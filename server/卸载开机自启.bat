@echo off
rem linNas remove autostart
chcp 936 >nul
title linNas remove autostart
set "VBS=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\linNas-autostart.vbs"
if exist "%VBS%" (
  del "%VBS%"
  echo [linNas] Autostart removed.
) else (
  echo [linNas] Autostart was not installed.
)
pause
