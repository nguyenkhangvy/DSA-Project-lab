@echo off
rem Opens the School-Life-Assistant window (docs/superpowers/specs/2026-10-01-accounts-window-design.md).
rem Double-click it in File Explorer; the window opens without a console.
if not exist "%~dp0.venv\Scripts\pythonw.exe" (
  echo sla-agent isn't installed in this folder yet. Follow "The laptop agent" in README.md first.
  pause
  exit /b 1
)
start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window
