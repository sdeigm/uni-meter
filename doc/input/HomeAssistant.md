# Using Home Assistant sensors as input source

Home Assistant sensors can be used as input source. To access the sensors, you have to create an access token in
Home Assistant and configure the uni-meter with that ``access-token`` and the URL of your system.

Also, with this input, the input source can operate in two modes.
Either in a `mono-phase` mode, where the power and/or the energy data is provided as a single value for all three
phases, or in a `tri-phase` mode, where the power and/or the energy data is provided as a separate value for each phase.

Depending on the chosen phase mode, the sensors to be used can be configured by the following properties:

* Power `mono-phase`
    * `power-sensor` - sensor providing the total current power
* Power `tri-phase`
    * `power-l1-sensor` - sensor providing current power phase 1
    * `power-l2-sensor` - sensor providing current power phase 2
    * `power-l3-sensor` - sensor providing current power phase 3
* Energy `mono-phase`
    * `energy-consumption-sensor` - sensor providing total energy consumption
    * `energy-production-sensor` - sensor providing total energy production
* Energy `tri-phase`
    * `energy-consumption-l1-sensor` - sensor providing energy consumption phase 1
    * `energy-consumption-l2-sensor` - sensor providing energy consumption phase 2
    * `energy-consumption-l3-sensor` - sensor providing energy consumption phase 3
    * `energy-production-l1-sensor` - sensor providing energy production phase 1
    * `energy-production-l2-sensor` - sensor providing energy production phase 2
    * `energy-production-l3-sensor` - sensor providing energy production phase 3

If you have a setup where the power values are split up between power production and power consumption, you can
additionally specify the sensors for the production.

* Power `mono-phase`
    * `power-production-sensor` - sensor providing the current production power
* Power `tri-phase`
    * `power-production-l1-sensor` - sensor providing current production power phase 1
    * `power-production-l2-sensor` - sensor providing current production power phase 2
    * `power-production-l3-sensor` - sensor providing current production power phase 3

The current power values are then calculated as

``current power = power-sensor - power-production-sensor``

or

``current power Lx = power-Lx-sensor - power-production-lx-sensor``

So the simplest `/etc/uni-meter.conf` file reading the data from Home Assistant sensors could look like this:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.home-assistant"

  input-devices {
    home-assistant {
      url = "http://192.168.178.51:8123"
      access-token = "eyJhbGciOiJIUzI1Ni...."

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      power-sensor = "sensor.current_power"
      energy-consumption-sensor = "sensor.energy_imported"
      energy-production-sensor = "sensor.energy_exported"
    }
  }
}
```
