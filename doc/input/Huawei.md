# Using a Huawei inverter with DTSU666-H input source

To use a Huawei inverter with a DTSU666-H as input source, set up the `uni-meter.conf` file as
below. Adjust the IP address, port, and unit ID according to your setup:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.huawei-inverter"
  
  input-devices {
    huawei-inverter {
      address = "192.168.178.125"
      port = 502
      unit-id = 1
    }
  }  
}
```

