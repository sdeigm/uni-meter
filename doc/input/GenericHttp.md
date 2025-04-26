# Using the generic HTTP input source

The generic HTTP input source can be used to gather the electrical meter data from any device providing the data via
an HTTP get request as JSON value. You have to configure the complete URL where the data is gathered from, including
the entire path and query parameters.

The input source can operate in two modes. Either in a `mono-phase` mode, where the power and/or the energy data is
provided as a single value for all three phases, or in a `tri-phase` mode, where the power and/or the energy data is
provided as a separate value for each phase.

The input data is gathered through channels. The following channels exist for the different energy and power phase modes:
* Power `mono-phase`
    * `power-total` - total current power
* Power `tri-phase`
    * `power-l1` - current power phase 1
    * `power-l2` - current power phase 2
    * `power-l3` - current power phase 3
* Energy `mono-phase`
    * `energy-consumption-total` - total energy consumption
    * `energy-production-total` - total energy production
* Energy `tri-phase`
    * `energy-consumption-l1` - energy consumption phase 1
    * `energy-consumption-l2` - energy consumption phase 2
    * `energy-consumption-l3` - energy consumption phase 3
    * `energy-production-l1` - energy production phase 1
    * `energy-production-l2` - energy production phase 2
    * `energy-production-l3` - energy production phase 3

For each channel to be read, you have to configure where the data is gathered from and what type it is. Currently only
the `json` type is supported, but in the future, other types might be added.

For channels in JSON format, an additional JSON path has to be provided which specifies which part of the JSON data
contains the actual value.

Additionally, each channel has a `scale` property which can be used to scale the data. The default scale is 1.0 and can
be omitted.

So a `/etc/uni-meter.conf` file reading the data from a VzLogger webserver could look like this:

```hocon    
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://vzlogger-server:8088"
      #username = "username"
      #password = "password"

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$.data[0].tuples[0][1]"
        scale = 0.001
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$.data[1].tuples[0][1]"
        scale = 0.001
      },{
        type = "json"
        channel = "power-total"
        json-path = "$.data[2].tuples[0][1]"
      }]
    }
  }
}
```
