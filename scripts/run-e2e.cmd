@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-e2e.ps1" %*

