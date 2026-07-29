@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-billing-event-consistency-test.ps1" %*
