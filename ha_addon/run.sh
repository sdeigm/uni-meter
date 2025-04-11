#!/usr/bin/with-contenv bashio

if bashio::config.has_value 'custom_config' && [ -f "/config/$(bashio::config 'custom_config')" ]; then
    bashio::log.info "using custom configuration file: $(bashio::config 'custom_config')"
    cp "/config/$(bashio::config 'custom_config')" "/etc/uni-meter.conf"
else 
    echo "custom configuration file $(bashio::config 'custom_config') does not exist"
    exit 1
fi  

if [ -f "/config/logback.xml" ]; then
  bashio::log.info "using logging configuration file logback.xml"
  cp "/config/logback.xml" "/opt/uni-meter/config/logback.xml"
fi

export UNI_HA_URL="http://supervisor/core"
export UNI_HA_ACCESS_TOKEN=$SUPERVISOR_TOKEN

/opt/uni-meter/bin/uni-meter.sh
