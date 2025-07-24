# Using a Shelly Pro 3EM as the input source

To use a Shelly Pro 3EM as an input source, set up the `uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.shelly-pro3em"

  input-devices {
    shelly-pro3em {
      url = "<shelly-pro3em-url>"
    }
  }
}
```

Replace the `<shelly-pro3em-url>` placeholder with the actual URL of your Shelly Pro 3EM device.

