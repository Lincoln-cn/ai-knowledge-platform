@echo off
setlocal
rem ----------------------------------------------------------------
rem  aip-fe-live : frontend dev server for the 11 browser gates.
rem    port 5280, VITE_USE_MOCK defaults to true (.env.development)
rem  CRLF + ASCII ONLY: an LF-only or non-ASCII .cmd is mis-parsed by
rem  cmd.exe (it once read "setlocal enabledelayedexpansion" as
rem  "ayedexpansion" and silently ate a following line).
rem  USAGE:  double-click, or:  cmd /c "scripts\aip-fe-live.cmd"
rem          cmd /c "scripts\aip-fe-live.cmd" --check   (paths only)
rem ----------------------------------------------------------------
set "ROOT=%~dp0.."
set "FE=%ROOT%\frontend"
if /i "%~1"=="--check" (
  echo [aip-fe-live] ROOT=%ROOT%
  echo [aip-fe-live] FE=%FE%
  if exist "%FE%\package.json" (echo [aip-fe-live] OK: frontend/package.json found) else (echo [aip-fe-live] ERROR: frontend/package.json missing)
  where npm >nul 2>nul && (echo [aip-fe-live] OK: npm on PATH) || (echo [aip-fe-live] ERROR: npm not on PATH)
  exit /b 0
)
if not exist "%FE%\package.json" (
  echo [ERROR] frontend not found at "%FE%"
  exit /b 1
)
title aip-fe-live (frontend dev, 5280, mock)
cd /d "%FE%"
echo [aip-fe-live] vite on 5280, VITE_USE_MOCK defaults to true -- this is what the 11 browser gates want.
echo [aip-fe-live] wait for "Local: http://localhost:5280/" then run: cmd /c "tools\run-frontend-gates.cmd"
call npm run dev
