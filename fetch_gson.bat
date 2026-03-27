@echo off
REM fetch_gson.bat -- Step 1 of the gsheets build process (Windows)
REM
REM PURPOSE: Downloads Gson from Maven Central into java\lib\
REM Gson is the only external dependency for gsheets and must be
REM present before running build.bat (Step 2).
REM
REM ORDER:  1. fetch_gson.bat   <- YOU ARE HERE
REM         2. build.bat
REM
REM Run this once. Re-run only if you need to refresh Gson.
REM
REM --ssl-no-revoke: bypasses certificate revocation checks on
REM institutional/corporate networks (schannel error 0x80092012).
REM --retry 2: retries twice on transient failures before giving up.
REM Window stays open after all attempts regardless of success or failure.
REM v1.0.0

setlocal EnableDelayedExpansion

set DEST=java\lib
if not exist "%DEST%" mkdir "%DEST%"
set FAILURES=0

echo.
echo  =========================================
echo   Fetching Gson for gsheets
echo  =========================================
echo.

REM == [1/1] Gson ==============================================================
set GSON_VERSION=2.10.1
set GSON_FILE=gson-%GSON_VERSION%.jar
set GSON_URL=https://repo1.maven.org/maven2/com/google/code/gson/gson/%GSON_VERSION%/%GSON_FILE%

REM Skip if already present
if exist "%DEST%\%GSON_FILE%" (
    for %%F in ("%DEST%\%GSON_FILE%") do set SZ=%%~zF
    echo  [OK]   %GSON_FILE% already present -- !SZ! bytes
    echo         Skipping download.
    goto :summary
)

REM Also skip if any other gson version is already present
set FOUND_OTHER=
for %%F in ("%DEST%\gson*.jar") do set FOUND_OTHER=%%F
if defined FOUND_OTHER (
    for %%F in ("%FOUND_OTHER%") do set SZ=%%~zF
    echo  [OK]   Found existing Gson jar: %FOUND_OTHER% -- !SZ! bytes
    echo         Skipping download. build.bat will use this version.
    goto :summary
)

echo  [1/1] Fetching gson-%GSON_VERSION%.jar...
echo        %GSON_URL%
curl -L --show-error --ssl-no-revoke --retry 2 "%GSON_URL%" -o "%DEST%\%GSON_FILE%"
set CURL_RC=%ERRORLEVEL%
if %CURL_RC% neq 0 (
    echo  [FAIL] gson -- curl exit code %CURL_RC%
    set /a FAILURES+=1
) else (
    for %%F in ("%DEST%\%GSON_FILE%") do set SZ=%%~zF
    if !SZ! LSS 100000 (
        echo  [FAIL] gson -- file too small (!SZ! bytes^), likely an error page
        del /f /q "%DEST%\%GSON_FILE%" >nul 2>&1
        set /a FAILURES+=1
    ) else (
        echo  [OK]   gson-%GSON_VERSION%.jar -- !SZ! bytes
    )
)

:summary
echo.
if %FAILURES%==0 (
    echo  =========================================
    echo   Gson downloaded successfully.
    echo   Next step: run build.bat
    echo  =========================================
) else (
    echo  =========================================
    echo   Gson failed to download.
    echo   Download it manually from:
    echo   %GSON_URL%
    echo   Save as: %DEST%\%GSON_FILE%
    echo   Then run build.bat.
    echo  =========================================
)
echo.
pause
endlocal
