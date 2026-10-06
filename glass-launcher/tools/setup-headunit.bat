@echo off
rem Double-click to set up Glass Launcher on the head unit over USB (ADB).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-headunit.ps1" %*
pause
