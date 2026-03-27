@echo off
REM build.bat -- gsheets package build script
REM Run by double-clicking or from Command Prompt
REM
REM ORDER:  1. fetch_gson.bat  (download Gson -- run once)
REM         2. build.bat       (compile + install)
REM
REM Supports: Stata 17, 18, 19, StataNow 17/18/19
REM v1.1.0

REM Keep window open until user presses a key
if "%1"=="RELAUNCH" goto :main
cmd /c "%~f0" RELAUNCH
exit

:main
setlocal EnableDelayedExpansion

echo.
echo  =========================================
echo   gsheets build v1.1.0
echo  =========================================
echo.

REM ------------------------------------------------------------------
REM Step 1: Auto-detect Stata installation
REM Parentheses in "Program Files (x86)" break if/else if chains,
REM so we use a subroutine + goto pattern instead.
REM ------------------------------------------------------------------
set STATA_DIR=
set STATA_VER=

call :find_stata "C:\Program Files\StataNow19"  StataNow19
call :find_stata "C:\Program Files\StataNow18"  StataNow18
call :find_stata "C:\Program Files\StataNow17"  StataNow17
call :find_stata "C:\Program Files\Stata19"     Stata19
call :find_stata "C:\Program Files\Stata18"     Stata18
call :find_stata "C:\Program Files\Stata17"     Stata17
call :find_stata "C:\Program Files (x86)\StataNow19" StataNow19
call :find_stata "C:\Program Files (x86)\StataNow18" StataNow18
call :find_stata "C:\Program Files (x86)\StataNow17" StataNow17
call :find_stata "C:\Program Files (x86)\Stata19"    Stata19
call :find_stata "C:\Program Files (x86)\Stata18"    Stata18
call :find_stata "C:\Program Files (x86)\Stata17"    Stata17

if not defined STATA_DIR (
    echo  [ERROR] Could not find Stata installation.
    echo.
    echo  Checked C:\Program Files and C:\Program Files ^(x86^) for:
    echo    Stata17, Stata18, Stata19, StataNow17, StataNow18, StataNow19
    echo.
    echo  If Stata is installed elsewhere, open Command Prompt and run:
    echo    set STATA_DIR=C:\Your\Stata\Path
    echo    build.bat RELAUNCH
    echo.
    goto :done
)

echo  [OK]  Stata version  : %STATA_VER%
echo  [OK]  Stata path     : %STATA_DIR%

REM ------------------------------------------------------------------
REM Step 2: Find Stata's bundled JDK
REM ------------------------------------------------------------------
set JAVA_BIN=

for /d %%D in ("%STATA_DIR%\utilities\java\windows-x64\*") do (
    if not defined JAVA_BIN (
        if exist "%%D\bin\javac.exe" (
            set "JAVA_BIN=%%D\bin"
        )
    )
)

if not defined JAVA_BIN (
    echo  [ERROR] Could not find javac.exe in Stata's bundled JDK.
    echo          Expected: %STATA_DIR%\utilities\java\windows-x64\*\bin\javac.exe
    goto :done
)

set "JAVAC=%JAVA_BIN%\javac.exe"
set "JAR_EXE=%JAVA_BIN%\jar.exe"
echo  [OK]  Java compiler  : %JAVAC%

REM ------------------------------------------------------------------
REM Step 3: Find SFI API jar
REM ------------------------------------------------------------------
set SFI_JAR=

for /r "%STATA_DIR%\utilities\jar" %%F in (sfi-api.jar) do (
    if not defined SFI_JAR set "SFI_JAR=%%F"
)

if not defined SFI_JAR (
    echo  [ERROR] sfi-api.jar not found under %STATA_DIR%\utilities\jar
    goto :done
)

echo  [OK]  SFI API jar    : %SFI_JAR%

REM ------------------------------------------------------------------
REM Step 4: Find Gson jar
REM ------------------------------------------------------------------
set SCRIPT_DIR=%~dp0
set "LIB_DIR=%SCRIPT_DIR%java\lib"
set GSON_JAR=

for %%F in ("%LIB_DIR%\gson*.jar") do (
    if not defined GSON_JAR set "GSON_JAR=%%F"
)

