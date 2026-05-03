@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
set "BMS_API_PORT=%BMS_API_PORT: =%"
set "WEB_UI_PORT=%WEB_UI_PORT: =%"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

echo [Stop] Stopping listeners on ports %BMS_API_PORT% and %WEB_UI_PORT% ...
"%POWERSHELL_EXE%" -NoProfile -Command "$ports = @(%BMS_API_PORT%, %WEB_UI_PORT%); $listeners = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort }; if ($listeners) { $listeners | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }"
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%BMS_API_PORT% .*LISTENING" /C:":%WEB_UI_PORT% .*LISTENING"') do taskkill /F /PID %%P >nul 2>nul
"%POWERSHELL_EXE%" -NoProfile -Command "$names = @('BmsApiServer','StaticWebUiServer'); Get-CimInstance Win32_Process -Filter \"Name = 'java.exe' OR Name = 'javaw.exe'\" -ErrorAction SilentlyContinue | Where-Object { $cmd = $_.CommandLine; $cmd -and ($names | Where-Object { $cmd -like \"*$_*\" }) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>nul

echo [Stop] Done.
exit /b 0
