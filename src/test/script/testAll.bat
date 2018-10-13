@echo off

SET _THIS_FILE_DIRNAME=%~dp0
SET BIN_DIR=..\..\..\target\universal\stage\bin

call start "SUPERSERVER" %BIN_DIR%\aaaserver test-superserver
call start "SERVER" %BIN_DIR%\aaaserver test-server
call start "CLIENT" %BIN_DIR%\aaaserver test-client