if not defined GSON_JAR (
    echo  [ERROR] Gson jar not found in java\lib\
    echo.
    echo  Run fetch_gson.bat first, then re-run build.bat.
    goto :done
)

echo  [OK]  Gson jar       : %GSON_JAR%

REM ------------------------------------------------------------------
REM Step 5: Set up directories
REM ------------------------------------------------------------------
set "SRC_DIR=%SCRIPT_DIR%java\src"
set "OUT_DIR=%SCRIPT_DIR%java\out"
set "DIST_DIR=%SCRIPT_DIR%dist"

if not exist "%OUT_DIR%"  mkdir "%OUT_DIR%"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

REM ------------------------------------------------------------------
REM Step 6: Compile Java sources
REM ------------------------------------------------------------------
echo.
echo  Compiling Java sources...

set SOURCES=
for /r "%SRC_DIR%" %%F in (*.java) do (
    set SOURCES=!SOURCES! "%%F"
)

"%JAVAC%" --release 11 ^
    -cp "%SFI_JAR%;%GSON_JAR%" ^
    -d "%OUT_DIR%" ^
    -encoding UTF-8 ^
    !SOURCES!

if %ERRORLEVEL% neq 0 (
    echo.
    echo  [ERROR] Compilation failed. See errors above.
    goto :done
)

echo  [OK]  Compilation successful.

REM ------------------------------------------------------------------
REM Step 7: Verify expected classes exist
REM ------------------------------------------------------------------
set BUILD_OK=1
call :check_class "GsheetsAuth"
call :check_class "OAuthManager"
call :check_class "GsheetsException"
call :check_class "ServiceAccountKey"
call :check_class "SheetReader"
call :check_class "StataWriter"
call :check_class "GsheetsImport"
call :check_class "StataReader"
call :check_class "SheetWriter"
call :check_class "GsheetsExport"

if %BUILD_OK%==0 (
    echo  [ERROR] One or more expected class files missing after compilation.
    goto :done
)

echo  [OK]  All classes verified.

REM ------------------------------------------------------------------
REM Step 8: Package fat JAR (gsheets + Gson shaded in)
REM ------------------------------------------------------------------
echo.
echo  Packaging gsheets.jar...

cd "%OUT_DIR%"
"%JAR_EXE%" xf "%GSON_JAR%"
cd "%SCRIPT_DIR%"

"%JAR_EXE%" cf "%DIST_DIR%\gsheets.jar" -C "%OUT_DIR%" .

if not exist "%DIST_DIR%\gsheets.jar" (
    echo  [ERROR] JAR creation failed.
    goto :done
)

REM Verify Gson was shaded into the JAR correctly
if not exist "%OUT_DIR%\com\google\gson\Gson.class" (
    echo  [ERROR] Gson classes not found after extraction.
    echo          The Gson JAR may be corrupted. Delete java\lib\gson*.jar
    echo          and run fetch_gson.bat again.
    goto :done
)

for %%F in ("%DIST_DIR%\gsheets.jar") do echo  [OK]  gsheets.jar ^(%%~zF bytes^)

REM ------------------------------------------------------------------
REM Step 9: Copy ado, help, pkg files to dist
REM ------------------------------------------------------------------
copy /Y "%SCRIPT_DIR%ado\gs4_auth.ado"         "%DIST_DIR%\gs4_auth.ado"         >nul
copy /Y "%SCRIPT_DIR%ado\gs4_deauth.ado"       "%DIST_DIR%\gs4_deauth.ado"       >nul
copy /Y "%SCRIPT_DIR%ado\gs4_get.ado"          "%DIST_DIR%\gs4_get.ado"          >nul
copy /Y "%SCRIPT_DIR%ado\import_gsheet.ado"    "%DIST_DIR%\import_gsheet.ado"    >nul
copy /Y "%SCRIPT_DIR%ado\export_gsheet.ado"    "%DIST_DIR%\export_gsheet.ado"    >nul
copy /Y "%SCRIPT_DIR%gsheets.pkg"              "%DIST_DIR%\gsheets.pkg"          >nul
copy /Y "%SCRIPT_DIR%stata.toc"                "%DIST_DIR%\stata.toc"            >nul
copy /Y "%SCRIPT_DIR%help\gs4_auth.sthlp"      "%DIST_DIR%\gs4_auth.sthlp"      >nul
copy /Y "%SCRIPT_DIR%help\gs4_deauth.sthlp"    "%DIST_DIR%\gs4_deauth.sthlp"    >nul
copy /Y "%SCRIPT_DIR%help\gs4_get.sthlp"       "%DIST_DIR%\gs4_get.sthlp"       >nul
copy /Y "%SCRIPT_DIR%help\import_gsheet.sthlp" "%DIST_DIR%\import_gsheet.sthlp" >nul
copy /Y "%SCRIPT_DIR%help\export_gsheet.sthlp" "%DIST_DIR%\export_gsheet.sthlp" >nul

