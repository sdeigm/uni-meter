FROM debian:bookworm-slim AS builder
WORKDIR /src
RUN apt-get update && apt-get install -y openjdk-17-jdk-headless maven
COPY . /src
RUN mvn install

# ---

FROM debian:bookworm-slim
# Install Java 17 & avahi & disable d-bus
RUN apt-get update && \
    apt-get install -y openjdk-17-jre-headless && \
    apt-get install -y avahi-daemon && \
    sed -i 's/.*enable-dbus=.*/enable-dbus=no/' /etc/avahi/avahi-daemon.conf && \
    sed -i 's/#allow-interfaces=eth0/allow-interfaces=eth0/' /etc/avahi/avahi-daemon.conf && \
    apt-get -qq clean && \
    rm -rf /var/lib/apt/lists/* /tmp/*

# Install uni-meter
RUN --mount=type=bind,target=/helper,source=/src,from=builder \
    mkdir -p /opt/uni-meter && \
    tar --strip-components 1 -xzf /helper/target/uni-meter-*.tgz -C /opt/uni-meter && \
    cp /opt/uni-meter/config/uni-meter.conf /etc

ENTRYPOINT ["/opt/uni-meter/bin/uni-meter-and-avahi.sh"]
