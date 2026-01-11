# Running the docker image

## Using docker compose

To easily run the `uni-meter` as docker image, a docker compose setup is available in a separate  
[uni-meter-docker](https://github.com/sdeigm/uni-meter-docker) repository. To use that repository, clone it to your
local machine using

```shell
git clone https://github.com/sdeigm/uni-meter-docker.git
```

Within the cloned directory you will find a `docker-compose.yaml`, a `uni-meter.conf` and a
`logback.xml` file. These files can be adjusted to your needs. Afterward you bring up the system by just executing

```shell
docker compose up
```

## Using pure docker

To run this project in a docker container, you can see the example below. This
exposes UDP Port `1010` and also exposes the httpd daemon on `8080` which
can be changed to your needs.

```sh
docker run -d \
    -p 1010:1010/udp -p 8080:80 --name uni-meter \
    --restart=unless-stopped \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v $PWD/uni-meter.conf:/etc/uni-meter.conf \
    sdeigm/uni-meter
```

## Device id

The automatically generated device of the uni-meter is based on the MAC address of the docker container. In certain
docker environments the MAC address changes on every restart. In that case some storages lose the connection to the 
device and have to be re-paired manually. To avoid this, you can specify a device id in the `uni-meter.conf` file as
described here for the [Shelly Pro 3EM](../output/ShellyPro3EM.md#configuring-the-shelly-device-id) and for the 
[EcoTracker](../output/EcoTracker.md#configuring-the-mac-address-and-hostname)




