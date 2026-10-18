@echo off
setlocal
set "JAVA_HOME=C:\Users\Pc\Projects\Ballistic calculator app\BallisticScope\.tools\jdk\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE=C:\Users\Pc\Projects\Ballistic calculator app\BallisticScope\.tools\gradle-8.7\bin\gradle.bat"
if not exist "%GRADLE%" (
    echo Gradle not found: %GRADLE%
    exit /b 1
)
call "%GRADLE%" -p "%~dp0." assembleDebug
if errorlevel 1 exit /b 1
if not exist "%~dp0..\dist" mkdir "%~dp0..\dist"
copy /Y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0..\dist\AirPulse.apk"
echo APK: %~dp0..\dist\AirPulse.apk
