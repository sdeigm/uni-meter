# Using the Powerfox poweropti as input device

To use a Powerfox poweropti smart meter as an input source, set up the `uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.poweropti"

  input-devices {
    poweropti {
      url = "http://poweropti-ip"
      api-key = "poweropti-api-key"
    }
  }
}
```

Replace the `poweropti-ip` and `poweropti-api-key` placeholders with the actual values from your environment.  

