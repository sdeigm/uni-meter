# Using a Sungrow Smart Energy Meter input source

To use a Sungrow smart energy meter as an input source, set up the `uni-meter.conf` file as
below. Adjust the IP address, port, and unit ID according to your setup:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.sungrow"
  
  input-devices {
    sungrow {
      address = "192.168.178.125"
      port = 502
      unit-id = 1
    }
  }  
}
```

