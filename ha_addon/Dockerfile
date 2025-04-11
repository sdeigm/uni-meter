ARG BUILD_FROM
ARG UNI_METER_VERSION="1.1.5"
FROM ${BUILD_FROM:-ghcr.io/hassio-addons/base:17.0.1}

# Build stage
ARG UNI_METER_VERSION

# Install Java 17 runtime
RUN apk update
RUN apk add \
     openjdk17-jre-headless 

# Download and install uni-meter
RUN wget https://github.com/sdeigm/uni-meter/releases/download/${UNI_METER_VERSION}/uni-meter-${UNI_METER_VERSION}.tgz -O /tmp/uni-meter-${UNI_METER_VERSION}.tgz
RUN tar xzf /tmp/uni-meter-${UNI_METER_VERSION}.tgz -C /opt
RUN ln -s /opt/uni-meter-${UNI_METER_VERSION} /opt/uni-meter
RUN cp /opt/uni-meter/config/uni-meter.conf /etc/uni-meter.conf

# Copy data for add-on
COPY run.sh /
RUN chmod a+x /run.sh

CMD [ "/run.sh" ]