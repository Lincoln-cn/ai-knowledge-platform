@echo off
setlocal enabledelayedexpansion
rem ============================================================================
rem  Frontend gates runner -- used while GitHub Actions is unavailable
rem  (account quota / billing limit: Issue #102, plan under docs/).
rem
rem  WHAT IT RUNS (13 gates, two families with different preconditions):
rem    A) 11 browser gates  -> need the dev server on 5280
rem       (mock mode is what .env.development defaults to)
rem    B) verify_features + smoke_test -> serve their own copy of dist/,
rem       so they need a FRESH dist/ (npm run build:mock) and NO backend:
rem       in CI they run in the frontend job right after npm run build:mock.
rem       smoke_test refuses to run on a stale dist on purpose.
rem
rem  ASCII + CRLF ONLY: non-ASCII comments are parsed as GBK by cmd.exe and can
rem  eat the next line; an LF-only .cmd is mis-read (it once read
rem  "setlocal enabledelayedexpansion" as "ayedexpansion").
rem
rem  *** THREE TRAPS THIS SCRIPT NOW AVOIDS (all three were shipped once) ***
rem  1) NEVER probe a single literal address: Vite binds only ::1 here, so a
rem     127.0.0.1 probe says "not answering" while the server is healthy.
rem     Probing goes through tools\probe_http.py which tries
rem     localhost / [::1] / 127.0.0.1 and bypasses HTTP proxies.
rem  2) NEVER put an unescaped closing paren in echo text inside an if block:
rem     cmd closes the block early and the rest of the block runs
rem     unconditionally -- the symptom is "always fails, message truncated".
rem  3) ports 4205/4209/4210 sit inside this machine Windows excluded port
rem     range 3788-4287, so bind fails with errno 13 for every user.
rem     The two family-B gates therefore run on 14205/14209 via
rem     AIP_GATE_PORT. Their built-in default is unchanged, so CI is unaffected.
rem
rem  USAGE:  cmd /c "tools\run-frontend-gates.cmd"
rem  REPORT: .git\frontend-gates-report.txt   (never committed)
rem ============================================================================

set "ROOT=%~dp0.."
set "FE=%ROOT%\frontend"
set "REPORT=%ROOT%\.git\frontend-gates-report.txt"
set "PROBE=%ROOT%\tools\probe_http.py"
set "GATE_PORT_A=14205"
set "GATE_PORT_B=14209"

if not exist "%FE%\package.json" (
  echo [ERROR] frontend folder not found at "%FE%"
  exit /b 1
)
if not exist "%PROBE%" (
  echo [ERROR] probe helper not found at "%PROBE%"
  exit /b 1
)

> "%REPORT%" echo Frontend gates report
>> "%REPORT%" echo Started: %DATE% %TIME%
>> "%REPORT%" echo.

rem --- step 1: can we run family A? -------------------------------------------
set "DEVUP=1"
>> "%REPORT%" echo == probe dev server on 5280 ==
python "%PROBE%" 5280 >> "%REPORT%" 2>&1
if errorlevel 1 (
  set "DEVUP=0"
  >> "%REPORT%" echo dev server not answering on 5280 -- the 11 browser gates will be SKIPPED
  echo [WARN] dev server not answering on 5280 -- the 11 browser gates will be SKIPPED.
  echo        start it first:  scripts\aip-fe-live.cmd
  echo        NOTE: Vite binds ::1 only here, so 127.0.0.1 alone is always refused.
  echo        A SKIPPED gate is a MISSING READING -- never read it as green.
)

set "FAILED=0"
set "TOTAL=0"
set "SKIPPED=0"

cd /d "%FE%"
if errorlevel 1 (
  echo [ERROR] cannot cd into "%FE%"
  exit /b 1
)

rem --- step 2: family A -- the 11 browser gates -------------------------------
for %%G in (check_theme check_nodeconfig check_node_menu check_canvas check_collapse ^
check_history check_palette check_pickers check_assistants check_singleton ^
check_publish) do (
  set /a TOTAL+=1
  echo === %%G.py ===
  >> "%REPORT%" echo.
  >> "%REPORT%" echo === %%G.py ===
  if "!DEVUP!"=="0" (
    echo     SKIP  %%G.py   - no dev server
    >> "%REPORT%" echo RESULT: SKIPPED %%G.py -- dev server not answering on 5280 -- MISSING READING
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

rem --- step 3: family B needs a fresh dist/ (mock build), so build once -------
>> "%REPORT%" echo.
>> "%REPORT%" echo == npm run build:mock ==
echo === build:mock ===
call npm run build:mock >> "%REPORT%" 2>&1
if errorlevel 1 (
  set "DISTOK=0"
  >> "%REPORT%" echo build:mock FAILED -- the 2 self-hosted gates will be SKIPPED
  echo [WARN] npm run build:mock failed -- verify_features + smoke_test will be SKIPPED.
) else (
  set "DISTOK=1"
  >> "%REPORT%" echo build:mock OK
)

rem --- step 4: family B -- the 2 self-hosted gates on non-reserved ports ------
>> "%REPORT%" echo.
>> "%REPORT%" echo == self-hosted gates on AIP_GATE_PORT %GATE_PORT_A% / %GATE_PORT_B% ==
set "AIP_GATE_PORT=%GATE_PORT_A%"
for %%G in (verify_features smoke_test) do (
  set /a TOTAL+=1
  echo === %%G.py ===
  >> "%REPORT%" echo.
  >> "%REPORT%" echo === %%G.py ===
  if "!DISTOK!"=="0" (
    echo     SKIP  %%G.py   - no dist build
    >> "%REPORT%" echo RESULT: SKIPPED %%G.py -- build:mock failed -- MISSING READING
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
  set "AIP_GATE_PORT=%GATE_PORT_B%"
)
set "AIP_GATE_PORT="

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
