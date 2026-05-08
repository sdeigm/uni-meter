# Using an IOmeter smart meter as the input source

An IOmeter smart meter can be accessed using the generic-http input source. To access the IOmeter, use the following configuration in the uni-meter.conf file:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      # Adjust the IP address of the IOmeter
      url = "http://192.168.x.x/v1/reading"

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$.meter.reading.registers[?(@.obis=='01-00:01.08.00*ff')].value"
        scale = 1
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$.meter.reading.registers[?(@.obis=='01-00:02.08.00*ff')].value"
        scale = 1
      },{
        type = "json"
        channel = "power-total"
        json-path = "$.meter.reading.registers[?(@.obis=='01-00:10.07.00*ff')].value"
        scale = 1
      }]
    }
  }
}
```

## Important note about power measurement (10.07 vs 24.07)

Some German smart meters do not provide the current power value under OBIS code 01-00:10.07.00*ff.

Instead, the current power is reported under:

```hocon
01-00:24.07.00*ff
```

In such cases, the configured 10.07 channel will return no value, resulting in an incorrect or zero power reading in uni-meter.

## Required configuration change

If your IOmeter device uses OBIS 24.07 instead of 10.07, the configuration must be adjusted accordingly:

```hocon
channel = "power-total"
json-path = "$.meter.reading.registers[?(@.obis=='01-00:24.07.00*ff')].value"
```