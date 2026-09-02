package li.cil.sedna.cli;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.buildroot.Buildroot;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.rtc.SystemTimeRealTimeCounter;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.concurrent.Callable;

@Command(name = "riscv", mixinStandardHelpOptions = true, description = "Boots the bundled RISC-V Linux image.")
public final class RiscVCommand implements Callable<Integer> {
    @Option(names = "-s", description = "Enable the GDB stub on port 55554.")
    private boolean enableGdbStub;

    @Option(names = "-S", description = "Enable the GDB stub and wait for a debugger to attach.")
    private boolean waitForGdb;

    @Option(names = "--terminal", description = "Use a jline terminal sized 80x25.")
    private boolean terminal;

    @Override
    public Integer call() throws Exception {
        if (waitForGdb) {
            enableGdbStub = true;
        }
        if (terminal) {
            runTerminal();
        } else {
            runSimple();
        }
        return 0;
    }

    private void runSimple() throws Exception {
        final R5Board board = new R5Board();
        final PhysicalMemory memory = Memory.create(32 * 1024 * 1024);
        final UART16550A uart = new UART16550A();
        final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());

        final Images images = getImages();

        final BlockDevice rootfs = ByteBufferBlockDevice.createFromStream(images.rootfs(), false);
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(), rootfs);
        final VirtIOFileSystemDevice fs = new VirtIOFileSystemDevice(board.getMemoryMap(), "host_fs", new HostFileSystem());

        uart.getInterrupt().set(0xA, board.getInterruptController());
        rtc.getInterrupt().set(0xB, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());
        fs.getInterrupt().set(0x2, board.getInterruptController());

        board.addDevice(0x80000000L, memory);
        board.addDevice(uart);
        board.addDevice(rtc);
        board.addDevice(hdd);
        board.addDevice(fs);

        board.setBootArguments("root=/dev/vda rw");
        board.setStandardOutputDevice(uart);

        board.setFirmwareSize(Buildroot.getSednaFirmwareRegionSize());

        board.reset();

        loadProgram(memory, images.firmware());

        board.initialize();
        board.setRunning(true);

        if (enableGdbStub) {
            board.enableGDB(55554, waitForGdb);
        }

        final int cyclesPerSecond = board.getCpu().getFrequency();
        final int cyclesPerStep = 1_000;

        try (final SerialConsole console = new SerialConsole()) {
            int remaining = 0;
            while (board.isRunning()) {
                final long stepStart = System.currentTimeMillis();

                remaining += cyclesPerSecond;
                while (remaining > 0) {
                    board.step(cyclesPerStep);
                    remaining -= cyclesPerStep;

                    console.pump(uart);
                }

                if (board.isRestarting()) {
                    loadProgram(memory, images.firmware());

                    board.initialize();
                }

                final long stepDuration = System.currentTimeMillis() - stepStart;
                final long sleep = 1000 - stepDuration;
                if (sleep > 0) {
                    //noinspection BusyWait
                    Thread.sleep(sleep);
                }
            }
        }
    }

    private void runTerminal() throws Exception {
        try (final Terminal terminal = TerminalBuilder.builder().jansi(false).jna(true).build()) {
            if (terminal.getClass() == DumbTerminal.class) {
                runSimple();
                return;
            }

            final NonBlockingReader reader = terminal.reader();
            final PrintWriter writer = terminal.writer();

            if (!terminal.getSize().equals(new Size(80, 25))) {
                try {
                    terminal.setSize(new Size(80, 25));
                } catch (final UnsupportedOperationException e) {
                    System.err.println("Cannot resize window to 80x25. Things might look a bit derpy.");
                }
            }

            final Images images = getImages();

            final R5Board board = new R5Board();
            final PhysicalMemory memory = Memory.create(20 * 1024 * 1024);
            final UART16550A uart = new UART16550A();
            final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());
            final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(),
                ByteBufferBlockDevice.createFromStream(images.rootfs(), true));

            uart.getInterrupt().set(0xA, board.getInterruptController());
            rtc.getInterrupt().set(0xB, board.getInterruptController());
            hdd.getInterrupt().set(0x1, board.getInterruptController());

            board.addDevice(0x80000000L, memory);
            board.addDevice(uart);
            board.addDevice(rtc);
            board.addDevice(hdd);

            board.setBootArguments("root=/dev/vda ro");
            board.setStandardOutputDevice(uart);

            board.setFirmwareSize(Buildroot.getSednaFirmwareRegionSize());

            board.reset();

            loadProgram(memory, images.firmware());

            board.initialize();
            board.setRunning(true);

            final int cyclesPerSecond = board.getCpu().getFrequency();
            final int cyclesPerStep = 1_000;

            int remaining = 0;
            while (board.isRunning()) {
                final long stepStart = System.currentTimeMillis();

                remaining += cyclesPerSecond;
                while (remaining > 0) {
                    board.step(cyclesPerStep);
                    remaining -= cyclesPerStep;

                    int value;
                    while ((value = uart.read()) != -1) {
                        writer.append((char) value);
                    }
                    writer.flush();

                    while (reader.available() > 0 && uart.canPutByte()) {
                        uart.putByte((byte) reader.read());
                    }
                }

                if (board.isRestarting()) {
                    loadProgram(memory, images.firmware());

                    board.initialize();
                }

                final long stepDuration = System.currentTimeMillis() - stepStart;
                final long sleep = 1000 - stepDuration;
                if (sleep > 0) {
                    //noinspection BusyWait
                    Thread.sleep(sleep);
                }
            }
        }
    }

    private void loadProgram(final PhysicalMemory memory, final byte[] program) throws MemoryAccessException {
        for (int address = 0; address < program.length; address++) {
            memory.store(address, program[address], Sizes.SIZE_8_LOG2);
        }
    }

    // The firmware is held as bytes because a restart re-loads it.
    private Images getImages() throws IOException {
        try (final InputStream firmware = Buildroot.getSednaFirmware()) {
            return new Images(firmware.readAllBytes(), Buildroot.getRootFilesystem());
        }
    }

    private record Images(byte[] firmware, InputStream rootfs) {
    }
}
