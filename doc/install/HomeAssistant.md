# Installing the Home Assistant Add-on

## Adding the uni-meter repository

Add this GitHub repository to Home Assistant by pressing the button below

[![Open your Home Assistant instance and show the add add-on repository dialog with a specific repository URL pre-filled.](https://my.home-assistant.io/badges/supervisor_add_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_add_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2Fsdeigm%2Funi-meter)

## Installing 

After adding the repository, click on the "ADD-ON STORE" button in the bottom-right area of your Home Assistant browser 
window. This will bring up a list of all available add-ons that now includes the `uni-meter`. Please click on
`uni-meter` and then install.

## Configuration

The `uni-meter` is configured using a `uni-meter.conf` file which should be placed in the ``/addon_configs/663b81ce_uni_meter`` 
directory of your Home Assistant instance. To access that directory, it might be necessary to install additional add-ons
which provide access to your Home Assistant instance via Samba or Sftp.

For troubleshooting problems, it might be necessary to adjust the logging of the `uni-meter`. Therefore, it is possible
to additionally put an optional `logback.xml` into that directory that can be used to adjust the logging settings. If
existent, the file is automatically evaluated.

## mDNS support

If your storage - like the Hoymiles MS-A2 - is discovering the virtual Shelly using mDNS, additional steps are necessary.
This step is not needed for storages like the Marstek Venus.

To use mDNS it is necessary to additionally install the Pyscript add-on. Afterward, copy the provided 
[uni-meter-mdns.py](https://github.com/sdeigm/uni-meter/blob/main/ha_addon/uni-meter-mdns.py) into the `/config/pyscript` 
directory of your instance.

If that is done, the `uni-meter` automatically detects the provided services and announces itself using mDNS.  

## Starting

When all configuration steps have been done, you can start the `uni-meter` add-on. To check if it is working correctly,
you can use your web-browser and open the URL

```
http://<home-assistant-ip>/rpc/EM.GetStatus?id=0
```

That should bring up the current electrical meter readings in your browser window.