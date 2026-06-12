# Configure the Shelly Pro EM output device

The Shelly Pro EM output device emulates the single phase Shelly Pro EM-50. In contrast to the three phase
Shelly Pro 3EM, this device provides two single phase meters (`EM1` components). Channel `0` reports the summed up
power and energy data of all input phases, channel `1` is reported as unused.

This output device can be used for consumers which only support single phase Shelly devices or which misinterpret
the three phase data of the Shelly Pro 3EM.

To use the Shelly Pro EM output device, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-proem"
  
  # ...
  output-devices {
    shelly-proem {
      # ...
    }
  }
}
```

Use your browser or the curl utility and open the URL

``http://<uni-meter-ip>/rpc/EM1.GetStatus?id=0``

to check if the virtual shelly is providing the electrical meter readings.

## Enabling JSON RPC over UDP

As a default, the JSON RPC over UDP interface of the Shelly Pro EM emulator is disabled. To enable it, configure the
`udp-port` and optionally the `udp-interface` in the `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-proem {
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

In some setups with higher latency until the real electrical meter readings are available on the output side, it might
be necessary to throttle the sampling frequency of the output data. Otherwise, it might be possible that the storage
oversteers the power production and consumption values and that they are fluctuating too much around 0 (see the comments
and findings to this [issue](https://github.com/sdeigm/uni-meter/issues/12)).

To throttle the sampling frequency, you can configure a `min-sample-period` in the `/etc/uni-meter.conf` file. This
configuration value specifies the minimum time until the next output data is delivered to the storage.

```hocon
uni-meter {
  #...
  output-devices {
    shelly-proem {
      #...
      min-sample-period = 5000ms
      #...
    }
  }
  #...
}
```

## Changing the HTTP server port

In its default configuration, the emulated Shelly Pro EM listens on port 80 for incoming HTTP requests. That port can 
be changed to for instance port 4711 by adding the following parts to your `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-proem {
      # ...
      port = 4711
    }
  }
}
```

Please be aware, that the `uni-meter` itself also provides some HTTP functionality on a port which can be configured
separately. 

> [!WARNING]
> Some consumers have the target port hardcoded to `80` and cannot be configured to use a custom port. If you change
> the port, those consumers might no longer be able to retrieve data.

## Configuring the Shelly device id

Normally it is not necessary to configure the Shelly device id. It will be automatically set based on the first
detected hardware mac address on the host machine.

If it may, for whatever reason, be necessary to modify the device id, it can be done using the following configuration
parameters:

```hocon
uni-meter {
  # ...
  output-devices {
    shelly-proem {
      device {
        mac = "B827EB364242"
        hostname = "shellyproem50-b827eb364242"
      }
    }
  }
  #...
}
```
