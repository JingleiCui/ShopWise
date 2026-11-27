@echo off
setlocal
where py.exe >nul 2>nul
if errorlevel 1 goto python
py -3 "%~dp0scripts\dev.py" %*
exit /b %errorlevel%
:python
python "%~dp0scripts\dev.py" %*
exit /b %errorlevel%
