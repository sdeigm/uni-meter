# Configure the EcoTracker output device

To use the Eco-Tracker output device, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.eco-tracker"
  
  # ...
  output-devices {
    eco-tracker {
      # ...
    }
  }
}
```
