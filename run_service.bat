@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "JAVAC_EXE="
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVAC_EXE for /f "delims=" %%I in ('where javac 2^>nul') do if not defined JAVAC_EXE set "JAVAC_EXE=%%~fI"
if not defined JAVA_EXE if defined JAVAC_EXE set "JAVA_EXE=%JAVAC_EXE:javac.exe=java.exe%"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~fI"
if not defined JAVAC_EXE (
    echo [Service] javac not found.
    exit /b 1
)
if not defined JAVA_EXE (
    echo [Service] java not found.
    exit /b 1
)

set "MODE=%~1"
if "%MODE%"=="" set "MODE=normal"

if not exist lib mkdir lib
if not exist bin mkdir bin

if not exist "lib\jSerialComm-2.11.0.jar" (
    echo [Service] Downloading jSerialComm...
    "%POWERSHELL_EXE%" -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fazecast/jSerialComm/2.11.0/jSerialComm-2.11.0.jar' -OutFile 'lib\jSerialComm-2.11.0.jar'"
)

echo [Service] Compiling BmsApiServer...
"%JAVAC_EXE%" -d bin -cp "bin;lib/*" src\main\java\TinyBmsUartSettingsService.java src\main\java\BmsApiServer.java src\main\java\BmsTestFeeder.java
if errorlevel 1 (
    echo [Service] Compilation failed.
    exit /b 1
)

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
set "BMS_API_PORT=%BMS_API_PORT: =%"
if "%BMS_SIM_COUNT%"=="" set "BMS_SIM_COUNT=120"
if "%BMS_SIM_INTERVAL_MS%"=="" set "BMS_SIM_INTERVAL_MS=1000"
if "%BMS_STOP_EXISTING%"=="" set "BMS_STOP_EXISTING=1"
if "%BMS_STARTUP_RETRIES%"=="" set "BMS_STARTUP_RETRIES=15"
if "%BMS_STARTUP_DELAY_SEC%"=="" set "BMS_STARTUP_DELAY_SEC=1"

if "%BMS_STOP_EXISTING%"=="1" (
    "%POWERSHELL_EXE%" -NoProfile -Command "$listeners = Get-NetTCPConnection -LocalPort %BMS_API_PORT% -State Listen -ErrorAction SilentlyContinue; if ($listeners) { $listeners | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }" >nul 2>nul
    for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%BMS_API_PORT% .*LISTENING"') do taskkill /F /PID %%P >nul 2>nul
    "%POWERSHELL_EXE%" -NoProfile -Command "Start-Sleep -Seconds 1" >nul 2>nul
    curl.exe -fsS "http://127.0.0.1:%BMS_API_PORT%/api/health" >nul 2>nul
    if not errorlevel 1 (
        echo [Service] API is already running on port %BMS_API_PORT%.
        echo [Service] If this is stale, run .\stop_all.bat and start again.
        exit /b 0
    )
)

if /I "%MODE%"=="normal" goto :mode_normal
if /I "%MODE%"=="single" goto :mode_single
if /I "%MODE%"=="simulate" goto :mode_simulate
if /I "%MODE%"=="simulate4" goto :mode_simulate4

echo [Service] Unknown mode "%MODE%".
echo Usage: run_service.bat [normal^|single^|simulate^|simulate4]
exit /b 1

:mode_normal
set "BMS_ALLOWED_MODULES="
echo [Service] Mode: normal - modules 1..4
goto :start_api

:mode_single
set "BMS_ALLOWED_MODULES=1"
echo [Service] Mode: single - module 1 only
goto :start_api

:mode_simulate
set "BMS_ALLOWED_MODULES=1"
echo [Service] Mode: simulate - single module
goto :start_api

:mode_simulate4
set "BMS_ALLOWED_MODULES="
echo [Service] Mode: simulate4 - modules 1..4

:start_api
echo [Service] Starting BmsApiServer on port %BMS_API_PORT% ...
start "BmsApiServer" /D "%~dp0" /B "%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -cp "bin;lib/*" BmsApiServer

set /a __attempt=0
:wait_api
curl.exe -fsS "http://127.0.0.1:%BMS_API_PORT%/api/health" >nul 2>nul
if not errorlevel 1 goto :api_ok
set /a __attempt+=1
if %__attempt% GEQ %BMS_STARTUP_RETRIES% goto :api_fail
"%POWERSHELL_EXE%" -NoProfile -Command "Start-Sleep -Seconds %BMS_STARTUP_DELAY_SEC%" >nul 2>nul
goto :wait_api

:api_fail
echo [Service] API failed to start on port %BMS_API_PORT% after %BMS_STARTUP_RETRIES% attempts.
exit /b 1

:api_ok
if /I "%MODE%"=="simulate" goto :run_sim_single
if /I "%MODE%"=="simulate4" goto :run_sim_multi

echo [Service] API is running in background.
echo [Service] To stop it: .\stop_all.bat
exit /b 0

:run_sim_single
echo [Service] Starting simulator feed - single module ...
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -cp "bin;lib/*" BmsTestFeeder --mode=single --module=1 --count=%BMS_SIM_COUNT% --interval-ms=%BMS_SIM_INTERVAL_MS% --endpoint=http://127.0.0.1:%BMS_API_PORT%/api/ingest
exit /b %ERRORLEVEL%

:run_sim_multi
echo [Service] Starting simulator feed - 4 modules ...
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -cp "bin;lib/*" BmsTestFeeder --mode=multi --count=%BMS_SIM_COUNT% --interval-ms=%BMS_SIM_INTERVAL_MS% --endpoint=http://127.0.0.1:%BMS_API_PORT%/api/ingest
exit /b %ERRORLEVEL%
