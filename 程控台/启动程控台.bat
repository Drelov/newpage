@echo off
cd /d "%~dp0"
where pythonw >nul 2>&1
if %errorlevel%==0 (
  start "" pythonw.exe "%~dp0main.py"
  exit /b 0
)
where pyw >nul 2>&1
if %errorlevel%==0 (
  start "" pyw.exe -3 "%~dp0main.py"
  exit /b 0
)
where python >nul 2>&1
if %errorlevel%==0 (
  start "" python.exe "%~dp0main.py"
  exit /b 0
)
echo Python 3 was not found. Install Python from https://www.python.org/downloads/
echo and enable "Add python.exe to PATH".
pause
