# Using a Fronius smart meter as input source

A Fronius smart meter can be accessed using the generic-http input source. To access the Fronius smart meter, use the
following configuration in the `/etc/uni-meter.conf` file:

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
