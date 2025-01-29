FROM --platform=linux/arm64 debian:bookworm-slim

ARG version="1.0.4-SNAPSHOT"

ENV VERSION=$version

run apt-get update 
run apt install -y openjdk-17-jre

ADD target/uni-meter-${VERSION}.tgz /opt
RUN ln -s /opt/uni-meter-${VERSION} /opt/uni-meter
RUN cp /opt/uni-meter/config/uni-meter.conf /etc

ENTRYPOINT ["/opt/uni-meter/bin/uni-meter.sh"]
