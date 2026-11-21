@echo off
rem Builds "dist\VCF Desk\VCF Desk.exe" and, if NSIS is installed, the installer dist\VCF-Desk-Setup-<version>.exe
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    py -3 -m venv .venv || python -m venv .venv
)
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt pyinstaller || (pause & exit /b 1)
copy /y VCF_Desk.pyw VCF_Desk.py >nul
".venv\Scripts\pyinstaller.exe" --noconfirm --clean --windowed --name "VCF Desk" ^
  --icon assets\vcfdesk.ico --version-file version_info.txt ^
  --hidden-import vcf_core --hidden-import access_parser --hidden-import pyodbc ^
  --exclude-module tkinter --exclude-module PySide6.QtNetwork --exclude-module PySide6.QtQml --exclude-module PySide6.QtQuick ^
  VCF_Desk.py || (del VCF_Desk.py & pause & exit /b 1)
del VCF_Desk.py
del /q "dist\VCF Desk\_internal\PySide6\opengl32sw.dll" 2>nul
rmdir /s /q "dist\VCF Desk\_internal\PySide6\translations" 2>nul
echo.
echo Built: dist\VCF Desk\VCF Desk.exe

set "NSIS="
for %%P in ("%ProgramFiles(x86)%\NSIS\makensis.exe" "%ProgramFiles%\NSIS\makensis.exe") do if exist %%P set "NSIS=%%~P"
if not defined NSIS (
    echo NSIS not found - skipping installer. Get it with:  winget install NSIS.NSIS
) else (
    "%NSIS%" installer\vcfdesk.nsi && echo Built: dist\VCF-Desk-Setup-1.0.exe
)
pause
