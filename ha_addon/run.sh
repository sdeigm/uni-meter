#!/usr/bin/with-contenv bashio

if bashio::config.has_value 'custom_config' && [ -f "/config/$(bashio::config 'custom_config')" ]; then
    bashio::log.info "using custom configuration file: $(bashio::config 'custom_config')"
    cp "/config/$(bashio::config 'custom_config')" "/etc/uni-meter.conf"
else 
    echo "custom configuration file $(bashio::config 'custom_config') does not exist"
    exit 1
fi    

/opt/uni-meter/bin/uni-meter.sh
