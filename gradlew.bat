@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
@rem Set JAVA_HOME if not already set
if "%JAVA_HOME%" == "" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
)
"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
