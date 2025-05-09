# Configure the EcoTracker output device

To use the Eco-Tracker output device, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.eco-tracker"
  
  # ...
  output-devices {
    eco-tracker {
      # ...
    }
  }
}
```

Use your browser or the curl utility and open the URL

``http://<uni-meter-ip>/v1/json``

to check if the virtual EcoTracker is providing the electrical meter readings.

## Changing the HTTP server port

In its default configuration, the emulated EcoTracker listens on port 80 for incoming HTTP requests. That port can
be changed to for instance port 4711 by adding the following parts to your `/etc/uni-meter.conf` file:

```hocon
uni-meter {
  # ...
  output-devices {
    eco-tracker {
      # ...
      port = 4711
    }
  }
}
```

Please be aware, that the `uni-meter` itself also provides some HTTP functionality on a port which can be configured
separately. 

## Changing the average interval

The EcoTracker provides two power readings in its JSON output: the current power readings and as a standard the average 
of the last 60 seconds. That average interval can be configured using the following option:

```hocon
uni-meter {
  # ...
  output-devices {
    eco-tracker {
      # ...
      average-interval = 120s
    }
  }
}
```
