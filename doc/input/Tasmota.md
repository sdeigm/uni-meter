# Using a Tasmota IR read head as the input source

To use a Tasmota IR read head as an input source, set up the `/etc/uni-meter.conf` file as follows:

```hocon
uni-meter {
  uni-meter {
    output = "uni-meter.output-devices.shelly-pro3em"

    input = "uni-meter.input-devices.tasmota"

    input-devices {
      tasmota {
        url = "http://<tasmota-ir-read-head-ip>"
        # username=""
        # password=""
        power-json-path = "$..curr_w"
        power-scale = 1.0 # default, can be omitted
        energy-consumption-json-path = "$..total_kwh"
        energy-consumption-scale = 1.0 # default, can be omitted
        energy-production-json-path = "$..export_total_kwh"
        energy-production-scale = 1.0 # default, can be omitted
      }
    }
  }
}
```

Replace the `<tasmota-ir-read-head-ip>` placeholder with the actual IP address of your Tasmota IR read head device.
If you have set a username and password for the device, you have to provide them as well.

Additionally, you have to configure the JSON paths for the power, energy consumption and energy production values to
access the actual values within the JSON data. If you have to scale these values, you can provide a scale factor which
is 1.0 as a default.

To retrieve the needed data for the JSON paths, check
`http://<tasmota-ir-read-head-ip>/cm?cmnd=Status%2010` which should give you
something like:

```json
{
  "StatusSNS": {
    "Time": "2025-04-23T09:28:35",
    "DWS7410": {
      "energy": 7418.4061,
      "en_out": 9032.9393,
      "power": -1962.05,
      "meter_id": "XXXXXX"
    }
  }
}
```

matching config paths from that example would be:
```hocon
  power-json-path = "$.StatusSNS.DWS7410.power"
  energy-consumption-json-path = "$.StatusSNS.DWS7410.energy"
  energy-production-json-path = "$.StatusSNS.DWS7410.en_out"
```

