# Using Tibber Pulse as the input source

The Tibber Pulse local API can be used as an input source. To use this API, the local HTTP server has to be enabled on
the Pulse bridge. How this can be done is described for instance here
[marq24/ha-tibber-pulse-local](https://github.com/marq24/ha-tibber-pulse-local).

If this API is enabled on your Tibber bridge, you should set up the `uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.tibber-pulse"

  input-devices {
    tibber-pulse {
      url = "<tibber-device-url>"
      node-id = 1
      user-id = "admin"
      password = "<tibber-device-password>"

      # The Marstek storage needs input data on a single phase. This can be controlled by
      # the configuration options below
      power-phase-mode = "mono-phase"
      power-phase = "l1"
    }
  }
}
```

Replace the `<tibber-device-url>` and `<tibber-device-password>` placeholders with the actual values from your environment.  
The `node-id` and `user-id` are optional and can be omitted if the default values from above are correct. Otherwise,
adjust the values accordingly.

