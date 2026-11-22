# Using the HomeWizard Wi-Fi P1 meter as input device

The HomeWizard P1 meter is read via its local HTTP API (`/api/v1/data`). Enable the **Local API** in the
HomeWizard Energy app (Settings > Meters > your P1 meter > Local API) first.

Example: forward the HomeWizard readings via Speedwire to an SMA inverter (e.g. Sunny Boy):

```hocon
uni-meter {
  output = "uni-meter.output-devices.sma-em"

  input = "uni-meter.input-devices.homewizard-p1"

  output-devices {
    sma-em {
      serial-number = 1900123456
    }
  }

  input-devices {
    homewizard-p1 {
      url = "http://homewizard-ip"
      polling-interval = 1s
    }
  }
}
```

Replace `homewizard-ip` with the IP address of your P1 meter.

## Phase handling

By default (`power-phase-mode = "tri-phase"`) the per-phase values (L1/L2/L3) are used if the smart meter
provides them; otherwise the total power is used. For a single-phase meter you can map the total power onto one
phase:

```hocon
homewizard-p1 {
  power-phase-mode = "mono-phase"
  power-phase = "l1"
  energy-phase-mode = "mono-phase"
  energy-phase = "l1"
}
```

Positive values mean power drawn from the grid, negative values mean power fed into the grid
(HomeWizard convention, matching uni-meter).
