package com.airepublic.bmstoinverter.bms.fidus.modbus;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.airepublic.bmstoinverter.core.BMS;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil.RegisterCode;

/**
 * Read-only reader for the experimentally confirmed FB-L-16 input registers.
 * No discovery and no Modbus write function are used by this class.
 */
public class FidusBmsModbusProcessor extends BMS {
    static final int SUMMARY_START = 0x5631;
    static final int SUMMARY_COUNT = 22;
    static final int DETAIL_START = 0x571A;
    static final int DETAIL_COUNT = 40;
    static final long INTER_REQUEST_DELAY_MS = 750;
    static final int CHARGE_CURRENT_CEILING = 1800; // 0.1 A
    static final int DISCHARGE_CURRENT_CEILING = 2000; // 0.1 A

    private volatile long lastValidUpdate;
    private volatile boolean dataValid;

    @Override
    protected void collectData(final Port port) throws IOException {
        final int unitId = getBmsId();
        final BatteryPack pack = getBatteryPack(unitId);
        try {
            final int[] summary = readInputRegisters(port, SUMMARY_START, SUMMARY_COUNT, unitId);

            try {
                Thread.sleep(INTER_REQUEST_DELAY_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while pacing Fidus FC04 requests", e);
            }

            final int[] detail = readInputRegisters(port, DETAIL_START, DETAIL_COUNT, unitId);
            final Snapshot snapshot = parse(summary, detail);
            apply(snapshot, pack);
            lastValidUpdate = System.currentTimeMillis();
            pack.lastValidUpdate = lastValidUpdate;
            pack.dataValid = true;
            dataValid = true;
        } catch (final IOException e) {
            dataValid = false;
            pack.dataValid = false;
            throw e;
        }
    }

    int[] readInputRegisters(final Port port, final int start, final int count, final int unitId) throws IOException {
        port.sendFrame(ModbusUtil.createRequestBuffer(RegisterCode.READ_INPUT_REGISTERS, start, count, unitId));
        final ByteBuffer response = port.receiveFrame();
        if (response == null || response.remaining() < 12) {
            throw new IOException("Missing or truncated Fidus Modbus response");
        }

        final int function = response.getInt();
        final int responseCount = response.getInt();
        final int responseUnit = response.getInt();
        if (function != RegisterCode.READ_INPUT_REGISTERS.getFunctionCode()
                || responseCount != count || responseUnit != unitId
                || response.remaining() != count * Integer.BYTES) {
            throw new IOException("Invalid Fidus Modbus response header or register count");
        }

        final int[] registers = new int[count];
        for (int i = 0; i < count; i++) {
            registers[i] = response.getInt() & 0xFFFF;
        }
        return registers;
    }

    static Snapshot parse(final int[] summary, final int[] detail) throws IOException {
        requireLength(summary, SUMMARY_COUNT, "summary");
        requireLength(detail, DETAIL_COUNT, "detail");

        final Snapshot value = new Snapshot();
        value.state = required(summary, 0x5632 - SUMMARY_START, "state");
        value.packVoltage = required(summary, 0x5635 - SUMMARY_START, "pack voltage");
        value.packCurrent = (short) required(summary, 0x5636 - SUMMARY_START, "pack current") / 100;
        value.tempAverage = required(summary, 0x5639 - SUMMARY_START, "average temperature");
        value.maxPackVoltage = required(summary, 0x563C - SUMMARY_START, "charge voltage limit");
        value.minPackVoltage = required(summary, 0x563D - SUMMARY_START, "discharge voltage limit");
        value.maxChargeCurrent = Math.min(required(summary, 0x563E - SUMMARY_START, "charge current limit"), CHARGE_CURRENT_CEILING);
        value.maxDischargeCurrent = Math.min(required(summary, 0x563F - SUMMARY_START, "discharge current limit"), DISCHARGE_CURRENT_CEILING);
        value.soc = required(summary, 0x5642 - SUMMARY_START, "display SOC");
        value.soh = required(summary, 0x5644 - SUMMARY_START, "SOH");

        // 0x5642 has been checked against the physical FB-L-16 display.
        // 0x571A can differ (94% vs 98% observed), so keep it diagnostic only.
        value.detailSoc = required(detail, 0, "secondary SOC");

        value.cells = new int[16];
        value.temperatures = new int[16];
        value.minCell = Integer.MAX_VALUE;
        value.maxCell = Integer.MIN_VALUE;
        value.minTemperature = Integer.MAX_VALUE;
        value.maxTemperature = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            value.cells[i] = required(detail, 0x5722 - DETAIL_START + i, "cell voltage " + (i + 1));
            if (value.cells[i] < value.minCell) {
                value.minCell = value.cells[i];
                value.minCellNumber = i + 1;
            }
            if (value.cells[i] > value.maxCell) {
                value.maxCell = value.cells[i];
                value.maxCellNumber = i + 1;
            }
            value.temperatures[i] = optional(detail[0x5732 - DETAIL_START + i]);
            if (value.temperatures[i] != Integer.MIN_VALUE) {
                value.temperatureCount++;
                if (value.temperatures[i] < value.minTemperature) {
                    value.minTemperature = value.temperatures[i];
                    value.minTemperatureNumber = i + 1;
                }
                if (value.temperatures[i] > value.maxTemperature) {
                    value.maxTemperature = value.temperatures[i];
                    value.maxTemperatureNumber = i + 1;
                }
            }
        }
        return value;
    }

