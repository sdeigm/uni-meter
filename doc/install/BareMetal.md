# Installation on a physical server

``uni-meter`` is written in Java and therefore can be run on any operating system that provides at least a Java 17
runtime environment. This can be either a Windows, Mac or Linux machine.  

## Download

The release versions can be downloaded from the [GitHub releases](https://github.com/sdeigm/uni-meter/releases).
It is only necessary to download the `uni-meter-<version>.tgz` archive, the source code archives are not needed.
Since the installation target system is most likely a Raspberry Pi, the following sections describe the installation
on a Raspberry Pi or a similar Unix system. The installation on a Windows or Mac system may slightly vary and is not
covered here.

## Installation

The `uni-meter` is dependent on an installed Java 17 runtime. To check if this is the case, type

```shell
java -version
```

If the output shows a version 17 or later, you are good to go. If not, you can install the OpenJDK 17 using the following
commands:

```shell    
sudo apt update
sudo apt install openjdk-17-jre
```

Afterward, you can extract the downloaded `uni-meter-<version>.tgz` archive. In theory, you can extract the archive
to any location you like, but all the scripts and configuration files included assume an installation in the `/opt`
directory. So preferably you should extract it to the `/opt` directory using the following commands:

```shell
sudo tar xzvf uni-meter-<version>.tgz -C /opt
```

This creates a `/opt/uni-meter-<version>` folder on your system. It is a good practice to create a symbolic link 
`/opt/uni-meter` pointing to the current version. That allows an easy switch between different versions. The symbolic
link can be created using

```shell
sudo ln -s /opt/uni-meter-<version> /opt/uni-meter
```

## Configuration

The configuration of the `uni-meter` is done using a configuration file in the [HOCON format](https://github.com/lightbend/config/blob/main/HOCON.md). 

The provided start script assumes the configuration file to be located under `/etc/uni-meter.conf`. As a starting 
point, copy the provided sample configuration file to that location:

```shell
sudo cp /opt/uni-meter/config/uni-meter.conf /etc/uni-meter.conf
```

Then use your favorite editor to adjust the configuration file to your needs as described in the configuration sections.

## First test

After you have adjusted the configuration file, you can start the tool using command

```shell
sudo /opt/uni-meter/bin/uni-meter.sh
```

If everything is set up correctly, the tool should start up, and you should see an output like

```shell
24-12-04 07:29:08.006 INFO  uni-meter                - ##################################################################
24-12-04 07:29:08.030 INFO  uni-meter                - # Universal electric meter converter 1.1.5 (2025-04-10 09:16:00) #
24-12-04 07:29:08.031 INFO  uni-meter                - ##################################################################
24-12-04 07:29:08.033 INFO  uni-meter                - initializing actor system
24-12-04 07:29:10.902 INFO  org.apache.pekko.event.slf4j.Slf4jLogger - Slf4jLogger started
24-12-04 07:29:11.707 INFO  uni-meter.controller     - creating Shelly3EM output device
24-12-04 07:29:11.758 INFO  uni-meter.controller     - creating VZLogger input device
24-12-04 07:29:16.254 INFO  uni-meter.http.port-80   - HTTP server is listening on /[0:0:0:0:0:0:0:0]:80
```

To check if the configuration works and if its up, you can check
http://<uni-meter:port>/status with e.g. `curl "http://<uni-meter>:<port>/status" | jq
".emeters"` which returns some JSON output like:

```json
[
  {
    "power": 257.0,
    "pf": 0.88,
    "current": 1.2,
    "voltage": 231.26,
    "is_valid": true,
    "total": 2282.33,
    "total_returned": 4020.62
  },
  {
    "power": 674.0,
    "pf": 0.99,
    "current": 3.0,
    "voltage": 228.33,
    "is_valid": true,
    "total": 3738.87,
    "total_returned": 2908.64
  },
  {
    "power": 657.0,
    "pf": 0.97,
    "current": 2.9,
    "voltage": 229.51,
    "is_valid": true,
    "total": 4038.92,
    "total_returned": 1380.2
  }
]
```

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

## Announcing the tool via mDNS

The Hoymiles MS-A2 storage uses mDNS to discover the virtual shelly. So the following step is only necessary if mDNS 
support is needed. That is not necessary for the Marstek storage. 

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

Starting with `uni-meter` version 1.1.5, a running avahi daemon is automatically detected and all necessary configuration
files will be automatically created in `/etc/avahi/service`.

If the host has multiple network interfaces, you can force which IPv4 address is announced via mDNS.  
`ip-address` has the highest priority. If it is empty and `ip-interface` is set, `uni-meter` resolves the IPv4
address of that interface. If both are empty (default), the output device address is used.

```hocon
uni-meter {
  mdns {
    type = "auto"
    ip-address = ""
    ip-interface = ""
  }
}
```


