@echo off
rem Build TSEntity and install it into the Experiment Server (restart the server to load it).
cd /d "%~dp0"
set "JAVA_HOME=%APPDATA%\PrismLauncher\java\java-runtime-epsilon"
call "%~dp0..\Experiment Server\.tools\gradle-9.8.1\bin\gradle.bat" build --no-daemon -q && copy /y "build\libs\TSEntity-1.3.0.jar" "%~dp0..\Experiment Server\plugins\" >nul && echo Built and copied to Experiment Server\plugins.
pause
