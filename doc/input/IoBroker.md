# Using ioBroker as the input source

Reading ioBroker datapoints as the input source can be done using the generic http interface on the uni-meter side and using
the [simpleAPI](https://github.com/ioBroker/ioBroker.simple-api) adapter on the ioBroker side. When the simpleAPI adapter
is installed and configured on the ioBroker, you can use the following configuration in the `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      # Adjust the IP address of the ioBroker and the datapoints to read according to your needs
      url = "http://192.168.x.x:8082/getBulk/smartmeter.0.1-0:1_8_0__255.value,smartmeter.0.1-0:2_8_0__255.value,smartmeter.0.1-0:16_7_0__255.value/?json"

      # sample ioBroker output: [
      #  {"id":"smartmeter.0.1-0:1_8_0__255.value","val":16464.7379,"ts":1740054549023,"ack":true},
      #  {"id":"smartmeter.0.1-0:2_8_0__255.value","val":16808.0592,"ts":1740054549029,"ack":true},
      #  {"id":"smartmeter.0.1-0:16_7_0__255.value","val":4.9,"ts":1740054549072,"ack":true}
      # ]

      power-phase-mode = "mono-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$[0].val"
        scale = 0.001
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$[1].val"
        scale = 0.001
      },{
        type = "json"
        channel = "power-total"
        json-path = "$[2].val"
      }]
    }
  }
}
```

