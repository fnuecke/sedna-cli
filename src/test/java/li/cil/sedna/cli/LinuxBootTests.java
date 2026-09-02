package li.cil.sedna.cli;

import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.buildroot.Buildroot;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.rtc.SystemTimeRealTimeCounter;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOKeyboardDevice;
import li.cil.sedna.riscv.R5Board;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
public final class LinuxBootTests {
    private static final int DECOY_DEVICES = 8; // the firmware tracks at most this many disks

    private static byte[] firmware;
    private static byte[] rootfs;

    @BeforeAll
    public static void loadImages() throws IOException {
        Sedna.initialize(); // registers the device tree providers the board publishes

        try (final InputStream stream = Buildroot.getSednaFirmware()) {
            firmware = stream.readAllBytes();
        }
        try (final InputStream stream = Buildroot.getRootFilesystem()) {
            rootfs = stream.readAllBytes();
        }
    }

    @Test
    public void bootsToLoginAndPowersOff() throws Exception {
        final Machine machine = new Machine();
        machine.awaitOutput("login:");
        machine.send("root\n");
        machine.awaitOutput("#");
        machine.send("df /\n");
        machine.awaitOutput("#");
        machine.send("poweroff\n");
        machine.awaitPowerOff();
    }

    @Test
    public void rebootBootsFromDiskAgain() throws Exception {
        final Machine machine = new Machine();
        machine.awaitOutput("login:");
        machine.send("root\n");
        machine.awaitOutput("#");
        machine.send("reboot\n");
        machine.awaitRestartAndReload();
        machine.awaitOutput("login:");
        machine.send("root\n");
        machine.awaitOutput("#");
        machine.send("poweroff\n");
        machine.awaitPowerOff();
    }

    @Test
    public void bootsPastNonBlockVirtioDevices() throws Exception {
        final Machine machine = new Machine(true, DECOY_DEVICES);
        machine.awaitOutput("login:");
    }

    @Test
    public void reportsMissingBootDiskAndBootsOnMediaChange() throws Exception {
        final Machine machine = new Machine(false);
        machine.awaitOutput("no bootable disk");
        machine.hdd.setBlock(ByteBufferBlockDevice.wrap(ByteBuffer.wrap(rootfs), false));
        machine.awaitOutput("login:");
    }

    // --------------------------------------------------------------------- //

    private static final class Machine {
        private static final long MAX_CYCLES = 2_000_000_000L; // a full boot takes about a tenth of this
        private static final int CYCLES_PER_STEP = 10_000;

        final R5Board board = new R5Board();
        final VirtIOBlockDevice hdd;
        private final PhysicalMemory memory = Memory.create(32 * 1024 * 1024);
        private final UART16550A uart = new UART16550A();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder pendingInput = new StringBuilder();
        private long cycles;

        Machine() throws IOException {
            this(true, 0);
        }

        Machine(final boolean withDisk) throws IOException {
            this(withDisk, 0);
        }

        Machine(final boolean withDisk, final int decoys) throws IOException {
            final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());
            hdd = new VirtIOBlockDevice(board.getMemoryMap(), withDisk
                ? ByteBufferBlockDevice.wrap(ByteBuffer.wrap(rootfs.clone()), false)
                : ByteBufferBlockDevice.create(0, false));

            uart.getInterrupt().set(0xA, board.getInterruptController());
            rtc.getInterrupt().set(0xB, board.getInterruptController());
            hdd.getInterrupt().set(0x1, board.getInterruptController());

            board.addDevice(0x80000000L, memory);
            board.addDevice(uart);
            board.addDevice(rtc);
            for (int i = 0; i < decoys; i++) {
                final VirtIOKeyboardDevice keyboard = new VirtIOKeyboardDevice(board.getMemoryMap());
                keyboard.getInterrupt().set(0x2 + i, board.getInterruptController());
                board.addDevice(keyboard);
            }
            board.addDevice(hdd);

            board.setBootArguments("root=/dev/vda rw");
            board.setStandardOutputDevice(uart);
            board.setFirmwareSize(Buildroot.getSednaFirmwareRegionSize());

            board.reset();
            loadFirmware();
            board.initialize();
            board.setRunning(true);
        }

        private void loadFirmware() throws MemoryAccessException {
            for (int address = 0; address < firmware.length; address++) {
                memory.store(address, firmware[address], Sizes.SIZE_8_LOG2);
            }
        }

        void send(final String text) {
            pendingInput.append(text);
        }

        void awaitOutput(final String expected) {
            final int start = output.length();
            final long deadline = cycles + MAX_CYCLES;
            while (output.indexOf(expected, start) < 0) {
                assertTrue(board.isRunning(), () -> "Machine stopped while waiting for [" + expected + "]. Output so far:\n" + output);
                assertTrue(cycles < deadline, () -> "Timed out waiting for [" + expected + "]. Output so far:\n" + output);
                step();
            }
        }

        void awaitPowerOff() {
            final long deadline = cycles + MAX_CYCLES;
            while (board.isRunning()) {
                assertTrue(cycles < deadline, () -> "Timed out waiting for poweroff. Output so far:\n" + output);
                step();
            }
            assertFalse(board.isRestarting(), () -> "Machine restarted instead of powering off. Output so far:\n" + output);
        }

        void awaitRestartAndReload() throws MemoryAccessException {
            final long deadline = cycles + MAX_CYCLES;
            while (!board.isRestarting()) {
                assertTrue(cycles < deadline, () -> "Timed out waiting for restart. Output so far:\n" + output);
                step();
            }
            loadFirmware();
            board.initialize();
        }

        private void step() {
            board.step(CYCLES_PER_STEP);
            cycles += CYCLES_PER_STEP;

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
