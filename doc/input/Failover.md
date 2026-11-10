# Using a failover input device

The failover input device combines two other input devices: a **primary** and a **secondary** one. As long as the
primary input device is healthy, its readings are forwarded to the output device. If the primary input device becomes
unhealthy, the readings of the secondary input device are forwarded instead.

The primary input device is considered unhealthy if

* it did not provide any power readings within the configured `timeout` (e.g. device offline, network problem), or
* its power readings did not change at all within the configured `frozen-timeout` (e.g. a hanging device that always
  reports the same value). Set `frozen-timeout = 0s` to disable this check.

As soon as the primary input device delivers valid readings again for the configured `switch-back-delay`, the
uni-meter switches back to it. All switches are logged.

Both input devices are configured as usual in the `input-devices` section. The failover input device references
them by their configuration path. Nested failover input devices are not supported.

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.failover"

  input-devices {
    # Primary: ESPHome based IR reader, read via the ESPHome web_server REST API
    generic-http {
      url = "http://192.168.178.50/sensor/total_power"
      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"
      channels = [{
        type = "json"
        channel = "power-total"
        json-path = "$.value"
      }]
    }

    # Secondary: Solaredge meter
    solaredge {
      address = "192.168.178.88"
      port = 502
      unit-id = 1
    }

    failover {
      primary = "uni-meter.input-devices.generic-http"
      secondary = "uni-meter.input-devices.solaredge"

      # These are the defaults used without any configuration:
      timeout = 10s
      frozen-timeout = 60s
      switch-back-delay = 30s
      check-interval = 1s
      energy-source = "active"
    }
  }
}
```

## Energy readings

The energy counters of the two input devices usually differ. With `energy-source` you can choose which energy readings
are forwarded:

* `active` - the energy readings of the currently active input device (default)
* `primary` - always the energy readings of the primary input device
* `secondary` - always the energy readings of the secondary input device

## Using two input devices of the same type

If both input devices are of the same type, you can derive an additional configuration block from the default one using
a HOCON substitution:

```hocon
uni-meter {
  input-devices {
    generic-http-2 = ${uni-meter.input-devices.generic-http} {
      url = "http://192.168.178.51/sensor/total_power"
    }

    failover {
      primary = "uni-meter.input-devices.generic-http"
      secondary = "uni-meter.input-devices.generic-http-2"
    }
  }
}
```
