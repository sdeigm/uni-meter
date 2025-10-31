# Rest API to dynamically adjust runtime parameters of the `uni-meter`

## Set parameters

Using this API call, you can dynamically adjust one or more runtime parameters of the `uni-meter`:

`http://<uni-meter-ip>:<uni-meter-port>/api/set_parameters?min-sample-period=2500ms&power-offset-total=20`

Currently, the following runtime parameters can be adjusted:

* min-sample-period
* power-offset-total
* power-offset-l1
* power-offset-l2
* power-offset-l3
