@echo off

SET _THIS_FILE_DIRNAME=%~dp0
SET BIN_DIR=..\..\..\target\universal\stage\bin

call start "SUPERSERVER" %BIN_DIR%\aaaserver test-superserver
timeout /T 10 /nobreak 1>nul

call start "SERVER" %BIN_DIR%\aaaserver test-server
timeout /T 10 /nobreak 1>nul

call start "CLIENT" %BIN_DIR%\aaaserver test-client
