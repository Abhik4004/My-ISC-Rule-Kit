@echo off
rem ISC Rule Kit launcher. Uses target\isc-rule-kit.jar (mvn package) if present, else the offline build output.
setlocal
set "HERE=%~dp0"
if exist "%HERE%target\isc-rule-kit.jar" (
  java -jar "%HERE%target\isc-rule-kit.jar" %*
  exit /b %ERRORLEVEL%
)
if not exist "%HERE%target\classpath.txt" (
  echo Not built yet. Run: mvn -q package   or   powershell -File scripts\build-offline.ps1 1>&2
  exit /b 2
)
set /p CP=<"%HERE%target\classpath.txt"
java -cp "%HERE%target\classes;%CP%" com.iscrulekit.Main %*
exit /b %ERRORLEVEL%
