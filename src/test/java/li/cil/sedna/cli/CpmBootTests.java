package li.cil.sedna.cli;

import li.cil.sedna.Sedna;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CpmBootTests {
    private static final int BUS_PORT = 0xE0; // the one address the ROM and the board must agree on
    private static final int CCP = 0xE200; // mirrors memmap.inc; the warm boot check reads this region
    private static final int BIOS = 0xF800;
    private static final int MAX_RUNNING_STATE = 256; // observed: under 100 bytes

    private static byte[] bootRom;
    private static byte[] floppy;

    @BeforeAll
    public static void loadImages() throws IOException {
        Sedna.initialize(); // registers the device descriptions the enumerator serves

        try (final InputStream stream = Cpm.getBootRom()) {
            bootRom = stream.readAllBytes();
        }
        try (final InputStream stream = Cpm.getFloppyImage()) {
            floppy = stream.readAllBytes();
        }
    }

    @Test
    public void bootsToPromptAndListsRomDisk() throws Exception {
        final Machine machine = new Machine(0x20, 0x10, 0x30, systemDisk());
        machine.awaitOutput("A>");
        machine.send("DIR\r");
        machine.awaitOutput("DEVS");
    }

    @ParameterizedTest
    @CsvSource({"0x20, 0x10, 0x30", "0x08, 0x70, 0xC0", "0x9C, 0x24, 0x68"})
    public void bootsRegardlessOfWhereDevicesAreMapped(final int uartPort, final int fdcPort, final int latchPort) throws Exception {
        final Machine machine = new Machine(uartPort, fdcPort, latchPort, systemDisk());
        machine.awaitOutput("A>");
    }

    @Test
    public void bootDriveBecomesA() throws Exception {
        // The system disk sits in the second drive; the first holds a blank. The ROM scans past
        // the blank, and the CBIOS letters the boot drive A: regardless of enumeration order.
        final Machine machine = new Machine(0x20, 0x10, 0x30, blankDisk(), systemDisk());
        machine.awaitOutput("A>");
        machine.send("DIR\r");
        machine.awaitOutput("DEVS");
        machine.awaitOutput("A>"); // a keypress during DIR aborts the listing, so wait it out
        // The blank disk kept a letter too, and is distinguishable from the boot disk.
        machine.send("DIR B:\r");
        machine.awaitOutput("NO FILE");
    }

    @Test
    public void reportsMissingSystemDiskAndBootsOnInsert() throws Exception {
        final Machine machine = new Machine(0x20, 0x10, 0x30, blankDisk());
        machine.awaitOutput("NO SYSTEM DISK");
        machine.insertDisk(0, systemDisk());
        machine.awaitOutput("A>");
    }

    @Test
    public void warmBootReloadsSystemFromDisk() throws Exception {
        final Machine machine = new Machine(0x20, 0x10, 0x30, systemDisk());
        machine.awaitOutput("A>");
        machine.idle(2_000_000);
        for (int address = 0xE200; address < 0xEA00; address++) {
            machine.board.getMemoryMap().store(address, 0x55, 0);
        }
        machine.send("\u0003"); // ^C at the prompt warm boots
        machine.awaitOutput("A>");

        final byte[] reloaded = new byte[BIOS - CCP];
        for (int i = 0; i < reloaded.length; i++) {
            reloaded[i] = (byte) machine.board.getMemoryMap().load(CCP + i, 0);
        }
        final byte[] onDisk = new byte[reloaded.length];
        System.arraycopy(floppy, Cpm.DiskGeometry.SECTOR_SIZE, onDisk, 0, onDisk.length);
        int differences = 0;
        for (int i = 0; i < reloaded.length; i++) {
            if (reloaded[i] != onDisk[i]) {
                differences++;
            }
        }
        final int count = differences;
        assertTrue(count < MAX_RUNNING_STATE, () -> "warm boot restored the system badly: "
            + count + " of " + reloaded.length + " bytes differ from the boot disk");

        machine.idle(2_000_000); // input pending while the CCP starts up is discarded, not typed
        machine.send("DIR\r");
        machine.awaitOutput("DEVS");
    }

    @Test
    public void warmBootRejectsNonSystemDisk() throws Exception {
        final Machine machine = new Machine(0x20, 0x10, 0x30, systemDisk());
        machine.awaitOutput("A>");
        machine.insertDisk(0, blankDisk());
        machine.send("\u0003");
        machine.awaitOutput("INSERT SYSTEM DISK");
        machine.insertDisk(0, systemDisk());
        machine.awaitOutput("A>");
    }

    @Test
    public void warmBootWaitsForEjectedSystemDisk() throws Exception {
        final Machine machine = new Machine(0x20, 0x10, 0x30, systemDisk());
        machine.awaitOutput("A>");
        machine.ejectDisk(0);
        machine.send("\u0003");
        machine.awaitOutput("INSERT SYSTEM DISK");
        machine.insertDisk(0, systemDisk());
        machine.awaitOutput("A>");
    }

    // --------------------------------------------------------------------- //

    private static BlockDevice systemDisk() {
        return ByteBufferBlockDevice.wrap(ByteBuffer.wrap(floppy.clone()), false);
    }

    private static BlockDevice blankDisk() {
        final ByteBuffer blank = ByteBuffer.allocate(Cpm.DiskGeometry.getImageSize());
        while (blank.hasRemaining()) {
            blank.put((byte) 0xE5);
        }
        blank.clear();
        return ByteBufferBlockDevice.wrap(blank, false);
    }

    private static final class Machine {
        private static final long MAX_CYCLES = 400_000_000;
        private static final int CYCLES_PER_STEP = 1_000;

        final Z80Board board;
        private final UART16550A uart = new UART16550A();
        private final WD1793 fdc = new WD1793();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder pendingInput = new StringBuilder();
        private long cycles;

        Machine(final int uartPort, final int fdcPort, final int latchPort, final BlockDevice... disks) throws IOException {
            board = new Z80Board();

            if (!board.addDevice(0, Memory.create(0x10000))) {
                throw new IOException("Failed mapping RAM.");
            }
            if (!board.addPortDevice(uartPort, new DeviceWindow(uart, 8))
                || !board.addPortDevice(fdcPort, fdc)
                || !board.addPortDevice(latchPort, new BootRomLatch(board))
                || !board.addPortDevice(BUS_PORT, new DeviceEnumerator(board.getPortMap(),
                board.getDevices(), board.getInterruptController()))) {
                throw new IOException("Failed mapping port devices.");
            }

            fdc.setUnitCount(disks.length);
            for (int unit = 0; unit < disks.length; unit++) {
                if (disks[unit] != null) {
                    insertDisk(unit, disks[unit]);
                }
            }

            board.setBootRom(new FlashMemoryDevice(ByteBuffer.wrap(bootRom.clone()).order(ByteOrder.LITTLE_ENDIAN)));

            board.reset();
            board.setRunning(true);
        }

        void insertDisk(final int unit, final BlockDevice disk) throws IOException {
            fdc.setDisk(unit, disk, Cpm.DiskGeometry.SIDES, Cpm.DiskGeometry.TRACKS,
                Cpm.DiskGeometry.SECTORS_PER_TRACK, Cpm.DiskGeometry.SECTOR_SIZE);
        }

        void ejectDisk(final int unit) throws IOException {
            fdc.removeDisk(unit);
        }

        void send(final String text) {
            pendingInput.append(text);
        }

        void idle(final long idleCycles) {
            for (long i = 0; i < idleCycles; i += CYCLES_PER_STEP) {
                board.step(CYCLES_PER_STEP);
                cycles += CYCLES_PER_STEP;
                pump();
            }
        }

        void awaitOutput(final String expected) {
            final int start = output.length();
            final long deadline = cycles + MAX_CYCLES;
            while (output.indexOf(expected, start) < 0) {
                assertTrue(cycles < deadline, () -> "Timed out waiting for [" + expected + "]. Output so far:\n" + output);
                board.step(CYCLES_PER_STEP);
                cycles += CYCLES_PER_STEP;
                pump();
            }
        }

        private void pump() {
            int value;
            while ((value = uart.read()) >= 0) {
                output.append((char) (value & 0x7F));
            }
            while (!pendingInput.isEmpty() && uart.canPutByte()) {
                uart.putByte((byte) pendingInput.charAt(0));
                pendingInput.deleteCharAt(0);
            }
        }
    }
}
