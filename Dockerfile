FROM alpine:3 AS builder
WORKDIR /src
RUN apk add --no-cache openjdk17-jdk maven
COPY . /src
RUN mvn install

# ---

FROM alpine:3
# Install Java 17 & avahi & disable d-bus
RUN apk add --no-cache openjdk17-jre-headless dbus avahi avahi-tools bash \
    && sed -i 's/.*enable-dbus=.*/enable-dbus=yes/' /etc/avahi/avahi-daemon.conf \
    && mkdir -p /var/run/dbus \
    && chown messagebus:messagebus /var/run/dbus  \
    && chmod 755 /var/run/dbus    
  
# Install uni-meter
RUN --mount=type=bind,target=/helper,source=/src,from=builder \
    mkdir -p /opt/uni-meter && \
    tar --strip-components 1 -xzf /helper/target/uni-meter-*.tgz -C /opt/uni-meter && \
    cp /opt/uni-meter/config/uni-meter.conf /etc

ENTRYPOINT ["/opt/uni-meter/bin/uni-meter-and-daemons.sh"]
