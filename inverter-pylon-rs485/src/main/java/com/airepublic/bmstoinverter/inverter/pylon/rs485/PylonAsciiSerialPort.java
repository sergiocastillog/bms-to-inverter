package com.airepublic.bmstoinverter.inverter.pylon.rs485;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import com.airepublic.bmstoinverter.core.protocol.rs485.FrameDefinition;
import com.airepublic.bmstoinverter.protocol.rs485.JSerialCommPort;

/** Serial transport for Pylon ASCII frames delimited by '~' and carriage return. */
final class PylonAsciiSerialPort extends JSerialCommPort {
    private static final int MAX_FRAME_LENGTH = 4096;
    private static final long RECEIVE_WAIT_MS = 300;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    PylonAsciiSerialPort(final String portname, final int baudrate, final int dataBits,
            final int stopBits, final int parity) {
        super(portname, baudrate, dataBits, stopBits, parity, new byte[] { 0x7E },
                FrameDefinition.create("SOAAOCCCCOLLLDVVVVO"));
    }

    @Override
    public ByteBuffer getNextFrame() throws IOException {
        final long deadline = System.currentTimeMillis() + RECEIVE_WAIT_MS;
        do {
            final int value;
            try {
                value = queue.read() & 0xFF;
            } catch (final IOException noByteYet) {
                try {
                    Thread.sleep(2);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            if (value == 0x7E) {
                pending.reset();
                pending.write(value);
            } else if (pending.size() > 0) {
                pending.write(value);
                if (pending.size() > MAX_FRAME_LENGTH) {
                    pending.reset();
                    throw new IOException("Pylon ASCII frame exceeds maximum length");
                }
                if (value == 0x0D) {
                    final byte[] complete = pending.toByteArray();
                    pending.reset();
                    return ByteBuffer.wrap(complete);
                }
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }
}
