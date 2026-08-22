@echo off
rem CholeilSDK -- compile the toolchain, then run the whole pipeline.
rem
rem   choleil x   extract everything (script.txt, pointers, graphics, stray text)
rem   choleil i   insert everything  (rebuild Choleil.md from the edited files)
rem
rem WARNING: "x" overwrites script.txt and stray_text.txt with a fresh
rem extraction from the base ROM, discarding any translation work in them.
rem
rem Safe to run from any directory: it switches to its own first, because every
rem tool in the pipeline resolves its default filenames relative to the CWD.

setlocal
set "MODE=%~1"

if /i "%MODE%"=="x" goto :start
if /i "%MODE%"=="i" goto :start

echo usage: %~n0 ^<x^|i^>
echo   x   extract everything (text + graphics + stray text)
echo   i   insert everything (rebuild Choleil.md from script.txt + stray_text.txt)
exit /b 2

:start
cd /d "%~dp0" || exit /b 1

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
call "%~dp0mvnw.cmd" -q -DskipTests package
if errorlevel 1 goto :build_failed
goto :pipeline

:build_mvn
rem mvn is deliberately unquoted: quoting it makes cmd resolve the
rem extensionless POSIX "mvn" script that ships next to mvn.cmd, which dies
rem with errorlevel 1 and no output.
echo === building with mvn ===
call mvn -q -DskipTests package
if errorlevel 1 goto :build_failed
goto :pipeline

:build_failed
echo BUILD FAILED -- pipeline not run.
exit /b 1

:pipeline
"%JAVA%" -cp target\classes net.krusher.CholeilSDK %MODE%
exit /b %errorlevel%
