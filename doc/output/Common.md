# Common output device configuration options

## Configuring the forget interval

If the physical input device is not reachable and no power values are available for a certain time, the uni-meter will
not provide any output values to the storage anymore, so that the storage triggers its fallback behavior.

Without any configuration that happens after one minute. If you need a different timeout, you can configure it using 
the following configuration below:

```hocon
uni-meter {
  #...
  output-devices {
    #...
    shelly-pro3em {
      # These are the defaults used without any configuration: 
      forget-interval = 1m
    }
  }
}
```

## Configuring a static power offset

In some setups, it might be necessary to add a static offset to the power values. This can be the case if the real
electrical meter readings are not 100% accurate to your household's electrical meter readings.

You can either configure a power offset for the single phases or a total power offset. The phase power offsets take
precedence over the total power offset. If at least one phase power offset is configured, the total power offset is
ignored.

Setting the power offset is done in the `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  #...
  output-devices {
    #...
    shelly-pro3em {
      #...
      power-offset-total =0
      
      power-offset-l1 = 0
      power-offset-l2 = 0
      power-offset-l3 = 0
    }
  }
}
```

## Configuring a default power scaling

If you have two batteries which are not coupled in any manner, a method to have them both acting shared is to let them
see only the half of the power difference that realy exists.

```hocon
uni-meter {
  #...
  output-devices {
    #...
    shelly-pro3em {
      #...
      default-client-power-factor = 0.5
    }
  }
}
```

## Configuring a default voltage and/or frequeny

TODO: Describe use case here

```hocon
uni-meter {
  #...
  output-devices {
    #...
    shelly-pro3em {
      #...
      default-voltage = 230.0
      default-frequency = 5.0
    }
  }
}
```
