FROM openjdk:8u212-jdk-slim-stretch
MAINTAINER francisco.cardosogil@gmail.com

# Get version from environment, defaults to 0.0
ARG version=0.0
COPY target/universal/aaaserver-${version} /aaaserver-${version}
RUN ln -s /aaaserver-${version} /aaaserver

# Create user and group yaas:yaas 1000
RUN groupadd -r -g 1000 yaas && useradd -rM -g yaas -u 1000 yaas && chown -R yaas:yaas /aaaserver-${version} && chown -h yaas:yaas /aaaserver

# Create directory for CDR
RUN mkdir /var/yaas && chown -R yaas:yaas /var/yaas

WORKDIR /aaaserver/bin
ENTRYPOINT ["/bin/bash"]
