#!/bin/bash

# Build docker image and pull to Docker Hub
# sbt clean universal:packageBin

zipfile=$(ls target/universal/*.zip)
if [[ $zipfile =~ (.+)/aaaserver-(.+)\.zip ]]; then
	directory="${BASH_REMATCH[1]}"
	version="${BASH_REMATCH[2]}"
else
	echo "ERROR"
	exit 1
fi

unzip -o -d ${directory} ${zipfile}
sudo docker build --file src/main/docker/Dockerfile --build-arg version=$version --tag yaas:$version .


