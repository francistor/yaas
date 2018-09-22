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
SET APPLICATION_ID=3GPP-Gx
SET DESTINATION_HOST=server.yaasserver
SET DESTINATION_REALM=yaasserver
SET ORIGIN_REALM=yaasserver
SET DESTINATION_ADDRESS=127.0.0.1:3868

REM Test parameters
SET REQUESTFILE=%_THIS_FILE_DIRNAME%\GxRequest.txt

SET COUNT=1

REM Delete Garbage
del /Q _THIS_FILE_DIRNAME\out\*.* 2>nul

REM Diameter Gx CCR -------------------------------------------------------------
@echo.
@echo Credit Control request
@echo.

echo Session-Id = "session-id-1" > %REQUESTFILE%
echo CC-Request-Type = 1 >> %REQUESTFILE%
echo CC-Request-Number = 1 >> %REQUESTFILE%
echo Subscription-Id = "Subscription-Id-Type=1, Subscription-Id-Data=913374871" >> %REQUESTFILE%
echo 3GPP-Bearer-Identifier = FF >> %REQUESTFILE%
echo Framed-IP-Address = 1.2.3.4 >> %REQUESTFILE%
echo Framed-IPv6-Prefix = 2001:bebe:cafe::0/64 >> %REQUESTFILE%
echo 3GPP-QoS-Information = "3GPP-QoS-Class-Identifier = CLASS0, 3GPP-Max-Requested-Bandwidth-UL = 1000, 3GPP-Max-Requested-Bandwidth-DL = 500" >> %REQUESTFILE%


REM Send the packet
%DIAMETER% -debug verbose -count %COUNT% -oh %ORIGIN_HOST% -or %ORIGIN_REALM% -dh %DESTINATION_HOST% -dr %DESTINATION_REALM% -destinationAddress %DESTINATION_ADDRESS% -Application %APPLICATION_ID% -command Credit-Control -request "@%REQUESTFILE%"

