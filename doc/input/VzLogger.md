# Using VzLogger webserver as input source

To use the VzLogger webserver as an input source set up the `/etc/uni-meter.conf` file as follows and replace the
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

