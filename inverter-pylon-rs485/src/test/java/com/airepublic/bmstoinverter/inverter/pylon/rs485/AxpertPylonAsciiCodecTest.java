package com.airepublic.bmstoinverter.inverter.pylon.rs485;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.core.bms.data.EnergyStorage;
import com.airepublic.bmstoinverter.core.InverterConfig;
import com.airepublic.bmstoinverter.protocol.rs485.JSerialCommPort;
import com.airepublic.bmstoinverter.core.util.ByteReaderWriter;

class AxpertPylonAsciiCodecTest {
    private static final String DISCOVERY = "~0002464F0000FD9A\r";
    private static final String BATTERY_INFO = "~20024661E00202FD32\r";
    private static final String CELL_INFO = "~20024642E00202FD33\r";
    private static final String MANAGEMENT = "~20024692E00202FD2E\r";
    private static final String ALARMS = "~20024662E00202FD31\r";
    private static final String CHARGE_DISCHARGE = "~20024663E00202FD30\r";

    @Test
    void parsesEveryCapturedAxpertRequest() {
        assertRequest(DISCOVERY, 0x00, 0x4F);
        assertRequest(BATTERY_INFO, 0x20, 0x61);
        assertRequest(CELL_INFO, 0x20, 0x42);
        assertRequest(MANAGEMENT, 0x20, 0x92);
        assertRequest(ALARMS, 0x20, 0x62);
        assertRequest(CHARGE_DISCHARGE, 0x20, 0x63);
    }

    @Test
    void transportAcceptsBothDiscoveryAndOperationalVersions() {
        final PylonInverterRS485Descriptor descriptor = new PylonInverterRS485Descriptor();
        final JSerialCommPort port = (JSerialCommPort) descriptor.createPort(
                new InverterConfig("test", 9600, 1, descriptor));
        assertEquals(1, port.getStartFlag().length);
        assertEquals(0x7E, port.getStartFlag()[0] & 0xFF);
    }

    @Test
    void transportSplitsConcatenatedCarriageReturnDelimitedFrames() throws Exception {
        final PylonAsciiSerialPort port = new PylonAsciiSerialPort("test", 9600, 8, 1, 0);
        final ByteReaderWriter queue = new ByteReaderWriter();
        port.setQueue(queue);
        queue.write((DISCOVERY + BATTERY_INFO).getBytes(StandardCharsets.US_ASCII));
        assertEquals(DISCOVERY, ascii(port.getNextFrame()));
        assertEquals(BATTERY_INFO, ascii(port.getNextFrame()));
    }

    @Test
    void rejectsCorruptChecksumLengthAndHex() {
        assertThrows(IllegalArgumentException.class, () -> parse("~0002464F0000FFFF\r"));
        assertThrows(IllegalArgumentException.class, () -> parse("~20024661000202FD40\r"));
        assertThrows(IllegalArgumentException.class, () -> parse("~20024661E00402FD30\r"));
        assertThrows(IllegalArgumentException.class, () -> parse("~20024661E0020ZFD20\r"));
    }

    @Test
    void reproducesValidatedEmptyDiscoveryResponseByteForByte() {
        final PylonInverterRS485Processor processor = new PylonInverterRS485Processor(new EnergyStorage());
        final List<ByteBuffer> frames = processor.createSendFrames(asBuffer(DISCOVERY), new BatteryPack());
        assertEquals(1, frames.size());
        assertEquals("~200246000000FDB2\r", ascii(frames.get(0)));
    }

    @Test
    void operationalCommandsFailClosedWithoutBatteryData() {
        final PylonInverterRS485Processor processor = new PylonInverterRS485Processor(new EnergyStorage());
        assertTrue(processor.createSendFrames(asBuffer(BATTERY_INFO), new BatteryPack()).isEmpty());
        assertTrue(processor.createSendFrames(asBuffer(CELL_INFO), new BatteryPack()).isEmpty());
        assertTrue(processor.createSendFrames(asBuffer(MANAGEMENT), new BatteryPack()).isEmpty());
    }

    @Test
    void neverTransmitsWithoutAnAxpertRequest() {
        final PylonInverterRS485Processor processor = new PylonInverterRS485Processor(new EnergyStorage());
        assertTrue(processor.createSendFrames(null, freshPack()).isEmpty());
    }

    @Test
    void everyObservedOperationalResponseHasValidFraming() {
        final PylonInverterRS485Processor processor = new PylonInverterRS485Processor(new EnergyStorage());
        for (final String request : List.of(BATTERY_INFO, CELL_INFO, MANAGEMENT, ALARMS, CHARGE_DISCHARGE)) {
            final List<ByteBuffer> frames = processor.createSendFrames(asBuffer(request), freshPack());
            assertEquals(1, frames.size());
            final PylonInverterRS485Processor.ParsedRequest response =
                    PylonInverterRS485Processor.parseRequest(frames.get(0));
            assertEquals(0x20, response.version & 0xFF);
            assertEquals(0x02, response.address & 0xFF);
            assertEquals(0x46, response.cid1 & 0xFF);
            assertEquals(0x00, response.cid2 & 0xFF);
        }
    }

    @Test
    void batteryVoltageUsesPylonCentivoltsWithoutOverflow() {
        final PylonInverterRS485Processor processor = new PylonInverterRS485Processor(new EnergyStorage());
        final String frame = ascii(processor.createSendFrames(asBuffer(BATTERY_INFO), freshPack()).get(0));
        // 53.0 V from the common 0.1 V internal unit becomes 5300 centivolts (14B4).
        assertTrue(frame.contains("14B4"));
        assertTrue(!frame.contains("CF08"));
    }

    private static void assertRequest(final String raw, final int version, final int cid2) {
        final PylonInverterRS485Processor.ParsedRequest request = parse(raw);
        assertEquals(version, request.version & 0xFF);
        assertEquals(2, request.address & 0xFF);
        assertEquals(0x46, request.cid1 & 0xFF);
        assertEquals(cid2, request.cid2 & 0xFF);
    }

    private static PylonInverterRS485Processor.ParsedRequest parse(final String raw) {
        return PylonInverterRS485Processor.parseRequest(asBuffer(raw));
    }

    private static ByteBuffer asBuffer(final String raw) {
        return ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static String ascii(final ByteBuffer frame) {
        return new String(frame.array(), StandardCharsets.US_ASCII);
    }

    private static BatteryPack freshPack() {
        final BatteryPack pack = new BatteryPack();
        pack.numberOfCells = 16;
        pack.numOfTempSensors = 16;
        pack.packVoltage = 530;
        pack.packCurrent = -108;
        pack.packSOC = 940;
        pack.packSOH = 1000;
        pack.maxPackVoltageLimit = 576;
        pack.minPackVoltageLimit = 448;
        pack.maxPackChargeCurrent = 942;
        pack.maxPackDischargeCurrent = 2000;
        pack.maxCellmV = 3315;
        pack.minCellmV = 3313;
        pack.tempAverage = 249;
        pack.tempMax = 259;
        pack.tempMin = 246;
        for (int i = 0; i < 16; i++) {
            pack.cellVmV[i] = 3314;
            pack.cellTemperature[i] = 249;
        }
        return pack;
    }
}
