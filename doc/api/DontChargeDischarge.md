# Rest API to prevent charging/discharging of the storage

This API is experimental. Since the `uni-meter` does not have any information about the current charging state of the
storage, this feature relies on the storage's default behavior when no electrical meter readings are provided. So if
charging shall be prevented, the `uni-meter` does not provide any electrical meter readings to the storage if electrical
energy is feed into the grid. In the case of discharging prevention, it is the opposite. In both cases the storage
should fall back to its default behavior, which should result in an inactive storage.

To stop these operation modes again, you can use the `switch_on` API call as described [here](SwitchOnOff.md).

## Prevent charging

Using this API call, you can prevent the storage from charging for a certain time. This might, for instance, be used to 
prevent charging before 11am to use your storage in a more "grid-friendly" way.

`http://<uni-meter-ip>:<uni-meter-port>/api/no_charge?seconds=3600`

## Prevent discharging

Using this API call, you can prevent the storage from discharging for a certain time. This might, for instance, be used
to prevent discharging while charging your BEV. The command takes an optional parameter for the duration of the

`http://<uni-meter-ip>:<uni-meter-port>/api/no_discharge?seconds=3600`



