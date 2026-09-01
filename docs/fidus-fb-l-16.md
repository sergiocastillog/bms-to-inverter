# Pylontech Fidus FB-L-16

The `FIDUS_MODBUS` BMS binding reads the confirmed FB-L-16 register map over
RS485 Modbus RTU, 9600 8N1, unit 1. It is strictly read-only and only issues
FC04 requests. It performs two block reads with a 750 ms pause:

- `0x5631`, 22 registers: pack state, voltage/current, average temperature,
  dynamic limits, display-matched SOC and SOH.
- `0x571A`, 40 registers: redundant SOC, 16 cell voltages and 16 temperature
  sensors. The operational SOC is `0x5642`; it matched the physical battery
  display at 94%, while `0x571A` simultaneously reported 98%.

Example BMS configuration:

```properties
bms.pollInterval=1
bms.1.type=FIDUS_MODBUS
bms.1.id=1
bms.1.portLocator=/dev/serial/by-id/usb-FTDI_USB_Serial_Converter_FTB6SPL3-if00-port0
bms.1.baudRate=9600
bms.1.delayAfterNoBytes=1000
```

Build and test with:

```shell
mvn -pl bms-fidus-modbus -am test
mvn clean package
```

The Modbus transport delegates CRC, timeout and exception-response validation
to j2mod. The binding additionally rejects missing/truncated data, unexpected
unit/function/count and `0xFFFF`/`0x7FFF` sentinels in every
required field. Unknown alarm/protection/MOS/cycle fields are not synthesized.

Do not run another process such as `fidus_live.py` on the same serial port.
