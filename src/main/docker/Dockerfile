FROM openjdk:8u212-jdk-slim-stretch
MAINTAINER francisco.cardosogil@gmail.com
ARG version=0.0
RUN groupadd -r -g 1000 yaas && useradd -rM -g yaas -u 1000 yaas 
USER yaas
COPY --chown=yaas:yaas target/universal/aaaserver-${version} /aaaserver-${version}
RUN ln -s /aaaserver-${version} /aaaserver
WORKDIR /aaaserver/bin
ENTRYPOINT ["/bin/bash"]
