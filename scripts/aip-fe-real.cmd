@echo off
setlocal
rem ----------------------------------------------------------------
rem  aip-fe-real : frontend dev server in REAL-STACK mode.
rem    port 5280, VITE_USE_MOCK=false -- needed by verify_features.py
rem    and smoke_test.py (they drive a real backend, not the mock).
rem  NOTE: port 5280 can only be one mode at a time, so the 13 gates
rem  take TWO passes: mock (11 gates) then real (the 2 stack gates).
rem  USAGE:  cmd /c "scripts\aip-fe-real.cmd"  [--check]
rem ----------------------------------------------------------------
set "ROOT=%~dp0.."
set "FE=%ROOT%\frontend"
if /i "%~1"=="--check" (
  echo [aip-fe-real] ROOT=%ROOT%
  echo [aip-fe-real] FE=%FE%
  if exist "%FE%\package.json" (echo [aip-fe-real] OK: frontend/package.json found) else (echo [aip-fe-real] ERROR: frontend/package.json missing)
  exit /b 0
)
if not exist "%FE%\package.json" (
  echo [ERROR] frontend not found at "%FE%"
  exit /b 1
)
title aip-fe-real (frontend dev, 5280, real stack)
cd /d "%FE%"
set "VITE_USE_MOCK=false"
echo [aip-fe-real] vite on 5280 with VITE_USE_MOCK=false.
echo [aip-fe-real] the backend must answer on 18088 (start scripts\aip-be-live.cmd first).
call npm run dev
