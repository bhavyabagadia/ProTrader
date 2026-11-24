
@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

echo =================================
echo       PROTRADER BUILD
echo =================================

if exist out rmdir /s /q out
mkdir out

if exist sources.txt del sources.txt

for /r src %%F in (*.java) do (
    set "FILE=%%F"
    set "FILE=!FILE:\=/!"
    echo "!FILE!" >> sources.txt
)

echo.
echo Compiling Java files...

javac -encoding UTF-8 -d out @sources.txt

if errorlevel 1 goto build_failed

del sources.txt

echo.
echo =================================
echo       BUILD SUCCESSFUL
echo =================================
exit /b 0

:build_failed
del sources.txt
echo.
echo =================================
echo       BUILD FAILED
echo =================================
exit /b 1
