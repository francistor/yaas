#!/bin/bash

export YAAS_TEST_REQUESTS=10000
export YAAS_TEST_LOOP=false

_THIS_FILE_DIRNAME="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
BIN_DIR=..

rm $_THIS_FILE_DIRNAME/log/*
rm $_THIS_FILE_DIRNAME/cdr/*
rm -rf /var/yaas/cdr/session && mkdir -p /var/yaas/cdr/session
rm -rf /var/yaas/cdr/session-hot && mkdir -p /var/yaas/cdr/session-hot
rm -rf /var/yaas/cdr/service && mkdir -p /var/yaas/cdr/service

echo Preparing database
# mongo --quiet $_THIS_FILE_DIRNAME/fillMongoDB.js

echo launching superserver...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-superserver > log/nohup-superserver 2>&1
sleep 5

echo launching superserver mirror...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-superserver-mirror > log/nohup-superserver-mirror 2>&1
sleep 10

echo launching server...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-server > log/nohup-server 2>&1
sleep 10

echo launching client...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-client > log/nohup-client 2>&1

