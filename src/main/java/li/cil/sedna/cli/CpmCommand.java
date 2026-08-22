package li.cil.sedna.cli;

import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.cpm.Cpm;
import li.cil.sedna.device.DeviceWindow;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.bus.DeviceEnumerator;
import li.cil.sedna.device.disk.WD1793;
import li.cil.sedna.device.flash.FlashMemoryDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.z80.BootRomLatch;
import li.cil.sedna.z80.Z80Board;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;

@Command(name = "cpm", mixinStandardHelpOptions = true, description = "Boots CP/M 2.2 on the Z80 board.")
public final class CpmCommand implements Callable<Integer> {
    @Option(names = "--write-protect", description = "Present the CP/M disk as write protected.")
    private boolean writeProtected;

    @Override
    public Integer call() throws Exception {
        runCpm();
        return 0;
    }

    /**
     * The CBIOS discovers everything through the enumeration window, so CPM_BUS_PORT is the only
     * address that has to agree with the ROM.
     */
    private static final int CPM_BUS_PORT = 0xE0;

    private static final int CPM_UART_PORT = 0x20;
    private static final int CPM_FDC_PORT = 0x10;
    private static final int CPM_LATCH_PORT = 0x30;

    private static final int CPM_FLOPPY_SIDES = 1;
    private static final int CPM_FLOPPY_TRACKS = 64;
    private static final int CPM_FLOPPY_SECTORS = 32;
    private static final int CPM_FLOPPY_SECTOR_SIZE = 128;

    private Z80Board createCpmBoard(final UART16550A uart, final WD1793 fdc) throws IOException {
        final Z80Board board = new Z80Board();

        if (!board.addDevice(0, Memory.create(0x10000))) {
            throw new IOException("Failed mapping RAM.");
        }
        if (!board.addPortDevice(CPM_UART_PORT, new DeviceWindow(uart, 8))
            || !board.addPortDevice(CPM_FDC_PORT, fdc)
            || !board.addPortDevice(CPM_LATCH_PORT, new BootRomLatch(board))
            || !board.addPortDevice(CPM_BUS_PORT, new DeviceEnumerator(board.getPortMap(),
            board.getDevices(), board.getInterruptController()))) {
            throw new IOException("Failed mapping port devices.");
        }

        // Three drives on the one controller: the CP/M disk, a blank formatted one, and an empty
        // drive that keeps its letter with nothing in it.
        fdc.setUnitCount(3);
        setDisk(fdc, 0, ByteBufferBlockDevice.createFromStream(Cpm.getFloppyImage(), writeProtected));
        setDisk(fdc, 1, blankFloppy());

        board.setBootRom(new FlashMemoryDevice(readBootRom()));

        board.reset();
        return board;
    }

    private void runCpm() throws Exception {
        final UART16550A uart = new UART16550A();
        final WD1793 fdc = new WD1793();
        final Z80Board board = createCpmBoard(uart, fdc);

        board.setRunning(true);

        final int cyclesPerSecond = board.getCpu().getFrequency();
        final int cyclesPerStep = 1_000;

        try (final SerialConsole console = new SerialConsole()) {
            int remaining = 0;
            while (board.isRunning() && !board.isHalted()) {
                final long stepStart = System.currentTimeMillis();

                remaining += cyclesPerSecond;
                while (remaining > 0 && !board.isHalted()) {
                    board.step(cyclesPerStep);
                    remaining -= cyclesPerStep;

                    console.pump(uart);
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

    private void setDisk(final WD1793 fdc, final int unit, final BlockDevice disk) {
        fdc.setDisk(unit, disk, CPM_FLOPPY_SIDES, CPM_FLOPPY_TRACKS, CPM_FLOPPY_SECTORS,
            CPM_FLOPPY_SECTOR_SIZE);
    }

    /**
     * A freshly formatted CP/M disk is filled with E5, which is what marks a directory entry unused.
     */
    private BlockDevice blankFloppy() throws IOException {
        final int size = CPM_FLOPPY_SIDES * CPM_FLOPPY_TRACKS * CPM_FLOPPY_SECTORS * CPM_FLOPPY_SECTOR_SIZE;
        final ByteBuffer blank = ByteBuffer.allocate(size);
        while (blank.hasRemaining()) {
            blank.put((byte) 0xE5);
        }
        blank.clear();
        return ByteBufferBlockDevice.wrap(blank, false);
    }

    private ByteBuffer readBootRom() throws IOException {
        try (final InputStream stream = Cpm.getBootRom()) {
            return ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
