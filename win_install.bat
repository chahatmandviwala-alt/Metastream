@echo off
setlocal enabledelayedexpansion

set OUTPUT_FILE=Metastream.bat

rem ensure npm exists
where npm >nul 2>nul
if errorlevel 1 (
    echo Error: npm is not installed or not in PATH.
    echo Install Node.js from https://nodejs.org and re-run this script.
    exit /b 1
)

rem go to this script's folder
cd /d "%~dp0"

echo Running npm install...
npm install
if errorlevel 1 (
    echo npm install failed.
    exit /b 1
)

(
echo @echo off
echo setlocal
echo rem --- change the port if you like ---
echo set PORT=3000
echo.
echo cd /d "%%~dp0"
echo.
echo start "Metastream" cmd /c "node server.js"
echo.
echo timeout /t 2 /nobreak ^>nul
echo start "" http://localhost:%%PORT%%
echo.
echo endlocal
) > "%OUTPUT_FILE%"

echo Installation complete.
echo Run the app by double-clicking %OUTPUT_FILE%.

endlocal
