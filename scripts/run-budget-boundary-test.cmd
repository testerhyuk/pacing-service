@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-budget-boundary-test.ps1" %*
