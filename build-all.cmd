@echo off
set JAVA_HOME=C:\Users\Yousha101\.jdks\jdk-17.0.20.1+1
cd /d "%~dp0"
call "%~dp0gradlew.bat" :shared:desktopTest :shared:testDebugUnitTest :app:assembleTvDebug :app:assemblePhoneDebug :desktop:compileKotlin -PnovaplayNoAccounts=true > C:\gtmp\all.log 2>&1
echo EXIT %ERRORLEVEL% >> C:\gtmp\all.log
