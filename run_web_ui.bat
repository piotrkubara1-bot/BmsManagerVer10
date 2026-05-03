@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
set "WEB_UI_PORT=%WEB_UI_PORT: =%"

curl.exe -fsS "http://127.0.0.1:%WEB_UI_PORT%/dashboard.html" >nul 2>nul
if not errorlevel 1 (
    echo [WebUI] Web UI is already running on port %WEB_UI_PORT%.
    exit /b 0
)

if not exist bin mkdir bin
set "JAVAC_EXE="
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVAC_EXE for /f "delims=" %%I in ('where javac 2^>nul') do if not defined JAVAC_EXE set "JAVAC_EXE=%%~fI"
if not defined JAVA_EXE if defined JAVAC_EXE set "JAVA_EXE=%JAVAC_EXE:javac.exe=java.exe%"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~fI"
if not defined JAVAC_EXE (
    echo [WebUI] javac not found.
    exit /b 1
)
if not defined JAVA_EXE (
    echo [WebUI] java not found.
    exit /b 1
)

echo [WebUI] Compiling StaticWebUiServer...
"%JAVAC_EXE%" -d bin src\main\java\StaticWebUiServer.java
if errorlevel 1 (
    echo [WebUI] Compilation failed.
    exit /b 1
)

echo [WebUI] Starting Java Web UI on port %WEB_UI_PORT% ...
"%JAVA_EXE%" -cp "bin;src\main\resources" StaticWebUiServer
exit /b %ERRORLEVEL%
