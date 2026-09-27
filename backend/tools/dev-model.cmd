@echo off
rem ---------------------------------------------------------------------------
rem  Start the OpenAI-compatible model stub for local development.
rem
rem  It listens on 11434 by default, which is the same port a real Ollama uses,
rem  so application.yml's spring.ai.openai.base-url needs NO change - swapping
rem  in a real model service is just swapping which process runs.
rem
rem  Keep this file ASCII-only. cmd.exe reads .cmd in the OEM code page, so
rem  UTF-8 CJK comments would be parsed as commands and break the script.
rem
rem  Usage:
rem    dev-model.cmd                      start on 11434
rem    dev-model.cmd --delay-ms 300       simulate a slow upstream
rem    dev-model.cmd --fail-rate 0.3      inject 30% 500s (exercise retry/degrade)
rem    dev-model.cmd --dim 1024           change embedding dimension
rem
rem  See what the app actually sent:
rem    curl http://localhost:11434/__stub/requests
rem
rem  WARNING: this stub's outputs are SETTINGS, not measurements. Never use them
rem  as evidence for a model-capability conclusion - see docs/技术结论/M0-技术结论.md
rem ---------------------------------------------------------------------------
setlocal
python "%~dp0openai_stub.py" %*
