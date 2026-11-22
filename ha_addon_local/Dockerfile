ARG BUILD_FROM
FROM ${BUILD_FROM:-ghcr.io/hassio-addons/base:21.0.4}

RUN apk add --no-cache openjdk17-jre-headless

# uni-meter-*.tgz is the package built from this repository (mvn package), copied next to this file
COPY uni-meter-*.tgz /tmp/
RUN tar xzf /tmp/uni-meter-*.tgz -C /opt && \
    rm /tmp/uni-meter-*.tgz && \
    ln -s /opt/uni-meter-* /opt/uni-meter

COPY run.sh /
RUN chmod a+x /run.sh

CMD [ "/run.sh" ]
