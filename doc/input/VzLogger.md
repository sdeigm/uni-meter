# Using a VzLogger webserver as the input source

To use the VzLogger webserver as an input device, there are two options. First, there is a VzLogger specific input device
that directly takes the VzLogger channel UUIDs in its configuration. This was uni-meter's first input device and is still
working, but has the limitation that it only supports mono phase data. With the availability of the GenericHttp input
device, this might be the better choice for many users, especially if they want to use tri-phase data. The following
sections describe both methods to access VzLogger data

## Using the classic VzLogger input device

To use the ols VzLogger specific input source set up the `uni-meter.conf` file as follows and replace the
`<vzlogger-host>` and `<vzlogger-port>` placeholders with the actual host and port of your VzLogger webserver.
Additionally, provide the channel UUIDs of your system.

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

      # The Marstek storage needs input data on a single phase. This can be controlled by
      # the configuration options below
      power-phase-mode = "mono-phase"
      power-phase = "l1"
    }
  }
}
```

You will find that information in the VzLogger configuration file. As a
default, the VzLogger is configured in the `/etc/vzlogger.conf` file. Make sure that the VzLogger provides its
readings as a Web service and extracts the necessary information from that file:

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

## Using the GenericHttp input device for VzLogger

To use the GenericHttp input device to access VzLogger data in tri-phase mode, a `/etc/uni-meter.conf` could look like
the following one. Please adjust the JSON path index values behind `$.data[1]...` according to the configuration of your
VzLogger:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://xxx.xxx.xxx.xxx:yyyy" # put your vzlogger web IP and port here
      #username = "username"
      #password = "password"

      power-phase-mode = "tri-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$.data[4].tuples[0][1]" # adjust the index behind data according to your VzLogger setup
        scale = 1
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$.data[5].tuples[0][1]"
        scale = 1
      },{
        type = "json"
        channel = "power-l1"
        json-path = "$.data[1].tuples[0][1]"
      },{
        type = "json"
        channel = "power-l2"
        json-path = "$.data[2].tuples[0][1]"
      },{
        type = "json"
        channel = "power-l3"
        json-path = "$.data[3].tuples[0][1]"
      }]
    }
  }
}
```
