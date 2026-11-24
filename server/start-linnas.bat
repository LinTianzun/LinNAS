@echo off
rem linNas silent launcher (ASCII filename, safe for VBS autostart)
chcp 936 >nul
cd /d "%~dp0"
start "linNas-server" /min "%~dp0linNas-server.exe" -no-browser
