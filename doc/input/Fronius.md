# Using a Fronius smart meter as the input source

A Fronius smart meter can be accessed using the generic-http input source. To access the Fronius smart meter, use the
following configuration in the `uni-meter.conf` file:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://192.168.x.x/solar_api/v1/GetMeterRealtimeData.cgi?Scope=System"

      power-phase-mode = "tri-phase"

      channels = [{
        type = "json"
        channel = "power-l1"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_1"
      },{
        type = "json"
        channel = "power-l2"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_2"
      },{
        type = "json"
        channel = "power-l3"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_3"
      }]
    }
  }
}
```

## Using Modbus TCP (SunSpec)

Alternatively, the smart meter can be read via Modbus TCP. This works directly with a Fronius Smart Meter IP, which
does not need the Solar API of the inverter, and with a smart meter that is connected to a Fronius inverter which
has Modbus TCP enabled.

The input device walks the SunSpec model chain starting at the `base-register-address` and uses the first meter
model it finds. Both the "float" (models 211 - 214) and the "int+SF" (models 201 - 204) SunSpec model types are
supported.

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.fronius-smart-meter"

  input-devices {
    fronius-smart-meter {
      address = "192.168.x.x"
      port = 502
      unit-id = 1
    }
  }
}
```

Notes:

* Modbus TCP must be enabled in the web interface of the Smart Meter IP or of the inverter respectively.
* When reading the meter through a Fronius inverter, the primary meter is usually available at `unit-id = 200`.
* The SunSpec data usually starts at register `40000`. If your device uses a different start address (e.g. `0`
  or `50000`), set the `base-register-address` parameter accordingly.
* Positive power values are interpreted as grid consumption, negative values as grid feed-in. If your readings are
  the other way round, set `invert-power = true`. This also swaps the imported and exported energy counters.
