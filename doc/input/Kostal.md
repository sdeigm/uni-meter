# Using a Kostal Smart Energy Meter as the input source

To use a Kostal smart energy meter as an input source, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.ksem"
  
  input-devices {
    ksem {
      address = "192.168.x.y"
      port = 502
      unit-id = 1
    }
  }  
}
```
