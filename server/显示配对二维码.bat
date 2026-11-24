@echo off
rem Show linNas pairing QR code (scan with the App, no manual IP typing)
title linNas Pairing QR
set "PORT=52700"
set "URL=http://127.0.0.1:%PORT%/pair"

powershell -NoProfile -Command "if (-not (Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue)) { exit 1 }"
if errorlevel 1 (
  echo [linNas] server not running, starting...
  if exist "%~dp0linNas-server.exe" (
    start "" /min "%~dp0linNas-server.exe"
    ping -n 4 127.0.0.1 >nul
  ) else (
    echo [ERROR] linNas-server.exe not found. Put this .bat inside the server folder.
    pause
    exit /b 1
  )
)

echo [linNas] opening pairing page: %URL%
echo On your phone: open linNas App -^> tap "Scan QR" -^> aim at the QR on screen
start "" "%URL%"
ping -n 3 127.0.0.1 >nul