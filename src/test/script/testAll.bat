@echo off

SET YAAS_TEST_REQUESTS=10000

SET _THIS_FILE_DIRNAME=%~dp0
SET BIN_DIR=..\..\..\target\universal\stage\bin

del %_THIS_FILE_DIRNAME%\log\*.* /Q
del %_THIS_FILE_DIRNAME%\cdr\*.* /Q

call start "SUPERSERVER" %BIN_DIR%\aaaserver -Dinstance=test-superserver
timeout /T 20 /nobreak 1>nul

call start "SERVER" %BIN_DIR%\aaaserver -Dinstance=test-server
timeout /T 20 /nobreak 1>nul

call start "CLIENT" %BIN_DIR%\aaaserver -Dinstance=test-client

