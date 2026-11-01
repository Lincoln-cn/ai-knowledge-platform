@echo off
rem ---------------------------------------------------------------------------
rem tools\branch-hygiene.cmd -- launcher for tools\branch_hygiene.py
rem
rem ASCII only + CRLF line endings are REQUIRED (see scripts\README.md):
rem cmd.exe reads a .cmd byte-wise, non-ASCII comments get parsed as GBK and can
rem swallow the following lines -- this repo has been bitten by that before.
rem ---------------------------------------------------------------------------
setlocal
where python >nul 2>nul
if errorlevel 1 (
  echo [branch-hygiene] python not found in PATH
  exit /b 2
)
python "%~dp0branch_hygiene.py" %*
exit /b %ERRORLEVEL%