    private static void apply(final Snapshot value, final BatteryPack pack) {
        pack.manufacturerCode = "Pylontech Fidus FB-L-16";
        pack.numberOfCells = 16;
        pack.packVoltage = value.packVoltage;
        pack.packCurrent = value.packCurrent;
        pack.tempAverage = value.tempAverage;
        pack.numOfTempSensors = value.temperatureCount;
        if (value.temperatureCount > 0) {
            pack.tempMin = value.minTemperature;
            pack.tempMax = value.maxTemperature;
            pack.tempMinCellNum = value.minTemperatureNumber;
            pack.tempMaxCellNum = value.maxTemperatureNumber;
        }
        pack.maxPackVoltageLimit = value.maxPackVoltage;
        pack.minPackVoltageLimit = value.minPackVoltage;
        pack.maxPackChargeCurrent = value.maxChargeCurrent;
        pack.maxPackDischargeCurrent = value.maxDischargeCurrent;
        pack.packSOC = value.soc;
        pack.packSOH = value.soh;
        pack.chargeDischargeStatus = value.state;
        pack.minCellmV = value.minCell;
        pack.maxCellmV = value.maxCell;
        pack.minCellVNum = value.minCellNumber;
        pack.maxCellVNum = value.maxCellNumber;
        pack.cellDiffmV = value.maxCell - value.minCell;
        for (int i = 0; i < 16; i++) {
            pack.cellVmV[i] = value.cells[i];
            if (value.temperatures[i] != Integer.MIN_VALUE) {
                pack.cellTemperature[i] = value.temperatures[i];
            }
        }
    }

    private static int required(final int[] values, final int index, final String name) throws IOException {
        final int value = values[index] & 0xFFFF;
        if (value == 0xFFFF || value == 0x7FFF) {
            throw new IOException("Fidus returned no-data sentinel for " + name);
        }
        return value;
    }

    private static int optional(final int value) {
        return value == 0xFFFF || value == 0x7FFF ? Integer.MIN_VALUE : value;
    }

    private static void requireLength(final int[] values, final int length, final String block) throws IOException {
        if (values == null || values.length != length) {
            throw new IOException("Invalid Fidus " + block + " block length");
        }
    }

    public boolean isDataValid() {
        return dataValid && System.currentTimeMillis() - lastValidUpdate <= 3000;
    }

    public long getLastValidUpdate() {
        return lastValidUpdate;
    }

    static final class Snapshot {
        int state;
        int packVoltage;
        int packCurrent;
        int tempAverage;
        int maxPackVoltage;
        int minPackVoltage;
        int maxChargeCurrent;
        int maxDischargeCurrent;
        int soc;
        int detailSoc;
        int soh;
        int minCell;
        int maxCell;
        int minCellNumber;
        int maxCellNumber;
        int[] cells;
        int[] temperatures;
        int temperatureCount;
        int minTemperature;
        int maxTemperature;
        int minTemperatureNumber;
        int maxTemperatureNumber;
    }
}
