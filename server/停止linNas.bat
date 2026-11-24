@echo off
rem linNas stop
chcp 936 >nul
taskkill /IM linNas-server.exe /F >nul 2>&1
if %errorlevel%==0 (
  echo [linNas] 已停止。
) else (
  echo [linNas] 服务未在运行。
)
ping -n 2 127.0.0.1 >nul
