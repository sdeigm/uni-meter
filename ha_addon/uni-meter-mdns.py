import socket
from homeassistant.components import zeroconf
from zeroconf.asyncio import AsyncServiceInfo

@service
def uni_meter_mdns_register(type, name, ip, port, properties):
    aiozc = zeroconf.async_get_async_instance(hass)
    info = AsyncServiceInfo(
        type_=type + "._tcp.local.",
        name=name + "." + type + "._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties=properties
    )
    aiozc.async_register_service(info)

@service
def uni_meter_mdns_unregister(type, name, ip, port, properties):
    aiozc = zeroconf.async_get_async_instance(hass)
    info = AsyncServiceInfo(
        type_=type + "._tcp.local.",
        name=name + "." + type + "._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties=properties
    )
    aiozc.async_unregister_service(info)

@service
def uni_meter_unregister_all():
    aiozc = zeroconf.async_get_async_instance(hass)
    aiozc.async_unregister_all_services()
