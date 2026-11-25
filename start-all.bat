@echo off
title GhostKey Stealth Launcher
echo =======================================================
echo          [GHOSTKEY STEALTH MESSAGING SUITE]
echo   Zero-Trace OpenPGP Blind Dead-Drop with Tor Routing
echo =======================================================
echo.

echo Starting GhostKey Blind Relay & Stealth Web UI...
start "GhostKey Relay [PORT 8765]" cmd /k "cd /d %~dp0server && npm start"

timeout /t 2 /nobreak >nul

echo Opening GhostKey Console in your browser...
start http://localhost:8765/

echo.
echo =======================================================
echo GhostKey System is now active!
echo Relay URL:   http://127.0.0.1:8765
echo Web Console: http://localhost:8765/
echo.
echo For Android: Open the /android directory in Android Studio
echo or build a release APK via ./gradlew assembleRelease.
echo =======================================================
pause
