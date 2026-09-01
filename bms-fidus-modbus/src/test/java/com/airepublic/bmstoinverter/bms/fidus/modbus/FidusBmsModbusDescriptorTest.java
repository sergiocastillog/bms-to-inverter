package com.airepublic.bmstoinverter.bms.fidus.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.airepublic.bmstoinverter.core.BMSConfig;
import com.airepublic.bmstoinverter.protocol.modbus.J2ModMasterPort;

class FidusBmsModbusDescriptorTest {
    private final FidusBmsModbusDescriptor descriptor = new FidusBmsModbusDescriptor();

    @Test
    void describesBinding() {
        assertEquals("FIDUS_MODBUS", descriptor.getName());
        assertEquals(9600, descriptor.getDefaultBaudRate());
        assertEquals(FidusBmsModbusProcessor.class, descriptor.getBMSClass());
        assertInstanceOf(J2ModMasterPort.class, descriptor.createPort(Mockito.mock(BMSConfig.class)));
    }
}
