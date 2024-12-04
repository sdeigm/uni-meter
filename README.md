# uni-meter

uni-meter is a small tool that simulates a Shelly Pro3EM device for the usage with the Hoymiles MS-A2 storage.
It is not a full implementation of a Shelly Pro3EM device, currently only the parts that are needed by the Hoymiles 
storage are implemented.

The real electrical meter data currently can be gathered either from a VzLogger webserver or from a SMA energy meter 
using the SMA energy meter UDP protocol.

The idea is to further enhance the tool in the future by adding more input and output devices to get a universal converter between
different electrical meters, inverters and storage systems.

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
sudo tar -xzvf uni-meter-<version>.tgz -C /opt
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

The configuration is done using a configuration file in the HOCON format (https://github.com/lightbend/config/blob/main/HOCON.md). 

The provided start script assumes the configuration file to be located in the `/etc` directory. To do so, copy the
provided configuration file to that location using:

```shell
sudo cp /opt/uni-meter/config/uni-meter.conf /etc/uni-meter.conf
```

Then use your favorite editor to adjust the configuration file to your needs as described in the following sections.

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

### First test

After you have adjusted the configuration file, you can start the tool using command

```shell
sudo /opt/uni-meter/bin/uni-meter.sh
```

If everything is set up correctly, the tool should start up and you should see an output like

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
tool using the systemd service, you can check the log messages in `var/log/uni-meter.log`.

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





