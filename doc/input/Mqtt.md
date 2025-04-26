# Using MQTT as input source

The MQTT input source can operate in two modes. Either in a `mono-phase` mode, where the power and/or the energy data is
provided as a single value for all three phases, or in a `tri-phase` mode, where the power and/or the energy data is
provided as a separate value for each phase.

The input data is gathered through channels. Each channel has a unique identifier and a topic where the data is gathered
from. The following channels exist for the different energy and power phase modes:
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

Each channel is linked to a topic where the data is gathered from and has a type which specifies how the data is
stored within the MQTT topic. Currently, two types are supported: `value` and `json`. Use the `value` type for data
stored as a number string within the topic. Use the `json` type for data stored as JSON within the topic. For
channels in JSON format, an additional JSON path has to be provided which specifies which part of the JSON data
contains the actual value.

Additionally, each channel has a `scale` property which can be used to scale the data. The default scale is 1.0 and can
be omitted.

So a `/etc/uni-meter.conf` file for a MQTT input source could look like this:

```hocon    
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.mqtt"

  input-devices {
    mqtt {
      url = "tcp://127.0.0.1:1883"
      #username = "username"
      #password = "password"

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "power-total"
        json-path = "$..power"
        scale = 1.0 # default, can be omitted
      },{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "energy-consumption-total"
        json-path = "$..counter_pos"
      },{
        type = "json"
        topic = "tele/smlreader/SENSOR"
        channel = "energy-production-total"
        json-path = "$..counter_neg"
      }]
    }
  }
}
```
