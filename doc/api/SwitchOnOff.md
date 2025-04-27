# Rest API to switch on/off the `uni-meter`

## Switch off

Using this API call, you can switch off the `uni-meter` for a certain time. When switched off, the connected storages
do not receive any electrical meter readings and should fall back to their configured default behavior. That can, for
instance, be used to avoid any interferences with the storage while loading your BEV. The command takes an optional 
parameter for the duration of the switch off which defaults to an unlimited duration. After the duration expires, the
`uni-meter` is automatically switched on again:

`http://<uni-meter-ip>:<uni-meter-port>/api/switch_off?seconds=3600`

## Switch on

If the `uni-meter` is switched off for a certain time period or unlimited, you can switch it on again using the 
following REST call:

`http://<uni-meter-ip>:<uni-meter-port>/api/switch_on`



