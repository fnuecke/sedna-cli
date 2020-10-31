package li.cil.sedna.cli;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.buildroot.Buildroot;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;
import li.cil.sedna.riscv.R5CPU;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingReader;

import java.io.*;

public final class Main {
    public static void main(final String[] args) throws Exception {
        for (final String arg : args) {
            if ("--benchmark".equals(arg)) {
                runBenchmark();
                return;
            } else if ("--terminal".equals(arg)) {
                runTerminal();
                return;
            }
        }

        runSimple();
    }

    private static void runSimple() throws Exception {
        final R5Board board = new R5Board();
        final PhysicalMemory memory = Memory.create(40 * 1024 * 1024);
        final UART16550A uart = new UART16550A();
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(),
                ByteBufferBlockDevice.createFromStream(Buildroot.getRootFilesystem(), true));
        final VirtIOFileSystemDevice fs = new VirtIOFileSystemDevice(board.getMemoryMap(),
                "host_fs", new HostFileSystem(new File(".").getAbsoluteFile()));

        uart.getInterrupt().set(0xA, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());
        fs.getInterrupt().set(0x2, board.getInterruptController());

        board.addDevice(0x80000000, memory);
        board.addDevice(uart);
        board.addDevice(hdd);
        board.addDevice(fs);

        board.setBootArguments("root=/dev/vda ro");
        board.setStandardOutputDevice(uart);

        board.reset();

        loadProgramFile(memory, Buildroot.getFirmware());
        loadProgramFile(memory, Buildroot.getLinuxImage(), 0x400000);

        board.initialize();
        board.setRunning(true);

        final int cyclesPerSecond = board.getCpu().getFrequency();
        final int cyclesPerStep = 1_000;

        try (final InputStreamReader isr = new InputStreamReader(System.in)) {
            final BufferedReader br = new BufferedReader(isr);

            int remaining = 0;
            while (board.isRunning()) {
                final long stepStart = System.currentTimeMillis();

                remaining += cyclesPerSecond;
                while (remaining > 0) {
                    board.step(cyclesPerStep);
                    remaining -= cyclesPerStep;

                    int value;
                    while ((value = uart.read()) != -1) {
                        System.out.print((char) value);
                    }

                    while (br.ready() && uart.canPutByte()) {
                        uart.putByte((byte) br.read());
                    }
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

    private static void runTerminal() throws Exception {
        final Terminal terminal = TerminalBuilder.builder().jansi(false).jna(true).build();
        if (terminal.getClass() == DumbTerminal.class) {
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

        final R5Board board = new R5Board();
        final PhysicalMemory memory = Memory.create(40 * 1024 * 1024);
        final UART16550A uart = new UART16550A();
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(),
                ByteBufferBlockDevice.createFromStream(Buildroot.getRootFilesystem(), true));

        uart.getInterrupt().set(0xA, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());

        board.addDevice(0x80000000, memory);
        board.addDevice(uart);
        board.addDevice(hdd);

        board.setBootArguments("root=/dev/vda ro");
        board.setStandardOutputDevice(uart);

        board.reset();

        loadProgramFile(memory, Buildroot.getFirmware());
        loadProgramFile(memory, Buildroot.getLinuxImage(), 0x400000);

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

            final long stepDuration = System.currentTimeMillis() - stepStart;
            final long sleep = 1000 - stepDuration;
            if (sleep > 0) {
                //noinspection BusyWait
                Thread.sleep(sleep);
            }
        }
    }

    private static void runBenchmark() throws Exception {
        final R5Board board = new R5Board();
        final PhysicalMemory memory = Memory.create(40 * 1024 * 1024);
        final UART16550A uart = new UART16550A();
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(),
                ByteBufferBlockDevice.createFromStream(Buildroot.getRootFilesystem(), true));

        uart.getInterrupt().set(0xA, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());

        board.addDevice(0x80000000, memory);
        board.addDevice(uart);
        board.addDevice(hdd);

        board.setBootArguments("root=/dev/vda ro");
        board.setStandardOutputDevice(uart);

        System.out.println("Waiting for profiler...");
        Thread.sleep(5 * 1000);
        System.out.println("Initializing...");

        final long cyclesPerRun = 300_000_000;
        final int cyclesPerStep = 1_000;
        final int hz = board.getCpu().getFrequency();

        final int samples = 10;
        int minRunDuration = Integer.MAX_VALUE;
        int maxRunDuration = Integer.MIN_VALUE;
        int accRunDuration = 0;

        final R5CPU cpu = board.getCpu();
        final StringBuilder sb = new StringBuilder(16 * 1024);

        System.out.println("Starting...");

        for (int i = 0; i < samples; i++) {
            board.reset();

            for (int offset = 0; offset < memory.getLength(); offset += 4) {
                memory.store(offset, 0, Sizes.SIZE_32_LOG2);
            }

            loadProgramFile(memory, Buildroot.getFirmware());
            loadProgramFile(memory, Buildroot.getLinuxImage(), 0x400000);

            board.initialize();
            board.setRunning(true);

            sb.setLength(0);

            final long runStart = System.currentTimeMillis();

            final long limit = cpu.getTime() + cyclesPerRun;
            int remaining = 0;
            while (cpu.getTime() < limit && board.isRunning()) {
                remaining += hz;
                while (remaining > 0) {
                    board.step(cyclesPerStep);
                    remaining -= cyclesPerStep;

                    int value;
                    while ((value = uart.read()) != -1) {
                        sb.append((char) value);
                    }
                }
            }

            final int runDuration = (int) (System.currentTimeMillis() - runStart);
            accRunDuration += runDuration;
            minRunDuration = Integer.min(minRunDuration, runDuration);
            maxRunDuration = Integer.max(maxRunDuration, runDuration);

            System.out.print(sb.toString());

            System.out.printf("\n\ntime: %.2fs\n", runDuration / 1000.0);
        }

        final int avgDuration = accRunDuration / samples;
        System.out.printf("\n\ntimes: min=%.2fs, max=%.2fs, avg=%.2fs\n",
                minRunDuration / 1000.0,
                maxRunDuration / 1000.0,
                avgDuration / 1000.0);
        System.out.printf("\n\nhz: max=%.2fsMHz, min=%.2fMHz, avg=%.2fMHz\n",
                (cyclesPerRun / 1_000_000.0) / (minRunDuration / 1000.0),
                (cyclesPerRun / 1_000_000.0) / (maxRunDuration / 1000.0),
                (cyclesPerRun / 1_000_000.0) / (avgDuration / 1000.0));
    }

    private static void loadProgramFile(final PhysicalMemory memory, final InputStream stream) throws Exception {
        loadProgramFile(memory, stream, 0);
    }

    private static void loadProgramFile(final PhysicalMemory memory, final InputStream stream, int offset) throws Exception {
        final BufferedInputStream bis = new BufferedInputStream(stream);
        for (int address = offset, value = bis.read(); value != -1; value = bis.read(), address++) {
            memory.store(address, (byte) value, Sizes.SIZE_8_LOG2);
        }
    }
}
