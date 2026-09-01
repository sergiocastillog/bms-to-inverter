package com.airepublic.bmstoinverter.bms.fidus.modbus;

import com.airepublic.bmstoinverter.core.BMS;
import com.airepublic.bmstoinverter.core.BMSConfig;
import com.airepublic.bmstoinverter.core.BMSDescriptor;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.protocol.modbus.J2ModMasterPort;

/** Descriptor for the Pylontech Fidus FB-L-16 read-only Modbus binding. */
public class FidusBmsModbusDescriptor implements BMSDescriptor {
    @Override
    public String getName() {
        return "FIDUS_MODBUS";
    }

    @Override
    public int getDefaultBaudRate() {
        return 9600;
    }

    @Override
    public Class<? extends BMS> getBMSClass() {
        return FidusBmsModbusProcessor.class;
    }

    @Override
    public Port createPort(final BMSConfig config) {
        return new J2ModMasterPort(config.getPortLocator(), config.getBaudRate());
    }
}
