@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

set "MODE=%~1"
if "%MODE%"=="" set "MODE=normal"
if /I "%MODE%"=="--no-gui" set "MODE=normal"

if /I "%MODE%"=="stop" (
    call "%~dp0stop_all.bat"
    exit /b %ERRORLEVEL%
)

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
set "BMS_API_PORT=%BMS_API_PORT: =%"
set "WEB_UI_PORT=%WEB_UI_PORT: =%"

call "%~dp0stop_all.bat" >nul 2>nul
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -Command "Start-Sleep -Seconds 1" >nul 2>nul

echo [FullStack] Starting service mode: %MODE%
start "BMS-Service" /D "%~dp0" /B cmd /c "set BMS_API_PORT=%BMS_API_PORT% && call run_service.bat %MODE%"

"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -Command "Start-Sleep -Seconds 2" >nul 2>nul

echo [FullStack] Starting Web UI on port %WEB_UI_PORT%
start "BMS-WebUI" /D "%~dp0" /B cmd /c "set WEB_UI_PORT=%WEB_UI_PORT% && call run_web_ui.bat"

echo [FullStack] GUI is not started here. Run build_and_run_gui.bat separately.
echo [FullStack] Service: http://127.0.0.1:%BMS_API_PORT%/api/health
echo [FullStack] Web UI : http://127.0.0.1:%WEB_UI_PORT%/dashboard.html
exit /b 0
