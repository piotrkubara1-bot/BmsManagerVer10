@echo off
setlocal EnableDelayedExpansion

call "%~dp0load_env.bat" >nul 2>nul

set "PORT_ARG="
set "NO_PAUSE=0"
set "EXTRA_JAVA_ARGS="
if not "%~1"=="" (
    if /I "%~1"=="--no-pause" (
        set "NO_PAUSE=1"
        set "PORT_ARG=%~2"
    ) else (
        set "PORT_ARG=%~1"
        if /I "%~2"=="--no-pause" set "NO_PAUSE=1"
    )
)
if /I "%~1"=="--require-open" set "EXTRA_JAVA_ARGS=!EXTRA_JAVA_ARGS! --require-open"
if /I "%~2"=="--require-open" set "EXTRA_JAVA_ARGS=!EXTRA_JAVA_ARGS! --require-open"
if /I "%~3"=="--require-open" set "EXTRA_JAVA_ARGS=!EXTRA_JAVA_ARGS! --require-open"
if /I "%~4"=="--require-open" set "EXTRA_JAVA_ARGS=!EXTRA_JAVA_ARGS! --require-open"
set "JAVA_ARGS="
if not "%PORT_ARG%"=="" (
    if /I "%PORT_ARG:~0,7%"=="--port=" (
        set "JAVA_ARGS=%PORT_ARG%"
    ) else (
        set "JAVA_ARGS=--port=%PORT_ARG%"
    )
)

if not exist lib mkdir lib
if not exist bin mkdir bin
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "JAVAC_EXE="
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVAC_EXE for /f "delims=" %%I in ('where javac 2^>nul') do if not defined JAVAC_EXE set "JAVAC_EXE=%%~fI"
if not defined JAVA_EXE if defined JAVAC_EXE set "JAVA_EXE=%JAVAC_EXE:javac.exe=java.exe%"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~fI"
if not defined JAVAC_EXE (
    echo [BMS-UART] javac not found.
    exit /b 1
)
if not defined JAVA_EXE (
    echo [BMS-UART] java not found.
    exit /b 1
)

if not exist "lib\jSerialComm-2.11.0.jar" (
    echo [BMS-UART] Pobieranie jSerialComm...
    "%POWERSHELL_EXE%" -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fazecast/jSerialComm/2.11.0/jSerialComm-2.11.0.jar' -OutFile 'lib\jSerialComm-2.11.0.jar'"
)

echo [BMS-UART] Kompilacja BmsUartSender...
"%JAVAC_EXE%" -d bin -cp "bin;lib/*" src\main\java\BmsUartSender.java
if errorlevel 1 (
    echo [BMS-UART] Kompilacja nieudana.
    exit /b 1
)

echo [BMS-UART] Uruchamianie...
if "%JAVA_ARGS%"=="" (
    echo Konfiguracja: PORT=!SERIAL_PORT! z .env, URL=!BMS_API_INGEST_URL!
) else (
    echo Konfiguracja: PORT override=!JAVA_ARGS!, URL=!BMS_API_INGEST_URL!
)
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -cp "bin;lib/*" BmsUartSender %JAVA_ARGS% %EXTRA_JAVA_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
if not "%NO_PAUSE%"=="1" pause
exit /b %EXIT_CODE%
