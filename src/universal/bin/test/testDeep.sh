#!/bin/bash

# Launches functionality testing for RADIUS

_THIS_FILE_DIRNAME="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
BIN_DIR=..

rm $_THIS_FILE_DIRNAME/log/*
rm $_THIS_FILE_DIRNAME/cdr/*
rm -rf /var/yaas/cdr/session && mkdir -p /var/yaas/cdr/session
rm -rf /var/yaas/cdr/session-hot && mkdir -p /var/yaas/cdr/session-hot
rm -rf /var/yaas/cdr/service && mkdir -p /var/yaas/cdr/service

# echo launching superserver...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-superserver > log/nohup-superserver 2>&1
sleep 5

# echo launching server...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-server > log/nohup-server 2>&1
sleep 5

echo launching client...
nohup gnome-terminal -- $BIN_DIR/aaaserver -Dinstance=test-client-deep $* > log/nohup-client 2>&1