echo  [OK]  Files copied to dist\

REM ------------------------------------------------------------------
REM Step 10: Install to Stata ado paths
REM ------------------------------------------------------------------
set "ADO_DIR1=C:\ado\personal"
if not exist "%ADO_DIR1%" mkdir "%ADO_DIR1%"

set "ADO_DIR2=%USERPROFILE%\ado\personal"
if not exist "%ADO_DIR2%" mkdir "%ADO_DIR2%"

for %%D in ("%ADO_DIR1%" "%ADO_DIR2%") do (
    copy /Y "%DIST_DIR%\gsheets.jar"           "%%~D\gsheets.jar"           >nul
    copy /Y "%DIST_DIR%\gs4_auth.ado"          "%%~D\gs4_auth.ado"          >nul
    copy /Y "%DIST_DIR%\gs4_deauth.ado"        "%%~D\gs4_deauth.ado"        >nul
    copy /Y "%DIST_DIR%\gs4_get.ado"           "%%~D\gs4_get.ado"           >nul
    copy /Y "%DIST_DIR%\import_gsheet.ado"     "%%~D\import_gsheet.ado"     >nul
    copy /Y "%DIST_DIR%\export_gsheet.ado"     "%%~D\export_gsheet.ado"     >nul
    copy /Y "%DIST_DIR%\gsheets.pkg"           "%%~D\gsheets.pkg"           >nul
    copy /Y "%DIST_DIR%\stata.toc"             "%%~D\stata.toc"             >nul
    copy /Y "%DIST_DIR%\gs4_auth.sthlp"        "%%~D\gs4_auth.sthlp"        >nul
    copy /Y "%DIST_DIR%\gs4_deauth.sthlp"      "%%~D\gs4_deauth.sthlp"      >nul
    copy /Y "%DIST_DIR%\gs4_get.sthlp"         "%%~D\gs4_get.sthlp"         >nul
    copy /Y "%DIST_DIR%\import_gsheet.sthlp"   "%%~D\import_gsheet.sthlp"   >nul
    copy /Y "%DIST_DIR%\export_gsheet.sthlp"   "%%~D\export_gsheet.sthlp"   >nul
)

echo  [OK]  Installed to %ADO_DIR1%
echo  [OK]  Installed to %ADO_DIR2%

echo.
echo  =========================================
echo   Build complete. Test in Stata:
echo     gs4_auth, status
echo     ado describe gsheets
echo  =========================================

goto :done

REM ------------------------------------------------------------------
REM Subroutine: check_class
REM Usage: call :check_class "ClassName"
REM ------------------------------------------------------------------
:check_class
if not exist "%OUT_DIR%\com\gsheets\%~1.class" (
    echo  [MISSING] com\gsheets\%~1.class
    set BUILD_OK=0
)
goto :eof

REM ------------------------------------------------------------------
REM Subroutine: find_stata
REM Usage: call :find_stata "C:\path\to\Stata" VersionLabel
REM Sets STATA_DIR and STATA_VER if found and not already set
REM ------------------------------------------------------------------
:find_stata
if defined STATA_DIR goto :eof
if exist "%~1\utilities\java\" (
    set "STATA_DIR=%~1"
    set "STATA_VER=%~2"
)
goto :eof

:done
echo.
pause
endlocal
