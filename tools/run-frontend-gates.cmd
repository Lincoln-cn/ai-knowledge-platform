@echo off
setlocal enabledelayedexpansion
rem ============================================================================
rem  Frontend browser gates runner -- used while GitHub Actions is unavailable
rem  (account quota / billing limit, see Issue #102 and
rem   docs/无CI期开发与验证计划.md).
rem
rem  WHY THIS SCRIPT EXISTS: these gates bind their own ports
rem  (4205 / 4209 / 4210). The agent's sandbox refuses to bind them
rem  (WinError 10013), so these readings can only be produced on this machine.
rem
rem  ASCII ONLY on purpose: a .cmd with non-ASCII comments is parsed as GBK by
rem  cmd.exe and can eat following lines (already happened once in this repo).
rem
rem  USAGE:  cmd /c "tools\run-frontend-gates.cmd"
rem  NEEDED: python on PATH, playwright installed, and the frontend dev server
rem          running on 5280 (start it yourself: %TEMP%\aip-fe-live.cmd).
rem  REPORT: .git\frontend-gates-report.txt   (never committed)
rem ============================================================================

set "ROOT=%~dp0.."
set "FE=%ROOT%\frontend"
set "REPORT=%ROOT%\.git\frontend-gates-report.txt"

if not exist "%FE%\package.json" (
  echo [ERROR] frontend folder not found at "%FE%"
  exit /b 1
)

> "%REPORT%" echo Frontend browser gates report
>> "%REPORT%" echo Started: %DATE% %TIME%
>> "%REPORT%" echo.

rem --- step 1: the 11 deterministic gates need the dev server on 5280 ----------
>> "%REPORT%" echo == probe dev server on 5280 ==
python -c "import urllib.request,sys;\
sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:5280',timeout=3).status<500 else 1)" 1>nul 2>>"%REPORT%"
if errorlevel 1 (
  echo [ERROR] frontend dev server is not answering on http://127.0.0.1:5280
  echo         start it first:  %TEMP%\aip-fe-live.cmd
  echo [ERROR] dev server not answering on 5280 >> "%REPORT%"
  echo Full report: %REPORT%
  exit /b 1
)
>> "%REPORT%" echo dev server OK on 5280

set "FAILED=0"
set "TOTAL=0"

cd /d "%FE%"
if errorlevel 1 (
  echo [ERROR] cannot cd into "%FE%"
  exit /b 1
)

rem --- step 2: 11 gates + 2 self-hosted ones ----------------------------------
for %%G in (check_theme check_nodeconfig check_node_menu check_canvas check_collapse ^
check_history check_palette check_pickers check_assistants check_singleton ^
check_publish verify_features smoke_test) do (
  set /a TOTAL+=1
  echo === %%G.py ===
  >> "%REPORT%" echo.
  >> "%REPORT%" echo === %%G.py ===
  python "%%G.py" >> "%REPORT%" 2>&1
  if errorlevel 1 (
    echo     FAIL  %%G.py
    >> "%REPORT%" echo RESULT: FAIL %%G.py
    set /a FAILED+=1
  ) else (
    echo     PASS  %%G.py
    >> "%REPORT%" echo RESULT: PASS %%G.py
  )
)

>> "%REPORT%" echo.
>> "%REPORT%" echo ================= SUMMARY =================
>> "%REPORT%" echo gates run: !TOTAL!   failed: !FAILED!
echo.
echo ==============================================
echo   gates run: !TOTAL!   failed: !FAILED!
echo ==============================================
echo Full report: %REPORT%
if !FAILED! GTR 0 exit /b 1
exit /b 0
