@echo off
setlocal
rem ----------------------------------------------------------------
rem  aip-stub : OpenAI-compatible fake model service on 11434.
rem    Needed by the tests whose @BeforeAll assumes a reachable model
rem    (FullTextSearchTest / RetrievalHitTestTest / RetrievalSecurityTest)
rem    and by the real-stack smoke/feature gates.
rem    Inspect what the app actually sent:  http://localhost:11434/__stub/requests
rem  USAGE:  cmd /c "scripts\aip-stub.cmd"  [--check]
rem ----------------------------------------------------------------
set "ROOT=%~dp0.."
set "BE=%ROOT%\backend"
if /i "%~1"=="--check" (
  echo [aip-stub] ROOT=%ROOT%
  echo [aip-stub] BE=%BE%
  if exist "%BE%\tools\openai_stub.py" (echo [aip-stub] OK: backend\tools\openai_stub.py found) else (echo [aip-stub] ERROR: stub script missing)
  where python >nul 2>nul && (echo [aip-stub] OK: python on PATH) || (echo [aip-stub] ERROR: python not on PATH)
  exit /b 0
)
if not exist "%BE%\tools\openai_stub.py" (
  echo [ERROR] stub script not found at "%BE%\tools\openai_stub.py"
  exit /b 1
)
title aip-stub (openai stub, 11434)
cd /d "%BE%"
echo [aip-stub] fake model on 11434. Close this window to stop.
python tools\openai_stub.py
