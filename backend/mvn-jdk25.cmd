@echo off
rem ---------------------------------------------------------------------------
rem  Run Maven with Temurin 25, the version this project is pinned to
rem  (design doc 09, section 4.1).
rem
rem  Keep this file ASCII-only: cmd.exe reads .cmd in the OEM code page, so
rem  UTF-8 CJK comments get parsed as commands and break the script.
rem
rem  Do NOT name this file mvn.cmd: the current directory precedes PATH when
rem  resolving a command name, so "call mvn" would resolve to this script and
rem  recurse until the batch stack blows up.
rem
rem  Why a wrapper at all: the machine's default JAVA_HOME is not 25 while
rem  pom.xml declares java.version=25. Pinning the JDK inside the project means
rem  "clone and build" works, and your other projects stay untouched.
rem
rem  Usage:    mvn-jdk25.cmd clean verify     (args pass through to Maven)
rem  Override: set AAI_PLATFORM_JDK25=<path to a JDK 25> before running this
rem ---------------------------------------------------------------------------
setlocal

if "%AAI_PLATFORM_JDK25%"=="" set "AAI_PLATFORM_JDK25=D:\Java\jdk-25"

if not exist "%AAI_PLATFORM_JDK25%\bin\java.exe" (
  echo [mvn-jdk25] JDK 25 not found at "%AAI_PLATFORM_JDK25%" 1>&2
  echo [mvn-jdk25] Install Temurin 25, or set AAI_PLATFORM_JDK25 to point at it 1>&2
  exit /b 1
)

set "JAVA_HOME=%AAI_PLATFORM_JDK25%"
call mvn %*
exit /b %ERRORLEVEL%
