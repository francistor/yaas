@echo off
REM --------------------------------------------------------------
REM Access-Request TEST for Yaas project
REM --------------------------------------------------------------

SET AAABASEDIR=C:\AAA
SET _THIS_FILE_DIRNAME=%~dp0

REM Usually this need not be changed
SET BINDIR=%AAABASEDIR%\bin
SET RUNDIR=%AAABASEDIR%\run
SET RADIUS=%BINDIR%\aaa-rt
SET DIAMETER=%BINDIR%\aaa-dt

REM Test parameters
SET REQUESTFILE=%_THIS_FILE_DIRNAME%\AccountingRequest.txt

SET COUNT=5

REM Delete Garbage
del /Q _THIS_FILE_DIRNAME\out\*.* 2>nul

REM Access-Request -------------------------------------------------------------
@echo.
@echo Access-Request
@echo.

echo User-Name = "thisIsTheUser@name" > %REQUESTFILE%
echo NAS-IP-Address = "4.3.2.1" >> %REQUESTFILE%

REM Send the packet
%RADIUS% -debug verbose -count %COUNT% -retryLimit 0 -remoteAddress 127.0.0.1:1812 -code Accounting-Request -request "@%REQUESTFILE%"


