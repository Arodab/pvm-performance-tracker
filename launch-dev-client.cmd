@echo off
title RuneLite Dev Client - PvM Performance Tracker
set "JAVA_HOME=C:\Users\T-GAMER\.jdks\jdk-11.0.31+11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
echo Building and launching a RuneLite dev client with PvM Performance Tracker loaded...
echo Fight any NPC and open the overlay / side panel to see live damage, accuracy and DPS.
echo Close the RuneLite window to return here.
echo.
call gradlew.bat run
echo.
echo Client closed (exit code %errorlevel%).
pause
