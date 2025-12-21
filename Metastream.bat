@echo off
setlocal
rem --- change the port if you like ---
set PORT=3000

rem go to this script's folder
cd /d "%~dp0"

rem start the server in its own window
start "Metastream" cmd /c "node server.js"

rem give the server a moment to boot, then open your browser
timeout /t 2 /nobreak >nul
start "" http://localhost:%PORT%/

endlocal
