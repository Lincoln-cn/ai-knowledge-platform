@echo off
setlocal enabledelayedexpansion
rem ============================================================================
rem  Frontend browser gates runner -- used while GitHub Actions is unavailable
rem  (account quota / billing limit, see Issue #102 and the no-CI development
rem   and verification plan under docs/).
rem
rem  WHY THIS SCRIPT EXISTS: these gates bind their own ports
rem  (4205 / 4209 / 4210). The agent sandbox refuses to bind them
rem  (WinError 10013), so these readings can only be produced on this machine.
rem
rem  ASCII ONLY on purpose: a .cmd with non-ASCII comments is parsed as GBK by
rem  cmd.exe and can eat following lines (already happened once in this repo).
rem  CRLF ONLY on purpose: an LF-only .cmd is mis-parsed by cmd.exe
rem  (it read "setlocal enabledelayedexpansion" as "ayedexpansion" once).
rem
rem  TWO KINDS OF GATES, TWO DIFFERENT PRECONDITIONS:
rem    1) the 11 browser gates -> need the frontend dev server on 5280
rem       (mock mode is fine; that is what .env.development defaults to)
rem    2) verify_features + smoke_test -> need the REAL stack: a backend on
rem       18088 (which is what frontend/.env.development points at via
rem       VITE_API_TARGET) plus the model stub on 11434.
rem    When the backend is not up the 2 real-stack gates are SKIPPED and the
rem    report says so -- a SKIPPED gate is a MISSING READING, not a pass.
rem
rem  USAGE:  cmd /c "tools\run-frontend-gates.cmd"
rem  NEEDED: python on PATH, playwright installed, frontend dev server on 5280
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

rem --- step 1: the 11 browser gates need the dev server on 5280 -----------------
>> "%REPORT%" echo == probe dev server on 5280 ==
rem *** EVERY inline python must stay on ONE line: cmd.exe does NOT honour the
rem *** backslash line continuation -- it passes a literal backslash to python
rem *** and you get "SyntaxError: unexpected EOF while parsing" instead of a
rem *** probe result. That bug shipped once; do not re-wrap these lines.
python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:5280',timeout=3).status<500 else 1)" 1>nul 2>>"%REPORT%"
if errorlevel 1 (
  echo [ERROR] frontend dev server is not answering on http://127.0.0.1:5280
  echo         start it in another window first:
  echo             cd /d "%FE%"
  echo             npm run dev
  echo         or click %TEMP%\aip-fe-live.cmd if that launcher exists
  echo [ERROR] dev server not answering on 5280 >> "%REPORT%"
  echo Full report: %REPORT%
  exit /b 1
)
>> "%REPORT%" echo dev server OK on 5280

rem --- step 2: do we have the real stack for the last 2 gates? -----------------
set "REALSTACK=1"
>> "%REPORT%" echo == probe backend on 18088 ==
python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:18088/actuator/health',timeout=3).status<500 else 1)" 1>nul 2>>"%REPORT%"
if errorlevel 1 (
  set "REALSTACK=0"
  >> "%REPORT%" echo backend not answering on 18088 -- the 2 real-stack gates will be SKIPPED
  echo [WARN] backend not answering on 18088.
  echo        verify_features.py + smoke_test.py need the REAL stack and will be SKIPPED.
  echo        A skipped gate is a MISSING READING -- write it down, never read it as green.
)

set "FAILED=0"
set "TOTAL=0"
set "SKIPPED=0"

cd /d "%FE%"
if errorlevel 1 (
  echo [ERROR] cannot cd into "%FE%"
  exit /b 1
)

rem --- step 3: the 11 browser gates --------------------------------------------
for %%G in (check_theme check_nodeconfig check_node_menu check_canvas check_collapse ^
check_history check_palette check_pickers check_assistants check_singleton ^
check_publish) do (
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

rem --- step 4: the 2 self-hosted gates (real stack) ----------------------------
for %%G in (verify_features smoke_test) do (
  set /a TOTAL+=1
  echo === %%G.py ===
  >> "%REPORT%" echo.
  >> "%REPORT%" echo === %%G.py ===
  if "!REALSTACK!"=="0" (
    echo     SKIP  %%G.py   (no real stack)
    >> "%REPORT%" echo RESULT: SKIPPED %%G.py -- backend 18088 not answering (MISSING READING, not a pass)
    set /a SKIPPED+=1
  ) else (
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
)

>> "%REPORT%" echo.
>> "%REPORT%" echo ================= SUMMARY =================
>> "%REPORT%" echo gates run: !TOTAL!   failed: !FAILED!   skipped: !SKIPPED!
echo.
echo ==============================================
echo   gates run: !TOTAL!   failed: !FAILED!   skipped: !SKIPPED!
echo ==============================================
if !SKIPPED! GTR 0 echo   NOTE: !SKIPPED! gate(s) SKIPPED = missing reading, not a pass
echo Full report: %REPORT%
if !FAILED! GTR 0 exit /b 1
exit /b 0
