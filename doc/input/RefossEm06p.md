# Using a Refoss EM06P as the input source

To use a Refoss EM06P as an input source, set up the `uni-meter.conf` file as follows:

```hocon
uni-meter {
  output = "uni-meter.output-devices.<output-device>"

  input = "uni-meter.input-devices.refoss-em06p"

  input-devices {
    refoss-em06p {
      url = "http://<refoss-em06p-ip>"
      power-phase-mode = "tri-phase"
      channel-id-l1 = 1
      channel-id-l2 = 2
      channel-id-l3 = 3
    }
  }
}
```

Replace the `<refoss-em06p-ip>` placeholder with the actual IP of your Refoss EM06P device.

## Phase Modes and Channel Mapping

The Refoss EM06P device supports 6 channels. You can use this input source in two modes depending on your setup:

* **3-Phase Meter (`tri-phase`)**: Set `power-phase-mode = "tri-phase"`. The plugin will read the channels specified by `channel-id-l1`, `channel-id-l2`, and `channel-id-l3`, reporting them as the 3 phases of a 3-phase meter.
* **1-Phase Meter (`mono-phase`)**: Set `power-phase-mode = "mono-phase"`. The plugin will read only the channel specified by `channel-id`, reporting as a single-phase meter. You can additionally specify `power-phase = "l1"` (or `l2`, `l3`) to define which output phase the data corresponds to.
