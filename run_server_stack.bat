@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "MYSQL_START_BAT=C:\xampp\mysql_start.bat"
set "MYSQL_EXE=C:\xampp\mysql\bin\mysql.exe"
set "DB_HOST_LOCAL=127.0.0.1"
if not "%DB_HOST%"=="" set "DB_HOST_LOCAL=%DB_HOST%"
set "DB_PORT_LOCAL=3306"
if not "%DB_PORT%"=="" set "DB_PORT_LOCAL=%DB_PORT%"
set "DB_USER_LOCAL=root"
if not "%DB_USER%"=="" set "DB_USER_LOCAL=%DB_USER%"
set "DB_NAME_LOCAL=bms"
if not "%DB_NAME%"=="" set "DB_NAME_LOCAL=%DB_NAME%"

if /I "%~1"=="stop" (
    call "%~dp0stop_all.bat"
    exit /b %ERRORLEVEL%
)

echo [Stack] Stopping old backend/Web UI listeners...
call "%~dp0stop_all.bat" >nul 2>nul
"%POWERSHELL_EXE%" -NoProfile -Command "Start-Sleep -Seconds 1" >nul 2>nul

echo [Stack] Ensuring MySQL is running...
"%POWERSHELL_EXE%" -NoProfile -Command "$ok = Test-NetConnection -ComputerName '%DB_HOST_LOCAL%' -Port %DB_PORT_LOCAL% -WarningAction SilentlyContinue; if ($ok.TcpTestSucceeded) { exit 0 } else { exit 1 }" >nul 2>nul
if errorlevel 1 (
    if exist "%MYSQL_START_BAT%" (
        start "BMS-MySQL" /MIN cmd /c ""%MYSQL_START_BAT%""
    ) else (
        echo [Stack] MySQL is not listening and XAMPP start script was not found at %MYSQL_START_BAT%.
        echo [Stack] Start MySQL manually and retry.
        exit /b 1
    )
)

set /a __db_attempt=0
:wait_mysql
"%POWERSHELL_EXE%" -NoProfile -Command "$ok = Test-NetConnection -ComputerName '%DB_HOST_LOCAL%' -Port %DB_PORT_LOCAL% -WarningAction SilentlyContinue; if ($ok.TcpTestSucceeded) { exit 0 } else { exit 1 }" >nul 2>nul
if not errorlevel 1 goto :mysql_ready
set /a __db_attempt+=1
if %__db_attempt% GEQ 20 (
    echo [Stack] MySQL did not start on %DB_HOST_LOCAL%:%DB_PORT_LOCAL%.
    exit /b 1
)
"%POWERSHELL_EXE%" -NoProfile -Command "Start-Sleep -Seconds 1" >nul 2>nul
goto :wait_mysql

:mysql_ready
echo [Stack] MySQL is ready.

if not exist "%MYSQL_EXE%" (
    echo [Stack] mysql.exe not found at %MYSQL_EXE%.
    exit /b 1
)

echo [Stack] Applying database schema...
if "%DB_PASSWORD%"=="" (
    "%MYSQL_EXE%" -h %DB_HOST_LOCAL% -P %DB_PORT_LOCAL% -u %DB_USER_LOCAL% < "%~dp0bms_schema.sql"
) else (
    "%MYSQL_EXE%" -h %DB_HOST_LOCAL% -P %DB_PORT_LOCAL% -u %DB_USER_LOCAL% -p%DB_PASSWORD% < "%~dp0bms_schema.sql"
)
if errorlevel 1 (
    echo [Stack] Schema import failed.
    exit /b 1
)

echo [Stack] Starting backend + Web UI...
call "%~dp0run_full_stack.bat" normal
exit /b %ERRORLEVEL%
