package li.cil.sedna.cli;

import li.cil.sedna.device.serial.UART16550A;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class SerialConsole implements AutoCloseable {
    private final BlockingQueue<Integer> pending = new LinkedBlockingQueue<>();
    private final Thread reader;
    private volatile boolean isAtEndOfInput;

    SerialConsole() {
        reader = new Thread(this::readInput, "console-input");
        reader.setDaemon(true);
        reader.start();
    }

    boolean pump(final UART16550A uart) {
        int value;
        while ((value = uart.read()) != -1) {
            System.out.print((char) value);
        }
        System.out.flush();

        while (!pending.isEmpty() && uart.canPutByte()) {
            final Integer next = pending.poll();
            if (next != null) {
                uart.putByte((byte) next.intValue());
            }
        }

        return !isAtEndOfInput || !pending.isEmpty();
    }

    private void readInput() {
        final InputStream stream = System.in;
        try {
            int value;
            while ((value = stream.read()) != -1) {
                pending.put(value);
            }
        } catch (final IOException | InterruptedException ignored) {
            // done
        }
        isAtEndOfInput = true;
    }

    @Override
    public void close() {
        reader.interrupt();
    }
}
