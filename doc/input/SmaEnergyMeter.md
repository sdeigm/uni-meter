# Using SMA energy meter as the input source

To use a SMA energy meter or a Sunny Home Manager as an input source, set up the `/etc/uni-meter.conf` file as follows

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.sma-energy-meter"

  input-devices {
    sma-energy-meter {
      port = 9522   
      group = "239.12.255.254"
      //susy-id = 270  
      //serial-number = 1234567
      network-interfaces =[
        "eth0"
        "wlan0"
        // "192.168.178.222"
      ]
    }
  }
}
```

The above configuration shows the default values which are used if nothing is provided. If your `port` and `group` are
different, you have to adjust the values accordingly.

If no `susy-id` and `serial-number` are provided, the first detected device will be used. Otherwise, provide the values
of the device you want to use.

The network interfaces to use are provided as a list of strings. Either specify the names or the IP addresses of the
interfaces you want to use.

