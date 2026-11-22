#!/usr/bin/with-contenv bashio

CONFIG="/config/$(bashio::config 'custom_config')"
if [ ! -f "$CONFIG" ]; then
    bashio::log.error "configuration file $CONFIG does not exist"
    exit 1
fi
bashio::log.info "using configuration file $CONFIG"

LOGBACK=/opt/uni-meter/config/logback.xml
if [ -f "/config/logback.xml" ]; then
    LOGBACK=/config/logback.xml
fi

exec java -Dconfig.file="$CONFIG" -Dlogback.configurationFile="$LOGBACK" \
     -jar /opt/uni-meter/lib/uni-meter-*.jar
