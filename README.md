# uni-meter

`uni-meter` is a small tool that emulates an electrical meter like a Shelly Pro3EM or an EcoTracker based on the
input data from a variety of input devices with the main purpose to control a storage system like the Hoymiles MS-A2
or the Marstek Venus. The `uni-meter` is not a full implementation of the emulated devices. Only the parts that are
needed by the storage systems are implemented. Especially the update of the data in the Shelly or Everhome cloud 
is not possible due to undocumented proprietary APIs. As a consequence, storage devices which are dependent on 
information from the Shelly or Everhome cloud are not supported.

The idea is to further enhance the tool in the future by adding more input and output devices to get a universal
converter between different electrical meters, inverters and storage systems.

Currently, the following output devices are supported:

* Everhome EcoTracker
* Shelly Pro 3EM
* SMA Energy Meter

The real electrical meter data can be gathered from the following input devices:

- AMIS Reader
- Emlog smart meter
- Fronius smart meter
- Generic HTTP (configurable HTTP interface, usable for many devices)
- Home Assistant sensors
- Huawei Inverter (DTSU666-H)
- ioBroker datapoints (via simple API adapter)
- Kostal Smart Energy Meter
- MQTT
- Shelly 3EM
- Shelly Pro 3EM
- SHRDZM smartmeter interface module (UDP)
- SMA energy meter / Sunny Home Manager (UDP protocol)
- SMD120 modbus energy meter (via Protos PE11) (SMD630 could be added, I have no test device)
- Solaredge
- Sungrow Smart Energy Meter (DTSU666)
- Tasmota IR read head (via HTTP)
- Tibber Pulse (local API) 
- VzLogger webserver

Storages known to be supported are

* Growatt NOAH 2000 & NEXA 2000
* Hoymiles MS-A2 & HiBattery 1920 AC
* Marstek B2500 & Venus
* Solakon One

The tool is built in Java and needs at least a Java 17 Runtime. If you want to use an ESP32 or a similar system which 
does not support Java 17, there is another project called [Energy2Shelly](https://github.com/TheRealMoeder/Energy2Shelly_ESP) which is written in C++ and can be used 
as an alternative on such systems. 

## Installation

There are different installation options available. You can choose to 

* **install the tool on a [Physical Server](doc/install/BareMetal.md)**
* **install the tool as [Home Assistant Add-On](doc/install/HomeAssistant.md)**
* **run the tool as [Docker Container](doc/install/Docker.md)**
* **or build it from [Source Code](doc/install/Building.md)** 

## Configuration

The configuration is done using a configuration file in the [HOCON format](https://github.com/lightbend/config/blob/main/HOCON.md). 

The basic structure of that configuration file is common in all setups.

You can configure the HTTP server port used by the ``uni-meter`` itself for its external API which defaults to port 80. 
Additionally, you have to choose which input device to use and which output device to use. Based on that choice, there 
are device-specific configuration sections for the input and output device. 

```hocon
uni-meter {
  output = "uni-meter.output-devices.<output-device-type>"
  
  input = "uni-meter.input-devices.<input-device-type>"

  http-server {
    port = 80
  }
  
  output-devices {
    <output-device-type> {
      # ...
    }
  }

  input-devices {
    <input-device-type> {
      # ...
    }
  }
}
```

Some sample configurations for different devices can be found [here](https://github.com/sdeigm/uni-meter/tree/main/samples).

## Output device configuration

To configure the output device, follow the instructions in these sections:

* **[Common configuration for all devices](doc/output/Common.md)**
* **[Eco-Tracker](doc/output/EcoTracker.md)**
* **[Shelly Pro 3EM](doc/output/ShellyPro3EM.md)**
* **[SMA Energy Meter](doc/output/SmaEM.md)**

## Input device configuration

To configure the input device, follow the instructions in these sections:

* **[AMIS Reader](doc/input/AmisReader.md)**
* **[Emlog](doc/input/Emlog.md)**
* **[Fronius](doc/input/Fronius.md)**
* **[Generic HTTP](doc/input/GenericHttp.md)**
* **[Home Assistant](doc/input/HomeAssistant.md)**
* **[Huawei Inverter](doc/input/Huawei.md)**
* **[ioBroker](doc/input/IoBroker.md)**
* **[Kostal Smart Energy Meter](doc/input/Kostal.md)**
* **[MQTT](doc/input/Mqtt.md)**
* **[Shelly 3EM](doc/input/Shelly3Em.md)**
* **[Shelly Pro 3EM](doc/input/ShellyPro3Em.md)**
* **[SHRDZM](doc/input/ShrDzm.md)**
* **[SMA Energy Meter](doc/input/SmaEnergyMeter.md)**
* **[SMD 120](doc/input/Smd120.md)**
* **[Solaredge](doc/input/Solaredge.md)**
* **[Sungrow Smart Energy Meter](doc/input/Sungrow.md)**
* **[Tasmota](doc/input/Tasmota.md)**
* **[Tibber Pulse](doc/input/TibberPulse.md)**
* **[VzLogger](doc/input/VzLogger.md)**

## External API

Starting with version 1.1.7, the `uni-meter` provides a REST API which can be used to externally control or change the
behavior. That API might, for instance, be used via a cron job using curl or directly from a web-browser.

* **[REST API to switch on/off](doc/api/SwitchOnOff.md)**
* **[REST API to prevent charging/discharging](doc/api/DontChargeDischarge.md)**
* **[REST API to dynamically adjust runtime parameters](doc/api/SetParameters.md)**

## Troubleshooting

`uni-meter` use the Java logback logging system. In the default setup only info, warning and error messages are written
to the logfile/standard output. For debugging purposes you can set the log level to `DEBUG` or even 
`TRACE` within the logback configuration file. The location of that configuration file differs depending on your setup 
type.

* For a physical server this is typically `/opt/uni-meter/config/logback.xml`
* For a docker setup it is/can be provided as a parameter to your startup command
* For a home assistant setup it can be placed in `/addon_configs/663b81ce_uni_meter`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yy-MM-dd HH:mm:ss.SSS} %-5level %-24logger - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>

    <logger name="uni-meter"  level="TRACE" additivity="false">
        <appender-ref ref="STDOUT" />
    </logger>
</Configuration>
```

A restart is necessary for these changes to take effect.

