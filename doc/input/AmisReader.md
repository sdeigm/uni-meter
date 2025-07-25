# Using an AMIS Reader as the input source

An AMIS reader can be accessed using the generic-http input source. To access the reader, use the
following `uni-meter.conf` file:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"
  
  
  output-devices {
    shelly-pro3em {
      udp-port = 1010
    }
  }

  input-devices {
    generic-http {
      url = "http://<amis-ip>/rest"
	  
      power-phase-mode = "tri-phase"
      energy-phase-mode = "tri-phase"
	  
      channels = [{
        type = "json"
        channel = "power-l1"
        json-path = "$.saldo"
      },{
        type = "json"
        channel = "energy-consumption-l1"
        json-path = "$['1.8.0']"
      },{
        type = "json"
        channel = "energy-production-l1"
        json-path = "$['2.8.0']"
      }]
    }
  }  
}
```
