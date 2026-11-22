@echo off
setlocal
set "JAVA_HOME=C:\Tools\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0gradlew.bat" assembleRelease "-Dorg.gradle.java.home=%JAVA_HOME%"
if errorlevel 1 exit /b 1
if not exist "%~dp0..\dist" mkdir "%~dp0..\dist"
copy /Y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0..\dist\AirPulse.apk"
echo APK: %~dp0..\dist\AirPulse.apk
