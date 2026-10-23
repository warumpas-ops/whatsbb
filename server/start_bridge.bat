@echo off
title WhatsBB Bridge Server
color 0A
setlocal EnableDelayedExpansion

set PATH=C:\Program Files\nodejs;%PATH%
cd /d "%~dp0"

echo =======================================================
echo          WhatsBB Companion Bridge for BB10
echo =======================================================
echo.

:: Detect local IPv4
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4 Address" /c:"Adresse IPv4"') do (
    set IP=%%a
    set IP=!IP: =!
    if not "!IP!"=="" (
        if not "!IP:~0,3!"=="127" (
            if not "!IP:~0,7!"=="169.254" (
                set LOCAL_IP=!IP!
            )
        )
    )
)

echo [WhatsBB] Detected Bridge Server Address:
if defined LOCAL_IP (
    echo   ==^> http://%LOCAL_IP%:3000
) else (
    echo   ==^> http://localhost:3000
)
echo.
echo [WhatsBB] In the WhatsBB app on your BlackBerry:
echo          Go to Settings and enter the URL above.
echo.
echo [WhatsBB] Starting Baileys WhatsApp Multi-Device engine...
echo =======================================================
echo.

node server.js

pause
