# Using SHRDZM smartmeter interface as the input source

To use a SHRDZM smartmeter interface providing the smart meter readings via UDP, set up the `uni-meter.conf` file
as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.shrdzm"

  input-devices {
    shrdzm {
      port = 9522
      interface = "0.0.0.0"

      # The Marstek storage needs input data on a single phase. This can be controlled by
      # the configuration options below
      power-phase-mode = "mono-phase"
      power-phase = "l1"
    }
  }
}
```

The above configuration shows the default values for the ShrDzm device which are used, if nothing is provided. If you
want to use a different port or interface, you have to adjust the values accordingly.

