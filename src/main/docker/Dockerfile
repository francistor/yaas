FROM openjdk:11.0.3-jdk-slim-stretch
MAINTAINER francisco.cardosogil@gmail.com
ARG version=0.0
COPY target/universal/aaaserver-${version} /aaaserver-${version}
WORKDIR /aaaserver-${version}/bin
ENTRYPOINT ["/bin/bash"]
