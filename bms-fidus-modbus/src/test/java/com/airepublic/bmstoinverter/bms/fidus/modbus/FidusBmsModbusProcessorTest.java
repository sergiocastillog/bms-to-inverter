package com.airepublic.bmstoinverter.bms.fidus.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil.RegisterCode;

class FidusBmsModbusProcessorTest {
    @Test
    void parsesConfirmedRealCaptureAndAppliesCeilings() throws Exception {
        final FidusBmsModbusProcessor.Snapshot value = FidusBmsModbusProcessor.parse(summary(0xFD85), detail());

        assertEquals(2, value.state);
        assertEquals(538, value.packVoltage);
        assertEquals(-6, value.packCurrent); // internal unit 0.1 A; raw -635 mA
        assertEquals(259, value.tempAverage);
        assertEquals(576, value.maxPackVoltage);
        assertEquals(448, value.minPackVoltage);
        assertEquals(0, value.maxChargeCurrent); // zero must never become a default
        assertEquals(2000, value.maxDischargeCurrent); // dynamic 200.9 A capped at 200 A
        assertEquals(980, value.soc);
        assertEquals(1000, value.soh);
        assertEquals(3357, value.minCell);
        assertEquals(3378, value.maxCell);
        assertEquals(259, value.temperatures[0]);
    }

    @Test
    void parsesPositiveAndNegativeSignedCurrent() throws Exception {
        assertEquals(12, FidusBmsModbusProcessor.parse(summary(1234), detail()).packCurrent);
        assertEquals(-12, FidusBmsModbusProcessor.parse(summary((-1234) & 0xFFFF), detail()).packCurrent);
    }

    @Test
    void rejectsSentinelsAndTruncatedBlocks() {
        final int[] sentinel = summary(0xFD85);
        sentinel[0x5635 - FidusBmsModbusProcessor.SUMMARY_START] = 0xFFFF;
        assertThrows(IOException.class, () -> FidusBmsModbusProcessor.parse(sentinel, detail()));

        final int[] sentinel2 = summary(0xFD85);
        sentinel2[0x5644 - FidusBmsModbusProcessor.SUMMARY_START] = 0x7FFF;
        assertThrows(IOException.class, () -> FidusBmsModbusProcessor.parse(sentinel2, detail()));
        assertThrows(IOException.class, () -> FidusBmsModbusProcessor.parse(new int[2], detail()));
    }

    @Test
    void usesDisplayMatchedSummarySocWhenDetailDiffers() throws Exception {
        final int[] detail = detail();
        detail[0] = 970;
        final FidusBmsModbusProcessor.Snapshot value = FidusBmsModbusProcessor.parse(summary(0xFD85), detail);
        assertEquals(980, value.soc);
        assertEquals(970, value.detailSoc);
    }

    @Test
    void sendsOnlyFc04AndRejectsMissingOrMalformedResponses() throws Exception {
        final FidusBmsModbusProcessor processor = new FidusBmsModbusProcessor();
        final Port port = mock(Port.class);
        when(port.receiveFrame()).thenReturn(response(4, 2, 1, 10, 20));

        final int[] result = processor.readInputRegisters(port, 0x5631, 2, 1);
        assertEquals(10, result[0]);
        assertEquals(20, result[1]);
        verify(port).sendFrame(ModbusUtil.createRequestBuffer(RegisterCode.READ_INPUT_REGISTERS, 0x5631, 2, 1));

        when(port.receiveFrame()).thenReturn(null);
        assertThrows(IOException.class, () -> processor.readInputRegisters(port, 0x5631, 2, 1));
        when(port.receiveFrame()).thenReturn(response(0x84, 2, 1, 10, 20));
        assertThrows(IOException.class, () -> processor.readInputRegisters(port, 0x5631, 2, 1));
        when(port.receiveFrame()).thenReturn(response(4, 1, 1, 10));
        assertThrows(IOException.class, () -> processor.readInputRegisters(port, 0x5631, 2, 1));
    }

    private static int[] summary(final int rawCurrent) {
        final int[] r = new int[FidusBmsModbusProcessor.SUMMARY_COUNT];
        r[0x5632 - FidusBmsModbusProcessor.SUMMARY_START] = 2;
        r[0x5635 - FidusBmsModbusProcessor.SUMMARY_START] = 538;
        r[0x5636 - FidusBmsModbusProcessor.SUMMARY_START] = rawCurrent;
        r[0x5639 - FidusBmsModbusProcessor.SUMMARY_START] = 259;
        r[0x563C - FidusBmsModbusProcessor.SUMMARY_START] = 576;
        r[0x563D - FidusBmsModbusProcessor.SUMMARY_START] = 448;
        r[0x563E - FidusBmsModbusProcessor.SUMMARY_START] = 0;
        r[0x563F - FidusBmsModbusProcessor.SUMMARY_START] = 2009;
        r[0x5642 - FidusBmsModbusProcessor.SUMMARY_START] = 980;
        r[0x5644 - FidusBmsModbusProcessor.SUMMARY_START] = 1000;
        return r;
    }

    private static int[] detail() {
        final int[] r = new int[FidusBmsModbusProcessor.DETAIL_COUNT];
        r[0] = 980;
        final int[] cells = {3371, 3378, 3360, 3368, 3358, 3371, 3367, 3362,
                3374, 3373, 3357, 3364, 3372, 3364, 3364, 3361};
        for (int i = 0; i < 16; i++) {
            r[0x5722 - FidusBmsModbusProcessor.DETAIL_START + i] = cells[i];
            r[0x5732 - FidusBmsModbusProcessor.DETAIL_START + i] = 259 + i;
        }
        return r;
    }

    private static ByteBuffer response(final int function, final int count, final int unit, final int... registers) {
        final ByteBuffer buffer = ByteBuffer.allocate(12 + registers.length * 4);
        buffer.putInt(function).putInt(count).putInt(unit);
        for (final int register : registers) {
            buffer.putInt(register);
        }
        return (ByteBuffer) buffer.flip();
    }
}
