# Using a Shelly 3EM as the input source

To use a Shelly 3EM as an input source, set up the `uni-meter.conf` file as follows

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

