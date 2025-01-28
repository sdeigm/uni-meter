# uni-meter

uni-meter is a small tool that emulates a Shelly Pro3EM device for the usage with the Hoymiles MS-A2 storage.
It is not a full implementation of a Shelly Pro3EM device, currently only the parts that are needed by the Hoymiles 
storage are implemented.

The real electrical meter data currently can be gathered from the following devices:

- Generic HTTP (configurable HTTP interface, usable for many devices)
- MQTT
- Shelly 3EM (totally untested, I have no test device)
- SHRDZM smartmeter interface module (UDP)
- SMA energy meter / Sunny Home Manager (UDP protocol)
- SMD120 modbus energy meter (via Protos PE11) (SMD630 could be added, I have no test device)
- Tasmota IR read head (via HTTP)
- Tibber Pulse (local API) 
- VzLogger webserver

The idea is to further enhance the tool in the future by adding more input and output devices to get a universal 
converter between different electrical meters, inverters and storage systems.

## Download

The release versions are packaged as [GitHub package](https://github.com/sdeigm/uni-meter/packages/2336213).
You just have to download the `uni-meter-<version>.tgz` archive from the assets on the right side of the package's page.

## Building

The project can be build using Java 17 and Maven 3.8.5 or later by simply typing

```shell
mvn install
```

within the project's root directory. 

Afterward you will find a `uni-meter-<version>.tgz` archive in the `target` 
directory which can be used to deploy the tool to the target system which most likely is a Raspberry Pi.

## Installation

To install the tool on a Raspberry Pi, you need to have a Java 17 runtime installed. To check if this is the case, type

```shell
java -version
```

If the output shows a version 17 or later, you are good to go. If not, you can install the OpenJDK 17 using the following
commands:

```shell    
sudo apt update
sudo apt install openjdk-17-jre
```

Afterward, you can copy the `uni-meter-<version>.tgz` archive to the Raspberry Pi. In theory, you can extract the archive
to any location you like, but all the scripts and configuration files included assume an installation in the `/opt` 
directory. So preferably you should extract it to the `/opt` directory using the following commands:

```shell
sudo tar xzvf uni-meter-<version>.tgz -C /opt
```
```shell
sudo ln -s /opt/uni-meter-<version> /opt/uni-meter
```

## Announcing the tool via mDNS

To make the tool discoverable by the Hoymiles storage via mDNS, the `avahi-daemon` is used. On recent Raspbian versions,
the `avahi-daemon` is already installed and running. To check if this is the case, type

```shell
sudo systemctl status avahi-daemon
```

If you see an output like `active (running)`, you are good to go. If not, you can install the `avahi-daemon` using the

```shell
sudo apt install avahi-daemon
```

and enable it using the following command:

```shell
sudo systemctl enable avahi-daemon
sudo systemctl start avahi-daemon
```

Afterward you can copy the provided service files to the `/etc/avahi/services` directory.

```shell
sudo cp /opt/uni-meter/config/avahi/*.service /etc/avahi/services/
```

The daemon will automatically pick up the new service files and announce the tool via mDNS. No restart of the 
`avahi-daemon` is needed.

The provided service files announce the Shelly Pro3EM emulator running on port 80. Even if I used another port within the
service file, the Hoymiles storage does not seem to evaluate the port information and still connects to port 80. Maybe
future Hoymiles firmware version also will honor the port information.

In the meantime this has the disadvantage that the `uni-meter` tool has to bind to port 80 which requires root privileges.
Using `nginx` or using `setcap` to run the tool as a non-root user is possible but not covered in this documentation. 

## Configuration

The configuration is done using a configuration file in the [HOCON format](https://github.com/lightbend/config/blob/main/HOCON.md). 

The provided start script assumes the configuration file to be located in the `/etc` directory. To do so, copy the
provided configuration file to that location using:

```shell
sudo cp /opt/uni-meter/config/uni-meter.conf /etc/uni-meter.conf
```

Then use your favorite editor to adjust the configuration file to your needs as described in the following sections.

### Configuring the Shelly device id

It seems as if the Hoymiles storage expects a global unique identifier for the Shelly device. This Shelly device id
is freely configurable and can be set in the `/etc/uni-meter.conf` file. Please configure the mac address and the hostname
of your virtual Shelly Pro3EM device. The mac address should be an arbitrary 12 character long hexadecimal string. 
The hostname should end with the mac address in lower case.

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-pro3em {
      device {
        mac = "B827EB364242"
        hostname = "shellypro3em-b827eb364242"
      }
    }
  }
  #...
}
```

Additionally, you have to modify the two service files you copied into the `/etc/avahi/services` directory, so that the correct
device name is announced. Please replace the shellypro3em-b827eb364242 hostname with the one you have configured in the
`uni-meter.conf` file. Each service file contains the hostname twice. Once in the \<name> tag and once in the \<txt-record> tag.

```xml
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
    <name replace-wildcards="yes">shellypro3em-b827eb364242</name>
    <service protocol="ipv4">
        <type>_http._tcp</type>
        <port>80</port>
        <txt-record>gen=2</txt-record>
        <txt-record>id=shellypro3em-b827eb364242</txt-record>
        <txt-record>arch=esp8266</txt-record>
        <txt-record>fw_id=20241011-114455/1.4.4-g6d2a586</txt-record>
    </service>
