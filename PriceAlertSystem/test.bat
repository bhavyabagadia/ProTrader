@echo off
REM Runs the automatic self-tests (works offline).
cd /d "%~dp0"
call build.bat
java -cp out com.pricealert.SelfTest
