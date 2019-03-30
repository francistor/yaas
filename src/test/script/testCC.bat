@echo off
REM --------------------------------------------------------------
REM Gx TEST for Yaas project
REM --------------------------------------------------------------

SET AAABASEDIR=C:\AAA
SET _THIS_FILE_DIRNAME=%~dp0

REM Usually this need not be changed
SET BINDIR=%AAABASEDIR%\bin
SET RUNDIR=%AAABASEDIR%\run
SET RADIUS=%BINDIR%\aaa-rt
SET DIAMETER=%BINDIR%\aaa-dt

SET ORIGIN_HOST=client.yaasclient
SET APPLICATION_ID=Credit-Control
SET COMMAND=Credit-Control
SET DESTINATION_HOST=server.yaasserver
SET DESTINATION_REALM=yaasserver
SET ORIGIN_REALM=yaasserver
SET DESTINATION_ADDRESS=127.0.0.1:3868

REM Test parameters
SET REQUESTFILE=%_THIS_FILE_DIRNAME%\CCRequest.txt

SET COUNT=1

REM Delete Garbage
del /Q _THIS_FILE_DIRNAME\out\*.* 2>nul

REM Diameter CCR -------------------------------------------------------------
@echo.
@echo Credit Control request
@echo.

echo Session-Id = "session-id-1" > %REQUESTFILE%
echo Auth-Application-Id = 4 >> %REQUESTFILE%
echo CC-Request-Type = 1 >> %REQUESTFILE%
echo CC-Request-Number = 1 >> %REQUESTFILE%
echo Subscription-Id = "Subscription-Id-Type=1, Subscription-Id-Data=913374871" >> %REQUESTFILE%


REM Send the packet
%DIAMETER% -debug verbose -count %COUNT% -oh %ORIGIN_HOST% -or %ORIGIN_REALM% -dh %DESTINATION_HOST% -dr %DESTINATION_REALM% -destinationAddress %DESTINATION_ADDRESS% -Application %APPLICATION_ID% -command %COMMAND% -request "@%REQUESTFILE%"

