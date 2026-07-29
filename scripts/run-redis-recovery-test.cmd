@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-redis-recovery-test.ps1" %*
