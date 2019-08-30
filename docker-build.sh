#!/bin/bash

############################################
# Build docker image and pull to Docker Hub
# In order to make this work behind a proxy, edit or create
# /etc/systemd/system/docker.service.d/http-proxy.conf
#
# [Service]
# Environment="HTTPS_PROXY=http://<user>:<password>@<proxy>:<port>"
# Environment="HTTP_PROXY=http://<user>:<password>@<proxy>:<port>"
############################################

# Better at the beginning
echo "DockerHub password"
sudo -E docker login --username=francistor

# Build project
git pull
sbt clean stage universal:packageBin

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


