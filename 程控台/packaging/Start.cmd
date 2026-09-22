@echo off
cd /d "%~dp0"
if not exist "%~dp0pythonw.exe" (
  echo pythonw.exe is missing. Extract the whole zip first.
  pause
  exit /b 1
)
start "" "%~dp0pythonw.exe" "%~dp0main.py"