</service-group>
```

### Enabling JSON RPC over UDP

As a default the JSON RPC over UDP interface of the Shelly Pro3EM emulator is disabled. To enable it, configure the 
`udp-port` and optionally the `udp-interface` in the `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-pro3em {
      #...
      udp-port = 1010
      udp-interface = "0.0.0.0" # default, can be omitted
      #...
    }
  }
  #...
}
```  

### Throttling the sampling frequency of the Shelly device

In some setups with a higher latency until the real electrical meter readings are available on the output side, it might
be necessary to throttle the sampling frequency of the output data. Otherwise, it might be possible that the storage
oversteer the power production and consumption values and that they are fluctuating too much around 0 (see the comments
and findings to this [issue](https://github.com/sdeigm/uni-meter/issues/12)).

To throttle the sampling frequency you can configure a `min-sampling-period` in the `/etc/uni-meter.conf` file. This 
configuration value specifies the minimum time until the next output data is delivered to the storage.

```hocon
uni-meter {
  #...
  output-devices {
    shelly-pro3em {
      #...
      min-sample-period = 5000ms
      #...
    }
  }
  #...
}
```

### Using the generic HTTP input source

The generic HTTP input source can be used to gather the electrical meter data from any device providing the data via
an HTTP get request as JSON value. You have to configure the complete URL where the data is gathered from, including
the entire path and query parameters.

The input source can operate in two modes. Either in a `mono-phase` mode, where the power and/or the energy data is
provided as a single value for all three phases, or in a `tri-phase` mode, where the power and/or the energy data is
provided as a separate value for each phase.

The input data is gathered through channels. The following channels exist for the different energy and power phase modes:
* Power `mono-phase`
  * `power-total` - total current power
* Power `tri-phase`
  * `power-l1` - current power phase 1
  * `power-l2` - current power phase 2
  * `power-l3` - current power phase 3
* Energy `mono-phase`
  * `energy-consumption-total` - total energy consumption
  * `energy-production-total` - total energy production
* Energy `tri-phase`
  * `energy-consumption-l1` - energy consumption phase 1
  * `energy-consumption-l2` - energy consumption phase 2
  * `energy-consumption-l3` - energy consumption phase 3
  * `energy-production-l1` - energy production phase 1
  * `energy-production-l2` - energy production phase 2
  * `energy-production-l3` - energy production phase 3

For each channel to be read, you have to configure where the data is gathered from and what type it is. Currently only 
the `json` type is supported, but in the future, other types might be added. 

For channels in JSON format, an additional JSON path has to be provided which specifies which part of the JSON data
contains the actual value.

Additionally, each channel has a `scale` property which can be used to scale the data. The default scale is 1.0 and can
be omitted.

So a `/etc/uni-meter.conf` file reading the data from a VzLogger webserver could look like this:

```hocon    
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://vzlogger-server:8088"
      
      #username = "username"
      #password = "password"
      
      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$.data[0].tuples[0][1]"
        scale = 0.001
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$.data[1].tuples[0][1]"
        scale = 0.001
      },{
        type = "json"
        channel = "power-total"
        json-path = "$.data[2].tuples[0][1]"
      }]
    }
  }
}
```

### Using MQTT as input source

The MQTT input source can operate in two modes. Either in a `mono-phase` mode, where the power and/or the energy data is
provided as a single value for all three phases, or in a `tri-phase` mode, where the power and/or the energy data is
provided as a separate value for each phase. 

The input data is gathered through channels. Each channel has a unique identifier and a topic where the data is gathered
from. The following channels exist for the different energy and power phase modes:
* Power `mono-phase`
    * `power-total` - total current power
* Power `tri-phase`
    * `power-l1` - current power phase 1
    * `power-l2` - current power phase 2
    * `power-l3` - current power phase 3
* Energy `mono-phase`
    * `energy-consumption-total` - total energy consumption 
    * `energy-production-total` - total energy production
* Energy `tri-phase`
    * `energy-consumption-l1` - energy consumption phase 1
    * `energy-consumption-l2` - energy consumption phase 2
    * `energy-consumption-l3` - energy consumption phase 3
    * `energy-production-l1` - energy production phase 1
    * `energy-production-l2` - energy production phase 2
    * `energy-production-l3` - energy production phase 3 

Each channel is linked to a topic where the data is gathered from and has a type which specifies how the data is
stored within the MQTT topic. Currently, two types are supported: `value` and `json`. Use the `value` type for data 
stored as a number string within the topic. Use the `json` type for data stored as JSON within the topic. For
channels in JSON format, an additional JSON path has to be provided which specifies which part of the JSON data
contains the actual value.

Additionally, each channel has a `scale` property which can be used to scale the data. The default scale is 1.0 and can
be omitted.

So a `/etc/uni-meter.conf` file for a MQTT input source could look like this:

```hocon    
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.mqtt"

  input-devices {
    mqtt {
      type = "MQTT"

      url = "tcp://127.0.0.1:1883"
      #username = "username"
      #password = "password"

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "power-total"
        json-path = "$..power"
        scale = 1.0 # default, can be omitted
      },{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "energy-consumption-total"
        json-path = "$..counter_pos"
      },{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "energy-production-total"
        json-path = "$..counter_neg"
      }]
    }
  }
}
```

### Using Shelly 3EM as input source

To use a Shelly 3EM as an input source, set up the `/etc/uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.shelly-3em"

  input-devices {
    shelly-3em {
      url = "<shelly-3em-url>"
    }
  }
}
```

Replace the `<shelly-3em-url>` placeholder with the actual URL of your Shelly 3EM device.

This input device is currently totally untested, as I have no test device. If you have a Shelly 3EM device, please provide
feedback if it works or not via the GitHub issues.

### Using SHRDZM smartmeter interface as input source

To use a SHRDZM smartmeter interface providing the smart meter readings via UDP, set up the `/etc/uni-meter.conf` file
as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.shrdzm"

  input-devices {
    shrdzm {
      port = 9522
      interface = "0.0.0.0"
    }
  }
}
```

