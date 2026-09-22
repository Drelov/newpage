@echo off
cd /d "%~dp0"
if not exist "%~dp0Install-Local.ps1" (
  echo Install-Local.ps1 is missing. Extract the whole zip first.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Local.ps1"
if errorlevel 1 pause
