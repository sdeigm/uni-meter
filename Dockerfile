FROM debian:bookworm-slim

ARG version="1.0.4-SNAPSHOT"

ENV VERSION=$version

RUN apt-get update 

#
# Install Java 17
#
RUN apt install -y openjdk-17-jre

#
# Install avahi
#
RUN apt-get install -y avahi-daemon

# disable d-bus
RUN sed -i 's/.*enable-dbus=.*/enable-dbus=no/' /etc/avahi/avahi-daemon.conf
RUN sed -i 's/#allow-interfaces=eth0/allow-interfaces=eth0/' /etc/avahi/avahi-daemon.conf

#
# Install uni-meter
#
ADD target/uni-meter-${VERSION}.tgz /opt
RUN ln -s /opt/uni-meter-${VERSION} /opt/uni-meter
RUN cp /opt/uni-meter/config/uni-meter.conf /etc

ENTRYPOINT ["/opt/uni-meter/bin/uni-meter-and-avahi.sh"]
