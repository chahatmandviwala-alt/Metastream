@echo off
setlocal DisableDelayedExpansion

title Metastream Installer

rem go to this script's folder
cd /d "%~dp0"

echo.
echo ============================
echo   Metastream Installation
echo ============================
echo.

rem Check for npm
where npm >nul 2>nul
if errorlevel 1 goto :NO_NPM

echo Installing dependencies (this may take a minute)...

if exist "package-lock.json" (
    call npm ci
) else (
    call npm install
)

if errorlevel 1 goto :NPM_FAIL

set "OUTPUT_FILE=Metastream.bat"

(
echo @echo off
echo setlocal
echo.
echo cd /d "%%~dp0"
echo.
echo start "Metastream" cmd /c "npm run electron:dev"
echo.
echo timeout /t 2 /nobreak ^>nul
echo.
echo endlocal
) > "%OUTPUT_FILE%"

echo.
echo ============================
echo   Installation complete.
echo ============================
echo.
echo Start Metastream by double-clicking: %OUTPUT_FILE%
echo.
pause
exit /b 0

:NO_NPM
echo ERROR: Node.js / npm is not installed.
echo.
echo Please install Node.js (it includes npm), then run this installer again.
echo.
echo Download: https://nodejs.org
echo.
pause
exit /b 1

:NPM_FAIL
echo.
echo ERROR: Dependency installation failed.
echo.
echo Try closing this window and running WinInstall.bat again.
echo If it still fails, you may be offline or behind a restricted network.
echo.
pause
exit /b 1
@echo off
setlocal enabledelayedexpansion

title Metastream Installer

rem go to this script's folder
cd /d "%~dp0"

echo.
echo ============================
echo   Metastream Installation
echo ============================
echo.

rem Check for npm
where npm >nul 2>nul
if errorlevel 1 (
    echo ERROR: Node.js / npm is not installed.
    echo.
    echo Please install Node.js (it includes npm), then run this installer again.
    echo.
    echo Download: https://nodejs.org
    echo.
    pause
    exit /b 1
)

echo Installing dependencies (this may take a minute)...
if exist package-lock.json (
    npm ci
) else (
    npm install
)

if errorlevel 1 (
    echo.
    echo ERROR: Dependency installation failed.
    echo.
    echo Try closing this window and running install.bat again.
    echo If it still fails, you may be offline or behind a restricted network.
    echo.
    pause
    exit /b 1
)

set OUTPUT_FILE=Metastream.bat

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

echo.
echo ============================
echo   Installation complete.
echo ============================
echo.
echo Start Metastream by double-clicking: %OUTPUT_FILE%
echo.
pause
endlocal
