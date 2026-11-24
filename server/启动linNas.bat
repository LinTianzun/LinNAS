@echo off
rem linNas start
chcp 936 >nul
title linNas
cd /d "%~dp0"

set "EXE=linNas-server.exe"
set "PORT=52700"
set "URL=http://127.0.0.1:%PORT%/ui/"

if not exist "%EXE%" (
  echo [错误] 找不到 %EXE%,请把本脚本放在 server\ 目录下。
  pause
  exit /b 1
)

tasklist /FI "IMAGENAME eq %EXE%" 2>nul | find /I "%EXE%" >nul
if %errorlevel%==0 (
  echo [linNas] 服务已在运行,直接打开网页...
  start "" "%URL%"
  ping -n 2 127.0.0.1 >nul
  exit /b 0
)

echo [linNas] 正在启动服务端...
start "linNas-server" /min "%EXE%" -no-browser

set /a tries=0
:waitloop
ping -n 2 127.0.0.1 >nul
set /a tries+=1
powershell -NoProfile -Command "try{ Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 'http://127.0.0.1:%PORT%/api/health' | Out-Null; exit 0 }catch{ exit 1 }" >nul 2>&1
if %errorlevel%==0 goto ready
if %tries% lss 10 goto waitloop

echo [警告] 服务暂未响应,仍会打开网页。
:ready
echo [linNas] 已就绪: %URL%
start "" "%URL%"
ping -n 2 127.0.0.1 >nul
exit /b 0
