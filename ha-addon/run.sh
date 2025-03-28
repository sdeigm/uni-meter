#!/usr/bin/with-contenv bashio

if bashio::config.has_value 'custom_config' && [ -f "/config/$(bashio::config 'custom_config')" ]; then
    bashio::log.info "using custom configuration file: $(bashio::config 'custom_config')"
    cp "/config/$(bashio::config 'custom_config')" "/etc/uni-meter.conf"
fi    

/opt/uni-meter/bin/uni-meter.sh
