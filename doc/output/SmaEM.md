# Configure the SMA Energy Meter output device

To use the SMA Energy Meter output device, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.sma-em"

  # ...
  output-devices {
    sma-em {
      # ...
    }
  }
}
```

The device sends Speedwire SMA energy meter telegrams via UDP multicast.

## Basic configuration

```hocon
uni-meter {
  # ...
  output-devices {
    sma-em {
      group = "239.12.255.254"
      port = 9522
      ttl = 32
      send-interval = 1s
      serial-number = 12345678
      susy-id = 270
    }
  }
}
```

## Notes

- `serial-number` is stored as a 32-bit value in the SMA telegram. If you configure a larger number, it will be
  truncated to the lower 32 bits and a warning will be logged.
- The output uses the OBIS channel set compatible with SMA energy meter decoders. Reactive values and
  reactive/apparent energy counters are currently sent as `0`. Frequency is sent as `50 Hz`.
