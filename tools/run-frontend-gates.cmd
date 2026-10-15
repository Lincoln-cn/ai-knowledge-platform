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
rem  ASCII + CRLF ONLY on purpose:
rem    - non-ASCII comments are parsed as GBK by cmd.exe and can eat the next
rem      line (that already happened once in this repo),
rem    - an LF-only .cmd is mis-read by cmd.exe (it once read
rem      "setlocal enabledelayedexpansion" as "ayedexpansion").
rem
rem  *** NEVER PROBE A SINGLE LITERAL ADDRESS ***
rem  Vite binds only ::1 (IPv6) on this machine, so a 127.0.0.1 probe gets
rem  WinError 10061 while the dev server is perfectly healthy -- that is what
rem  made this runner report "dev server is not answering" with the server up.
rem  All probing goes through tools\probe_http.py, which tries
rem  localhost / [::1] / 127.0.0.1 in turn (and bypasses HTTP proxies).
rem
rem  TWO KINDS OF GATES, TWO DIFFERENT PRECONDITIONS:
rem    1) the 11 browser gates -> need the frontend dev server on 5280
rem       (mock mode is fine; that is what .env.development defaults to)
rem    2) verify_features + smoke_test -> need the REAL stack: a backend on
rem       18088 (frontend/.env.development VITE_API_TARGET) + stub on 11434.
rem    Without the backend those 2 are SKIPPED and counted separately:
rem    a SKIPPED gate is a MISSING READING, never a pass.
rem
rem  USAGE:  cmd /c "tools\run-frontend-gates.cmd"
rem  NEEDED: python on PATH, playwright installed, dev server on 5280
rem          (scripts\aip-fe-live.cmd)
rem  REPORT: .git\frontend-gates-report.txt   (never committed)
rem ============================================================================

set "ROOT=%~dp0.."
set "FE=%ROOT%\frontend"
set "REPORT=%ROOT%\.git\frontend-gates-report.txt"
set "PROBE=%ROOT%\tools\probe_http.py"

if not exist "%FE%\package.json" (
  echo [ERROR] frontend folder not found at "%FE%"
  exit /b 1
)
if not exist "%PROBE%" (
  echo [ERROR] probe helper not found at "%PROBE%"
  exit /b 1
)

> "%REPORT%" echo Frontend browser gates report
>> "%REPORT%" echo Started: %DATE% %TIME%
>> "%REPORT%" echo.

rem --- step 1: the 11 browser gates need the dev server on 5280 -----------------
>> "%REPORT%" echo == probe dev server on 5280 (localhost / ::1 / 127.0.0.1) ==
python "%PROBE%" 5280 >> "%REPORT%" 2>&1
if errorlevel 1 (
  echo [ERROR] frontend dev server is not answering on 5280 -- tried localhost, ::1 and 127.0.0.1
  echo         start it in another window first:
  echo             scripts\aip-fe-live.cmd
  echo         or:  cd /d "%FE%"  ^&^&  npm run dev
  echo         NOTE: Vite binds ::1 only here -- 127.0.0.1 alone is always refused.
  echo [ERROR] dev server not answering on 5280 >> "%REPORT%"
  echo Full report: %REPORT%
  exit /b 1
)
>> "%REPORT%" echo dev server OK on 5280

rem --- step 2: do we have the real stack for the last 2 gates? -----------------
set "REALSTACK=1"
>> "%REPORT%" echo == probe backend on 18088/actuator/health ==
python "%PROBE%" 18088 --path /actuator/health >> "%REPORT%" 2>&1
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
    echo     SKIP  %%G.py   - no real stack
    >> "%REPORT%" echo RESULT: SKIPPED %%G.py -- backend 18088 not answering -- MISSING READING, not a pass
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
if !SKIPPED! GTR 0 echo   NOTE: !SKIPPED! gates SKIPPED = missing reading, not a pass
echo Full report: %REPORT%
if !FAILED! GTR 0 exit /b 1
exit /b 0
