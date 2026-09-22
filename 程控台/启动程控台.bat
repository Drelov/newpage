@echo off
cd /d "%~dp0"
if exist "%~dp0pythonw.exe" (
  start "" "%~dp0pythonw.exe" "%~dp0main.py"
  exit /b 0
)
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
echo pythonw.exe was not found. Use the zip package, or install Python and enable Add to PATH.
pause
