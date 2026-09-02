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

## Axpert MAX Pylon RS485 operation

This binding was validated on an Axpert MAX Twin 8K configured as `PYL`, using
the inverter adapter at:

```text
/dev/serial/by-id/usb-FTDI_FT232R_USB_UART_A5069RR4-if00-port0
```

The inverter requests the Pylon ASCII system frames `61` (live data), `62`
(alarms) and `63` (charge/discharge management). The gateway responds only
after a valid request; it never transmits unsolicited frames. The live values
validated at the inverter are SOC, pack voltage and pack current. Voltage is
encoded in centivolts for Pylon (`53.0 V` is sent as `53.00 V`), while the
internal model stores it in tenths of a volt.

`63` carries the dynamic Fidus limits without fixed presets:

- charge voltage limit (`0x563C`)
- discharge voltage limit (`0x563D`)
- charge current limit (`0x563E`)
- discharge current limit (`0x563F`), capped at 200 A to match the pack's
  observed maximum

The Fidus `0x5646` status bitmap is recorded as `flags=0x....` whenever it
changes, but its bit layout is not yet decoded. Consequently the Pylon `62`
alarm response remains conservative-but-incomplete: it does not currently
map Fidus alarms/protections. This is the known limitation to address after a
natural, observed BMS event; do not induce a battery protection solely to
decode it.

## Persistent service and incident logs

The committed operational configuration is
`bms-to-inverter-main/src/main/resources/config-fidus-axpert.properties`.
The user service definition is `systemd/fidus-axpert.service`.

Install or refresh it with:

```shell
cd /home/sergio/fidus-gateway/reference/bms-to-inverter
install -D -m 0644 systemd/fidus-axpert.service \
  /home/sergio/.config/systemd/user/fidus-axpert.service
systemctl --user daemon-reload
systemctl --user enable --now fidus-axpert.service
```

The service starts Java through `sg dialout`, because the existing user
systemd manager may not inherit the `dialout` group after it is added to the
interactive user. This grants access to the two FTDI serial adapters without
requiring a system-wide service.

Operational commands:

```shell
systemctl --user status fidus-axpert.service
journalctl --user -u fidus-axpert.service -f
tail -100 logs/bmstoinverter.log
systemctl --user restart fidus-axpert.service
systemctl --user stop fidus-axpert.service
```

`logs/bmstoinverter.log` rotates daily or at 100 MB and keeps 30 archived
rollovers. Normal polling is logged at debug level; the persistent log keeps
startup, port reopen, telemetry loss/recovery, Fidus state/limit changes and
errors. Before troubleshooting, ensure this service is the only process using
either serial port.
