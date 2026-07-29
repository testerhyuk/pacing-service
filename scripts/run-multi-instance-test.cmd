@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-multi-instance-test.ps1" %*