The above configuration shows the default values for the ShrDzm device which are used, if nothing is provided. If you
want to use a different port or interface, you have to adjust the values accordingly.

### Using SMA energy meter as input source

To use a SMA energy meter or a Sunny Home Manager as an input source, set up the `/etc/uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.sma-energy-meter"

  input-devices {
    sma-energy-meter {
      port = 9522   
      group = "239.12.255.254"
      //susy-id = 270  
      //serial-number = 1234567
      network-interfaces =[
        "eth0"
        "wlan0"
        // "192.168.178.222"
      ]
    }
  }
}
```

The above configuration shows the default values which are used, if nothing is provided. If your `port` and `group` are
different, you have to adjust the values accordingly.

If no `susy-id` and `serial-number` are provided, the first detected device will be used. Otherwise, provide the values
of the device you want to use.

The network interfaces to use are provided as a list of strings. Either specify the names or the IP addresses of the 
interfaces you want to use.

### Using SMD120 modbus energy meter as input source

To use a SMD120 modbus energy meter via a Protos PE11 as an input source, set up the `/etc/uni-meter.conf` file as 
follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.smd120"

  input-devices {
    smd120 {
      port = 8899
    }
  }
}
```

### Using Tasmota IR read head as input source

To use a Tasmota IR read head as an input source, set up the `/etc/uni-meter.conf` file as follows:

```hocon
uni-meter {
  uni-meter {
    output = "uni-meter.output-devices.shelly-pro3em"

    input = "uni-meter.input-devices.tasmota"

    input-devices {
      tasmota {
        url = "http://<tasmota-ir-read-head-ip>"
        # username=""
        # password=""
        power-json-path = "$..curr_w"
        power-scale = 1.0 # default, can be omitted
        energy-consumption-json-path = "$..total_kwh"
        energy-consumption-scale = 1.0 # default, can be omitted
        energy-production-json-path = "$..export_total_kwh"
        energy-production-scale = 1.0 # default, can be omitted
      }
    }
  }
}
```

Replace the `<tasmota-ir-read-head-ip>` placeholder with the actual IP address of your Tasmota IR read head device.
If you have set a username and password for the device, you have to provide them as well.

Additionally, you have to configure the JSON paths for the power, energy consumption and energy production values to
access the actual values within the JSON data. If you have to scale these values, you can provide a scale factor which
is 1.0 as a default.

### Using Tibber Pulse as input source

The Tibber Pulse local API can be used as an input source. To use this API, the local HTTP server has to be enabled on 
the Pulse bridge. How this can be done is described for instance here 
[marq24/ha-tibber-pulse-local](https://github.com/marq24/ha-tibber-pulse-local).

If this API is enabled on your Tibber bridge, you should set up the `/etc/uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.tibber-pulse"

  input-devices {
    tibber-pulse {
      url = "<tibber-device-url>"
      node-id = 1
      user-id = "admin"
      password = "<tibber-device-password>"
    }
  }
}
```

Replace the `<tibber-device-url>` and `<tibber-device-password>` placeholders with the actual values from your environment.  
The `node-id` and `user-id` are optional and can be omitted if the default values from above are correct. Otherwise,
adjust the values accordingly.

### Using VzLogger webserver as input source

To use the VzLogger webserver as an input source set up the `/etc/uni-meter.conf` file as follows and replace the
`<vzlogger-host>` and `<vzlogger-port>` placeholders with the actual host and port of your VzLogger webserver.
Additionally provide the channel UUIDs of your system.

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.vz-logger"

  input-devices {
    vz-logger {
      url = "http://<vzlogger-host>:<vzlogger-port>"
      energy-consumption-channel = "5478b110-b577-11ec-873f-179XXXXXXXX"
      energy-production-channel = "6fda4300-b577-11ec-8636-7348XXXXXXXX"
      power-channel = "e172f5b5-76cd-42da-abcc-effeXXXXXXXX"
    }
  }
}
```

