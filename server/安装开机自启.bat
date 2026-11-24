@echo off
rem linNas install autostart
chcp 936 >nul
title linNas install autostart
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"

if not exist "%STARTUP%" (
  echo [linNas] Startup folder not found: %STARTUP%
  pause
  exit /b 1
)

if not exist "%~dp0start-linnas.bat" (
  echo [linNas] start-linnas.bat not found next to this script.
  pause
  exit /b 1
)

rem VBS launcher: runs the server silently at logon (no console window)
set "VBS=%STARTUP%\linNas-autostart.vbs"
> "%VBS%" echo Set sh = CreateObject("WScript.Shell")
>> "%VBS%" echo sh.Run """%~dp0start-linnas.bat""", 0, False

echo [linNas] Autostart installed:
echo    %VBS%
echo.
echo linNas will start automatically when you log in.
echo To remove it, run Ð¶ÔØ¿ª»ú×ÔÆô.bat
pause
