@echo off

SET _THIS_FILE_DIRNAME=%~dp0
SET BIN_DIR=..\..\..\target\universal\stage\bin

del %_THIS_FILE_DIRNAME%\log\*.* /Q
del %_THIS_FILE_DIRNAME%\cdr\*.* /Q

call start "DB" %BIN_DIR%\aaaserver -Dconfig.file=/code/yaasws/yaas/src/k8/conf/db/aaa-default.conf -Dlogback.configurationFile=/code/yaasws/yaas/src/k8/conf/db/logback-default.xml
timeout /T 20 /nobreak 1>nul

call start "SUPERSERVER" %BIN_DIR%\aaaserver -Dconfig.file=/code/yaasws/yaas/src/k8/conf/superserver/aaa-default.conf -Dlogback.configurationFile=/code/yaasws/yaas/src/k8/conf/superserver/logback-default.xml
timeout /T 20 /nobreak 1>nul

call start "SERVER" %BIN_DIR%\aaaserver -Dconfig.file=/code/yaasws/yaas/src/k8/conf/server/aaa-default.conf -Dlogback.configurationFile=/code/yaasws/yaas/src/k8/conf/server/logback-default.xml
