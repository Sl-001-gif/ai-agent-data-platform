@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0offpeak_run.ps1" >> "%~dp0_offpeak_cmd.log" 2>&1
exit /b %ERRORLEVEL%
