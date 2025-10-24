# Configure the Shelly Pro 3EM output device

To use the Shelly Pro 3EM output device, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  # ...
  output-devices {
    shelly-pro3em {
      # ...
    }
  }
}
```

Use your browser or the curl utility and open the URL

``http://<uni-meter-ip>/rpc/EM.GetStatus?id=0``

to check if the virtual shelly is providing the electrical meter readings.

## Enabling JSON RPC over UDP (necessary for the Marstek storage)

As a default, the JSON RPC over UDP interface of the Shelly Pro3EM emulator is disabled. To enable it, configure the
`udp-port` and optionally the `udp-interface` in the `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-pro3em {
      #...
      udp-port = 1010
      udp-interface = "0.0.0.0" # default, can be omitted
      #...
    }
  }
  #...
}
```  

## Throttling the sampling frequency of the Shelly device

In some setups with a higher latency until the real electrical meter readings are available on the output side, it might
be necessary to throttle the sampling frequency of the output data. Otherwise, it might be possible that the storage
oversteers the power production and consumption values and that they are fluctuating too much around 0 (see the comments
and findings to this [issue](https://github.com/sdeigm/uni-meter/issues/12)).

To throttle the sampling frequency you can configure a `min-sample-period` in the `/etc/uni-meter.conf` file. This
configuration value specifies the minimum time until the next output data is delivered to the storage.

```hocon
uni-meter {
  #...
  output-devices {
    shelly-pro3em {
      #...
      min-sample-period = 5000ms
      #...
    }
  }
  #...
}
```

## Changing the HTTP server port

In its default configuration, the emulated Shelly Pro 3EM listens on port 80 for incoming HTTP requests. That port can 
be changed to for instance port 4711 by adding the following parts to your `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-pro3em {
      # ...
      port = 4711
    }
  }
}
```

Please be aware, that the `uni-meter` itself also provides some HTTP functionality on a port which can be configured
separately. 

## Configuring the Shelly device id

Starting from version 1.1.5 on, it is normally not necessary anymore to configure the Shelly device id. It will be 
automatically set based on the first detected hardware mac address on the host machine.

If it may, for whatever reason, be necessary to modify the device id, it can be done using the following configuration
parameters:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-pro3em {
      device {
        mac = "B827EB364242"
        hostname = "shellypro3em-b827eb364242"
      }
    }
  }
  #...
}
```

## Configuring client specific behaviour

Unimeter can pretend to be different devices for each connecting client. For each client, specified via
address, the MAC adress and the power factor can be configured.
By this, the power from the input device can be unequal distributed over multiple batteries.

```hocon
uni-meter {
  #...
  output-devices {
    shelly-pro3em {
      #...
      client-contexts = [{
        # See correct data from local machine
        address = "127.0.0.1"
        mac = "abcdef0123456"
        power-factor = 1.0      
      },{
        # Small batterie
        address = "192.168.178.30"
        mac = "bcdef01234567"
        power-factor = 0.3     
      },{
        # Big batterie
        address = "192.168.178.70"
        mac = "cdef012345678"
        power-factor = 0.7     
      }]
    }
  }
}
```