You will find that information in the VzLogger configuration file. As a
default, the VzLogger is configured in the `/etc/vzlogger.conf` file. Make sure that the VzLogger provides its
readings as Web service and extract the needed information from that file:

```hocon
{
  // ...
    
  // Build-in HTTP server
  "local": {
    "enabled": true,    // This has to be enabled to provide the readings via HTTP
    "port": 8088,       // Port used by the HTTP server
    
    // ... 
  }
  // ...  
    
  "meters": [
    {
      // ...
    
      "channels": [{
        "uuid" : "5478b110-b577-11ec-873f-179bXXXXXXXX",  // UUID of the energy consumption channel
        "middleware" : "http://localhost/middleware.php",
        "identifier" : "1-0:1.8.0", // 1.8.0 is the energy consumption channel
        "aggmode" : "MAX"
      },{
        "uuid" : "6fda4300-b577-11ec-8636-7348XXXXXXXX",  // UUID of the energy production channel
        "middleware" : "http://localhost/middleware.php",
        "identifier" : "1-0:2.8.0", // 2.8.0 is the energy production channel
        "aggmode" : "MAX"
      },{
        "uuid" : "e172f5b5-76cd-42da-abcc-effef3b895b2", // UUID of the power channel
        "middleware" : "http://localhost/middleware.php",
        "identifier" : "1-0:16.7.0", // 16.7.0 is the power channel
      }]
    }
  ]
}
````

### First test

After you have adjusted the configuration file, you can start the tool using command

```shell
sudo /opt/uni-meter/bin/uni-meter.sh
```

If everything is set up correctly, the tool should start up, and you should see an output like

```shell
24-12-04 07:29:08.006 INFO  uni-meter                - ##################################################################
24-12-04 07:29:08.030 INFO  uni-meter                - # Universal electric meter converter 0.9.0 (2024-12-04 04:58:16) #
24-12-04 07:29:08.031 INFO  uni-meter                - ##################################################################
24-12-04 07:29:08.033 INFO  uni-meter                - initializing actor system
24-12-04 07:29:10.902 INFO  org.apache.pekko.event.slf4j.Slf4jLogger - Slf4jLogger started
24-12-04 07:29:11.707 INFO  uni-meter.controller     - creating Shelly3EM output device
24-12-04 07:29:11.758 INFO  uni-meter.controller     - creating VZLogger input device
24-12-04 07:29:16.254 INFO  uni-meter.http.port-80   - HTTP server is listening on /[0:0:0:0:0:0:0:0]:80
```

Now you should be able to connect your Hoymiles storage to the emulator using the Hoymiles app.

## Automatic start using systemd

To start the tool automatically on boot, you can use the provided systemd service file. To do so, create a symlink 
within the `/etc/systemd/system` directory using the following command:

```shell
sudo ln -s /opt/uni-meter/config/systemd/uni-meter.service /etc/systemd/system/uni-meter.service
```

Afterward, you can enable the service using the following command so that it will be automatically started on boot:

```shell
sudo systemctl enable uni-meter
```

To start and stop the service immediately run

```shell
sudo systemctl start uni-meter
sudo systemctl stop uni-meter
```
The status of the service can be checked using

```shell
sudo systemctl status uni-meter
```

## Troubleshooting

If you start the tool directly from the command line, all error messages will be printed to the console. If you start the
tool using the systemd service, you can check the log messages in `/var/log/uni-meter.log`.

As a default the log level is just set to `INFO`. For debugging purposes you can set the log level to `DEBUG` or even 
`TRACE` within the `/opt/uni-meter/config/logback.xml` file.

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





