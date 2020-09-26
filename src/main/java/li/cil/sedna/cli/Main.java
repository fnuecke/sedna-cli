package li.cil.sedna.cli;

import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.riscv.R5Board;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;

public final class Main {
    private static final String firmware = "buildroot/output/images/fw_jump.bin";
    private static final String kernel = "buildroot/output/images/Image";
    private static final File rootfsFile = new File("buildroot/output/images/rootfs.ext2");

    public static void main(final String[] args) throws Exception {
        final Terminal terminal = TerminalBuilder.builder().jansi(false).jna(true).build();
        if (terminal.getClass() == DumbTerminal.class) {
            return;
        }

        final NonBlockingReader reader = terminal.reader();
        final PrintWriter writer = terminal.writer();

        writer.println("Starting Sedna CLI...");

        if (!terminal.getSize().equals(new Size(80, 25))) {
            try {
                terminal.setSize(new Size(80, 25));
            } catch (final UnsupportedOperationException e) {
                writer.println("Cannot resize window to 80x25. Things might look a bit derpy.");
            }
        }

        final R5Board board = new R5Board();
        final PhysicalMemory rom = Memory.create(128 * 1024);
        final PhysicalMemory memory = Memory.create(32 * 1014 * 1024);
        final UART16550A uart = new UART16550A();
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(), ByteBufferBlockDevice.createFromFile(rootfsFile, true));

        uart.getInterrupt().set(0xA, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());

        board.addDevice(0x80000000, rom);
        board.addDevice(0x80000000 + 0x400000, memory);
        board.addDevice(uart);
        board.addDevice(hdd);

        board.setBootargs("console=ttyS0 root=/dev/vda ro");

        board.reset();

        loadProgramFile(memory, 0, kernel);
        loadProgramFile(rom, 0, firmware);

        final int cyclesPerSecond = board.getCpu().getFrequency();
        final int cyclesPerStep = 1_000;

        int remaining = 0;
        for (; ; ) {
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
                Thread.sleep(sleep);
            }
        }
    }

    private static void loadProgramFile(final PhysicalMemory memory, int address, final String path) throws Exception {
        try (final FileInputStream is = new FileInputStream(path)) {
            final BufferedInputStream bis = new BufferedInputStream(is);
            for (int value = bis.read(); value != -1; value = bis.read()) {
                memory.store(address++, (byte) value, Sizes.SIZE_8_LOG2);
            }
        }
    }
}
