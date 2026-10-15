@echo off
setlocal
rem ----------------------------------------------------------------
rem  aip-be-live : backend for the real stack.
rem    SERVER_PORT=18088 on purpose: frontend/.env.development sets
rem    VITE_API_TARGET=http://127.0.0.1:18088, so the dev server proxy
rem    and the backend must agree. Change both together or neither.
rem  USAGE:  cmd /c "scripts\aip-be-live.cmd"  [--check]
rem ----------------------------------------------------------------
set "ROOT=%~dp0.."
set "BE=%ROOT%\backend"
if /i "%~1"=="--check" (
  echo [aip-be-live] ROOT=%ROOT%
  echo [aip-be-live] BE=%BE%
  if exist "%BE%\mvn-jdk25.cmd" (echo [aip-be-live] OK: backend\mvn-jdk25.cmd found) else (echo [aip-be-live] ERROR: backend\mvn-jdk25.cmd missing)
  exit /b 0
)
if not exist "%BE%\mvn-jdk25.cmd" (
  echo [ERROR] backend wrapper not found at "%BE%\mvn-jdk25.cmd"
  exit /b 1
)
title aip-be-live (backend, 18088)
cd /d "%BE%"
set "SERVER_PORT=18088"
echo [aip-be-live] Spring Boot on 18088 (frontend proxy target agrees).
call mvn-jdk25.cmd -B spring-boot:run
