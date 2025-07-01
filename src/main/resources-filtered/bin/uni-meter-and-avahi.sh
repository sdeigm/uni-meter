#!/bin/bash

/usr/sbin/avahi-daemon -D

/usr/bin/java \
 -Dlogback.configurationFile=${UNI_LOGGING:-/opt/uni-meter/config/logback.xml} \
 -Dconfig.file=${UNI_CONFIG:-/etc/uni-meter.conf} \
 -jar /opt/uni-meter/lib/uni-meter-${project.version}.jar