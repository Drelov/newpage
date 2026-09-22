@echo off
cd /d "%~dp0"
if exist "%~dp0程控台.exe" (
  start "" "%~dp0程控台.exe"
  exit /b 0
)
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
echo Chengkongtai.exe was not found, and Python 3 is not on PATH.
pause
