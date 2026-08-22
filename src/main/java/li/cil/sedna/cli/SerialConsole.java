package li.cil.sedna.cli;

import li.cil.sedna.device.serial.UART16550A;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class SerialConsole implements AutoCloseable {
    private final InputStreamReader source = new InputStreamReader(System.in);
    private final BufferedReader input = new BufferedReader(source);

    void pump(final UART16550A uart) throws IOException {
        int value;
        while ((value = uart.read()) != -1) {
            System.out.print((char) value);
        }
        System.out.flush();

        while (input.ready() && uart.canPutByte()) {
            uart.putByte((byte) input.read());
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
