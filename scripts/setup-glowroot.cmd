@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-glowroot.ps1" %*
