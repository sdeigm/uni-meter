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
    shelly-pro3em {
      #...
      min-sample-period = 5000ms
      #...
    }
  }
  #...
}
```

## Delivering output data only on input updates

If the input device provides new readings only every few seconds, throttling with a fixed `min-sample-period` does not
fit well. The throttling period never exactly matches the reading interval of the input device, so the storage sometimes
gets a reading that is already several seconds old, and sometimes a reading is not delivered at all. If the input device
delivers its readings at irregular intervals, there is no suitable value at all: a short `min-sample-period` delivers
the same reading several times while the input device is slow, a long one skips readings while it is fast.

For such setups, you can set the `sample-mode` to `on-input-update` in the `/etc/uni-meter.conf` file. In this mode,
the requests of the storage are answered as soon as the input device has delivered a new reading, and each reading is
delivered to the storage only once. If the input device stops delivering readings, the requests are not answered
anymore and the storage falls back to its default behavior. A configured `min-sample-period` is still respected as the
minimum time between two answers. Please be aware, that for input devices which poll the physical meter, every poll
counts as a new reading, so the `polling-interval` of the input device should not be shorter than the update interval
of the meter. For the Home Assistant input, set its `notify-on-update-only` option instead (see the
[Home Assistant input](../input/HomeAssistant.md)), so that only real sensor updates are forwarded.

Some input devices deliver the values of the three phases one after another in separate messages. To avoid that the
storage gets an answer after each of these messages, the answer is delayed by the `linger-period`, which defaults to
100 milliseconds. Normally there is no need to change this value, as long as it covers the time between the first and
the last of these messages.

```hocon
uni-meter {
  #...
  output-devices {
    shelly-pro3em {
      #...
      sample-mode = "on-input-update"
      linger-period = 100ms
      #...
    }
  }
  #...
}
```

Like the `min-sample-period`, the `sample-mode` only affects the JSON RPC over UDP and the websocket interface. Plain
HTTP requests to `/rpc/EM.GetStatus` are always answered immediately.

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

> [!WARNING]
> Some consumers have the target port hardcoded to `80` and cannot be configured to use a custom port. Known
> examples are the Growatt Noah/Nexa 2000 and Hoymiles storages. If you change the port, those consumers might no longer 
> be able to retrieve data.

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
