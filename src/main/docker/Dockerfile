FROM openjdk:8u212-jdk-slim-stretch
MAINTAINER francisco.cardosogil@gmail.com
ARG version=0.0
COPY target/universal/aaaserver-${version} /aaaserver-${version}
RUN ln -s /aaaserver-${version} /aaaserver
RUN groupadd -r -g 1000 yaas && useradd -rM -g yaas -u 1000 yaas && chown -R yaas:yaas /aaaserver-${version} && chown -h yaas:yaas /aaaserver
WORKDIR /aaaserver/bin
ENTRYPOINT ["/bin/bash"]
