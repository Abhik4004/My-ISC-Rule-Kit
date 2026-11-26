@echo off
rem ISC Rule Kit launcher. Builds the kit automatically on first use and after the code changes (e.g. git pull).
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%scripts\ensure-built.ps1"
if errorlevel 1 exit /b 2
if exist "%HERE%target\classpath.txt" (
  set /p CP=<"%HERE%target\classpath.txt"
  goto classes
)
java -jar "%HERE%target\isc-rule-kit.jar" %*
exit /b %ERRORLEVEL%
:classes
java -cp "%HERE%target\classes;%CP%" com.iscrulekit.Main %*
exit /b %ERRORLEVEL%
