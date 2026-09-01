@echo off
rem CholeilSDK -- compile the toolchain, then run the whole pipeline.
rem
rem   choleil x [-y]   extract everything (script.txt, pointers, graphics, stray text)
rem   choleil i        insert everything  (rebuild Choleil.md from the edited files)
rem
rem "x" overwrites script.txt and stray_text.txt with a fresh extraction from
rem the base ROM, discarding any translation work in them, so it asks for
rem confirmation first. -y (or /y, --yes) skips the prompt for scripted runs.
rem
rem Safe to run from any directory: it switches to its own first, because every
rem tool in the pipeline resolves its default filenames relative to the CWD.

setlocal
set "MODE=%~1"
set "FORCE="
if /i "%~2"=="-y"    set "FORCE=1"
if /i "%~2"=="/y"    set "FORCE=1"
if /i "%~2"=="--yes" set "FORCE=1"

if /i "%MODE%"=="x" goto :start
if /i "%MODE%"=="i" goto :start

echo usage: %~n0 ^<x^|i^> [-y]
echo   x   extract everything (text + graphics + stray text)
echo   i   insert everything (rebuild Choleil.md from script.txt + stray_text.txt)
echo   -y  skip the overwrite confirmation that "x" asks for
exit /b 2

:start
cd /d "%~dp0" || exit /b 1

if /i not "%MODE%"=="x" goto :java
if "%FORCE%"=="1" goto :java
if not exist "script.txt" if not exist "stray_text.txt" goto :java

echo.
echo  *** "x" re-extracts from the base ROM and OVERWRITES:
if exist "script.txt"     echo        script.txt
if exist "stray_text.txt" echo        stray_text.txt
echo  *** Any translation work in them will be lost.

rem Uncommitted edits are the ones that cannot be got back, so call them out.
where git >nul 2>&1 || goto :ask
for /f "delims=" %%f in ('git status --porcelain -- script.txt stray_text.txt 2^>nul') do (
    echo        UNCOMMITTED: %%f
    set "DIRTY=1"
)
if defined DIRTY echo  *** Those edits are NOT committed -- git cannot bring them back.

:ask
echo.
set "ANSWER="
set /p "ANSWER=Type yes to overwrite, anything else to cancel: "
if /i "%ANSWER%"=="yes" goto :java
echo Cancelled -- nothing was written.
exit /b 3

:java
rem The java on PATH here is an old JRE that cannot load these class files
rem (UnsupportedClassVersionError); JAVA_HOME is the JDK Maven builds with.
if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    echo WARNING: JAVA_HOME does not point at a JDK -- falling back to java on PATH,
    echo          which may be too old to run the compiled classes.
    set "JAVA=java"
)

rem Prefer an installed Maven; fall back to the wrapper, which bootstraps its
rem own pinned Maven on first use so a fresh clone builds with no setup.
where mvn >nul 2>&1 && goto :build_mvn

echo === building with the Maven wrapper ===
call "%~dp0mvnw.cmd" -q -Dmaven.test.skip=true package
if errorlevel 1 goto :build_failed
goto :pipeline

:build_mvn
rem mvn is deliberately unquoted: quoting it makes cmd resolve the
rem extensionless POSIX "mvn" script that ships next to mvn.cmd, which dies
rem with errorlevel 1 and no output.
echo === building with mvn ===
call mvn -q -Dmaven.test.skip=true package
if errorlevel 1 goto :build_failed
goto :pipeline

:build_failed
echo BUILD FAILED -- pipeline not run.
exit /b 1

:pipeline
"%JAVA%" -cp target\classes net.krusher.CholeilSDK %MODE%
exit /b %errorlevel%
