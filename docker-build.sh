#!/bin/bash

############################################
# Build docker image and pull to Docker Hub
############################################

# Better at the beginning
echo "DockerHub password"
sudo docker login --username=francistor

# Build project
git pull
sbt clean universal:packageBin

# Get version
zipfile=$(ls target/universal/*.zip)
if [[ $zipfile =~ (.+)/aaaserver-(.+)\.zip ]]; then
	directory="${BASH_REMATCH[1]}"
	version="${BASH_REMATCH[2]}"
else
	echo "ERROR"
	exit 1
fi

# Unzip contents
unzip -o -d ${directory} ${zipfile}

# Generate docker image
sudo docker build --file src/main/docker/Dockerfile --build-arg version=$version --tag yaas:$version .

# Publish to docker hub
sudo docker tag yaas:$version francistor/yaas:$version
sudo docker push francistor/yaas:$version


