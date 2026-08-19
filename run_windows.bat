@echo off
chcp 65001 >nul
cd /d "%~dp0"
where pyw >nul 2>&1 && start "" pyw wifi_app.py && exit /b 0
where pythonw >nul 2>&1 && start "" pythonw wifi_app.py && exit /b 0
echo Khong tim thay Python. Hay dung file EXE trong GitHub Releases.
pause

