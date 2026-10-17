@echo off
setlocal
rem ----------------------------------------------------------------
rem  aip-be-auto : backend for LOCAL AUTOMATION (captcha DISABLED).
rem
rem  WHAT IT IS
rem    A side-by-side twin of aip-be-live.cmd. It starts the SAME
rem    backend on the SAME port, with ONE difference:
rem        CAPTCHA_ENABLED=false
rem
rem  WHY IT EXISTS
rem    app.security.captcha.enabled defaults to true (compliance).
rem    Scripts that log in over the API (no human to type a code)
rem    get HTTP 400 / code 1003 on /auth/login. CI already starts
rem    the backend with this switch off:
rem        .github/workflows/ci.yml  ->  --app.security.captcha.enabled=false
rem    This script is the local equivalent, WITHOUT touching
rem    aip-be-live.cmd (the human-facing launcher must keep the
rem    captcha ON -- that is the real deployment behaviour).
rem
rem  NEVER use this for a demo or a shared environment.
rem    Captcha off = one compliance control item given up.
rem
rem  PORT
rem    18088 on purpose: frontend/.env.development sets
rem    VITE_API_TARGET=http://127.0.0.1:18088, so the dev server
rem    proxy and the backend must agree. Change both together.
rem
rem  TWO .cmd PITFALLS THIS FILE ALREADY AVOIDS
rem    1) LINE ENDINGS MUST BE CRLF. With LF-only, cmd.exe silently
rem       fails to parse the multi-line "if (...)" block and the
rem       script exits with NO output at all. If you edit this file
rem       with a tool that writes LF, convert it back to CRLF.
rem    2) NO NON-ASCII BYTES. A Chinese comment in a .cmd file is
rem       read as GBK by cmd.exe; it has previously swallowed a
rem       neighbouring "set" line. Keep this file pure ASCII.
rem
rem  USAGE
rem    cmd /c "scripts\aip-be-auto.cmd"           start it
rem    cmd /c "scripts\aip-be-auto.cmd" --check   verify wiring, do not start
rem ----------------------------------------------------------------
set "ROOT=%~dp0.."
set "BE=%ROOT%\backend"
if /i "%~1"=="--check" (
  echo [aip-be-auto] ROOT=%ROOT%
  echo [aip-be-auto] BE=%BE%
  if exist "%BE%\mvn-jdk25.cmd" (echo [aip-be-auto] OK: backend\mvn-jdk25.cmd found) else (echo [aip-be-auto] ERROR: backend\mvn-jdk25.cmd missing)
  echo [aip-be-auto] OK: CAPTCHA_ENABLED=false - captcha off
  echo [aip-be-auto] OK: SERVER_PORT=18088 - must match VITE_API_TARGET
  exit /b 0
)
if not exist "%BE%\mvn-jdk25.cmd" (
  echo [ERROR] backend wrapper not found at "%BE%\mvn-jdk25.cmd"
  exit /b 1
)
title aip-be-auto (backend, 18088, CAPTCHA OFF)
cd /d "%BE%"
set "SERVER_PORT=18088"
rem --- the one difference from aip-be-live.cmd -------------------
rem  application.yml reads it as ${CAPTCHA_ENABLED:true}.
rem  Kept as a plain env var: no quoting pitfalls in .cmd.
set "CAPTCHA_ENABLED=false"
rem --------------------------------------------------------------
echo [aip-be-auto] Spring Boot on 18088, CAPTCHA DISABLED (automation only).
call mvn-jdk25.cmd -B spring-boot:run
