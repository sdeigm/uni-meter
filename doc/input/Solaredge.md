# Using a Solaredge electrical meter input source

To use a Solaredge electrical meter as an input source, set up the `uni-meter.conf` file as
follows:

* The address is the IP of your solaredge inverter, which is connected to the
  Modbus counter
* To retrieve the data, the Modbus TCP interface must be activated on the
  inverter. To do this, you can either use the SetApp or connect directly to
  the inverter's WiFi from another PC / notebook / tablet and open the
  configuration page.
* Default port is 1502

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.solaredge"
  
  input-devices {   
    solaredge {
      address = "192.168.178.125"
      port = 1502
      unit-id = 1
    }
  }  
}
```

