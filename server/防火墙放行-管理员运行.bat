@echo off
rem 以管理员身份运行本脚本,开放 linNas 端口(默认 52700)给局域网访问
title linNas 防火墙放行
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 需要管理员权限,请右键选择"以管理员身份运行"
    pause
    exit /b 1
)
netsh advfirewall firewall delete rule name="linNas" >nul 2>&1
netsh advfirewall firewall add rule name="linNas" dir=in action=allow protocol=TCP localport=52700
echo.
echo 已放行 TCP 52700 端口(仅当前网络)
pause
