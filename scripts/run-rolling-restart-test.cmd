@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-rolling-restart-test.ps1" %*
