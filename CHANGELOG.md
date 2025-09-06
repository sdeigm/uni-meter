# Change Log
All notable changes to the `uni-meter` will be documented in this file.

## [1.1.16] - 2025-09-06

### Fixed

- [#237](https://github.com/sdeigm/uni-meter/issues/237) Double values in RPC responses shall always use 2 decimal digits (fixes a problem with the Marstek B2500 storage)

## [1.1.15] - 2025-08-07

### Fixed

- [#220](https://github.com/sdeigm/uni-meter/issues/220) Growatt NOAH needs disabled cloud settings to work in local mode

### Added

- [#212](https://github.com/sdeigm/uni-meter/issues/212) Sungrow Smart Meter support

## [1.1.14] - 2025-07-24

### Fixed

- [#208](https://github.com/sdeigm/uni-meter/issues/208) Incorrect response to RPC request Shelly.GetStatus
- [#190](https://github.com/sdeigm/uni-meter/issues/190) Avahi daemon in docker container is no longer restricted to certain interfaces

### Added

- [#210](https://github.com/sdeigm/uni-meter/issues/210) Additional debug log for raw tibber data
- [#199](https://github.com/sdeigm/uni-meter/pull/199) New input device Kostal Smart Energy Meter
- [#198](https://github.com/sdeigm/uni-meter/issues/198) Output device independent status endpoint 
- [#194](https://github.com/sdeigm/uni-meter/issues/194) Voltage & Frequency .. etc. as Input Variables 

## [1.1.13] - 2025-07-09

### Fixed

- [#187](https://github.com/sdeigm/uni-meter/issues/187) Fixes a problem with Home Assistant sensors in `unknown` state

### Added

- [#188](https://github.com/sdeigm/uni-meter/issues/188) Enhanced mDNS registration to support the Growatt Noah 2000 storage
  
## [1.1.12] - 2025-07-08

### Fixed

- [#184](https://github.com/sdeigm/uni-meter/issues/184) Fixes a problem with SMA serial numbers which do not fit into a 32-bit integer

### Added

- [#178](https://github.com/sdeigm/uni-meter/issues/178) Container image now supports environment variables `UNI_CONFIG` and `UNI_LOGGING`
to specify custom locations for the `uni-meter.conf` and `logback.xml` configuration files.

## [1.1.11] - 2025-05-29

### Fixed

- [#158](https://github.com/sdeigm/uni-meter/issues/158) Fixes a problem that leads to 30-second timeouts in the tibber-pulse input device

### Added

- [#153](https://github.com/sdeigm/uni-meter/issues/153) REST API methods to allow the temporary disabling of charging/discharging

## [1.1.10] - 2025-05-19

### Fixed

- [#143](https://github.com/sdeigm/uni-meter/issues/143) Fixes the problem that a trailing `/` in the `url` configuration of an HTTP input device
leads to problems with the HTTP request
- [#145](https://github.com/sdeigm/uni-meter/issues/145) Fixes the problem that the HTTP response status code was not evaluated
- [#151](https://github.com/sdeigm/uni-meter/issues/151) Fixes the problem that SolarEdge input values had the wrong sign

## [1.1.9] - 2025-05-01

### Fixed

- [#135](https://github.com/sdeigm/uni-meter/issues/135) Previous version(s) broke the Marstek compatibility

## [1.1.8] - 2025-04-27

### Fixed

- [#131](https://github.com/sdeigm/uni-meter/issues/131) REST API was always bound to port 80, regardless of the configured port
- [#133](https://github.com/sdeigm/uni-meter/issues/133) Power factor of Shelly Pro3EM is always one

