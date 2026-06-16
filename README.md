# BMS to Solar Inverter communication
## _*(Use, monitor and control any battery brand with any inverter)*_

<p align="center" with="100%">

<img src="https://github.com/user-attachments/assets/f0650c80-ef46-4b39-b63c-e64e172d1b6e"/> 

</p>

This application is reading data from one or multiple BMS(es) and sending it to an inverter using a microcontroller in between as a bridge. 
Many inverter manufacturers only allow batteries from certain battery manufacturers and certain models.
**With this application you have no restriction on what battery brands you can use with your inverter!!!** 

This project enables you to read your BMS's data via different protocols - RS485, RS232, UART, ModBus or CAN - and write the battery data to the inverter in a specification that the inverter supports - Pylontech, SMA, Growatt, Deye, SolArk, etc.
The appplication supports _multiple_ BMS (even mixes from different manufacturers), aggregating them and sending the data to the configurable inverter.

You can monitor each of your battery packs cells and view alarm states on the included webserver or hook up via the MQTT broker on your smart home.

It even let's you *manipulate* or *simulate* BMS data that get's sent to the inverter! Please see read plugin information in the [Wiki](https://github.com/ai-republic/bms-to-inverter/wiki/How-to-use).

**This way _you_ control what get's send to the inverter!**

This application will run on any microcontroller the can run a Java JDK (32 or 64bit) like PI's such as RPi 1 or RPi 5.
The (reference) project uses a Raspberry Pi 4B with a [Waveshare RS485/CAN](https://www.waveshare.com/rs485-can-hat.htm) hat or [Waveshare 2-Channel CAN FD HAT](https://www.waveshare.com/2-ch-can-fd-hat.htm) module but you can use any CAN or RS485 module for your PI that provides ports like `can0` or `/dev/ttyS0` or similar. 

A wide range of BMS and inverters are supported and new one's are added continuously on request, see [Supported-BMSes-and-Inverters](https://github.com/ai-republic/bms-to-inverter/wiki/Supported-BMSes-and-Inverters) in the Wiki.

**NOTE:** **If your BMS or inverter is not in the list it is likely to work with one of these bindings (like Pylon). Just open an issue and we'll see what I can do!**
**NOTE:** I would appreciate support to test the BMS and inverter bindings in all variations. Please let me know if you would like to support this project - Testers are very welcome! :)_
**NOTE** USB CAN adapters that do not create a proper CAN device but only a ttyUSB device have found to be problematic. So please choose the right hardware.

----------

## Supported protocols:
* RS485 / UART / RS232
* ModBus
* CAN

_**NOTE:** There are restrictions using CAN on Windows as SocketCAN library is *NOT* available on Windows OS_

----------

## Requirements

This project explicitly supports Java 8 because its the last version with 32-bit JDK 8 support that many microcontrollers support.
If you're using a Raspberry PI 3B, 4B or above I recommend to use a 64-bit Java JDK and 64-bit operating system like Raspian OS or Ubuntu.
The application has also been tested on Pi 1 - Pi5. Any microcontroller that can run a JDK 8+ can be used.
For detailed requirements please refer to the [How-to-Use](https://github.com/ai-republic/bms-to-inverter/wiki/How-to-use) in the Wiki.

----------

## How to use

See the Wiki page [How to use](https://github.com/ai-republic/bms-to-inverter/wiki/How-to-use) for details on how to install and configure your system using the [Configurator](https://github.com/ai-republic/bms-to-inverter/blob/main/configurator/current/configurator.jar).

**IMPORTANT**: If you use the dummy BMS (_NONE_) binding together with the inverter plugin _SimulatedBatteryPackPlugin_ you **MUST (!!!!!!!!!!) disconnect any _load_ and _PV DC input_** to prevent possible damage as the inverter will try charging/discharging!!!

----------

## Setup: Huawei ESM-48150B1 + Deye SUN-8K (RS485)

This section documents a working configuration for **6× Huawei ESM-48150B1** battery packs (Modbus RTU via RS485) with a **Deye SUN-8K-SG01LP1** inverter (Pylontech/Ho04 BMS port, RS485).

### Hardware

| Component | Connection |
|-----------|-----------|
| Huawei ESM-48150B1 × N | USB-to-RS485 adapter → BMS RS485 bus |
| Deye SUN-8K BMS port | USB-to-RS485 adapter → Deye BMS RS485 port |

Connect the RS485 A/B terminals of all ESM-48150B1 units in a daisy-chain to one adapter. Wire the Deye `RS485+`/`RS485−` BMS terminals to a second adapter. Set the Deye inverter BMS protocol to **Pylontech (Ho01/Ho04)** in the inverter settings menu.

**Use persistent device paths** — `/dev/ttyUSBN` numbers change when adapters are replugged. Linux creates stable symlinks automatically for any USB serial adapter that has a unique serial number (FTDI chips always do):

```bash
ls /dev/serial/by-id/
# usb-FTDI_FT232R_USB_UART_A10L900J-if00-port0 -> ../../ttyUSB1
# usb-FTDI_FT232R_USB_UART_BG00U13S-if00-port0 -> ../../ttyUSB3
```

Use the full `/dev/serial/by-id/...` path in `config.properties` — this is also how Solar Assistant identifies serial devices.

### Prerequisites

- **Java 8+** (64-bit JDK recommended on RPi 4+): `java -version`
- **Maven 3.8+** (to build from source): `mvn -version`
- Both adapters must be plugged in before the service starts

### Build

```bash
git clone https://github.com/marcelmorgan/bms-to-inverter.git
cd bms-to-inverter
mvn package -DskipTests
```

This produces `bms-to-inverter-main/target/bms-to-inverter.zip` containing all JARs.

### Deploy

```bash
DEPLOY=~/bms   # change to any path you prefer
mkdir -p $DEPLOY/{config,logs}
unzip bms-to-inverter-main/target/bms-to-inverter.zip -d $DEPLOY
```

Create `$DEPLOY/start.sh`:
```bash
cat > $DEPLOY/start.sh << 'EOF'
#!/bin/bash
java -DconfigFile=config/config.properties \
     -Dlog4j2.configurationFile=file:config/log4j2.xml \
     -jar lib/bms-to-inverter-main-0.0.1-SNAPSHOT.jar
EOF
chmod +x $DEPLOY/start.sh
```

### Configuration

Create `$DEPLOY/config/config.properties` — adjust slave IDs and port names for your setup:

First find the persistent path for each adapter (these survive unplugging and reboots):
```bash
ls /dev/serial/by-id/
# Example output:
#   usb-FTDI_FT232R_USB_UART_A10L900J-if00-port0 -> ../../ttyUSB1   ← BMS adapter
#   usb-FTDI_FT232R_USB_UART_BG00U13S-if00-port0 -> ../../ttyUSB3   ← Inverter adapter
```

Use those `/dev/serial/by-id/...` paths in your config — never use `/dev/ttyUSBN` directly, as the number changes when the adapter is replugged.

```properties
bms.pollInterval=5

# One entry per ESM-48150B1 unit. Slave addresses are set on the unit (default 1–6).
# Check the address of each unit with: mbpoll -a 1 -b 9600 -t 4:hex -r 0x1000 -c 1 /dev/serial/by-id/<your-bms-adapter>
bms.1.type=HUAWEI_ESM48150_MODBUS
bms.1.id=1              # Modbus slave address of first unit (0xD9 = 217 decimal if using Huawei defaults)
bms.1.portLocator=/dev/serial/by-id/usb-FTDI_FT232R_USB_UART_A10L900J-if00-port0
bms.1.baudRate=9600
bms.1.delayAfterNoBytes=200

# Repeat bms.2 through bms.N for each additional unit, changing .id for each slave address
# bms.2.type=HUAWEI_ESM48150_MODBUS
# bms.2.id=2
# bms.2.portLocator=/dev/serial/by-id/usb-FTDI_FT232R_USB_UART_A10L900J-if00-port0
# bms.2.baudRate=9600
# bms.2.delayAfterNoBytes=200

inverter.type=PYLON2_RS485
inverter.portLocator=/dev/serial/by-id/usb-FTDI_FT232R_USB_UART_BG00U13S-if00-port0
inverter.baudRate=9600
inverter.sendInterval=1
```

> **Slave addresses**: The ESM-48150B1 units ship with factory addresses. In this setup the six units use addresses 214–219 (0xD6–0xDB). Yours may differ. Use `mbpoll` or a Modbus scanner to confirm each unit's address before configuring.

Create `$DEPLOY/config/log4j2.xml` (adjust log levels as needed):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration>
  <Properties>
    <Property name="name">BMS-to-Inverter</Property>
    <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} | %-5p | %-20C:%-5L | %msg%n</Property>
  </Properties>
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="${pattern}"/>
    </Console>
    <RollingFile name="RollingFile" fileName="logs/${name}.log"
        filePattern="logs/$${date:yyyy-MM}/${name}-%d{yyyy-MM-dd}-%i.log.gz">
      <PatternLayout><pattern>${pattern}</pattern></PatternLayout>
      <Policies>
        <TimeBasedTriggeringPolicy/>
        <SizeBasedTriggeringPolicy size="100 MB"/>
      </Policies>
    </RollingFile>
  </Appenders>
  <Loggers>
    <Logger name="org.jboss.weld" level="error" additivity="false">
      <AppenderRef ref="Console"/><AppenderRef ref="RollingFile"/>
    </Logger>
    <Root level="info">
      <AppenderRef ref="Console"/><AppenderRef ref="RollingFile"/>
    </Root>
  </Loggers>
</Configuration>
```

### Run manually

```bash
cd ~/bms
./start.sh
```

Watch the log output — you should see `Received BMS data` lines for each pack within a few seconds. The Deye BMS detail screen should update within 30 seconds.

### Run as a systemd service (auto-start on boot)

Copy the service wrapper from the repo:
```bash
cp bmsservice.sh ~/bms/
chmod +x ~/bms/bmsservice.sh
```

Edit `~/bms/bmsservice.sh` and replace every occurrence of `~/bms-to-inverter` with `~/bms` (or whatever `$DEPLOY` path you chose).

Create the service unit:
```bash
sudo tee /etc/systemd/system/bms.service << EOF
[Unit]
Description=BMS to Inverter
After=multi-user.target

[Service]
Type=simple
Restart=always
RestartSec=3
ExecStop=runuser -l $USER -c "echo stop > ~/bms/stop"
ExecStart=runuser -l $USER -c "~/bms/bmsservice.sh -r"

[Install]
WantedBy=multi-user.target
EOF
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now bms.service
sudo systemctl status bms.service
```

Logs are written to `~/bms/logs/` and also available via `journalctl -u bms.service -f`.

----------

## Other Notes
**DISCLAIMER** I do not take _any_ responsibility for _any_ kind of damage or injury that might be caused by using this software. Use at your own risk.

If you have questions or need support feel free to contact me or raise an issue or discussion.
If you like to support me testing the application on all different BMSes and inverters please contact me!

## _**=====>>>>   Finally, if you like this project and like to support my work please consider sponsoring this project [`Sponsor`](https://github.com/sponsors/ai-republic) button on the right ❤️   <<<<=====**_

