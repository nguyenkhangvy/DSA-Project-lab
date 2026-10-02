"""How the scheduled task, the sla-mail: and sla-agent: links and the shortcuts start this agent
(spec 2026-10-02-agent-exe-design.md, 5): the installed app's School-Life-Assistant.exe, or, from source, a
windowless Python with `-m sla_agent`."""

import sys
from pathlib import Path, PureWindowsPath

APP_EXE = "School-Life-Assistant.exe"
_held = None  # the app's open version.txt (hold_app_folder)


def frozen():
    """Whether this is the built app (PyInstaller sets sys.frozen), not a Python running the source."""
    return bool(getattr(sys, "frozen", False))


def program():
    """What to start: this app's .exe, or pythonw.exe beside the running Python (no console window every minute)."""
    if frozen():
        return sys.executable
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    return str(pythonw if pythonw.exists() else Path(sys.executable))


def hold_app_folder():
    """While the built app runs, keep the version.txt beside its .exe open, so Windows refuses to rename its folder and
    the setup (installer.py) waits instead of swapping a new app in under a running one. A running program's own
    .exe and DLLs don't stop a rename; an open file does. Returns the open file, or None from source or when the
    file is missing."""
    global _held
    if frozen() and _held is None:
        try:
            _held = open(Path(sys.executable).with_name("version.txt"), "rb")  # noqa: SIM115 (held until the end)
        except OSError:
            pass
    return _held


def arguments(program, command):
    """What follows `program` on the command line: "run" for the app, "-m sla_agent run" for a Python."""
    return command if PureWindowsPath(program).name.lower() == APP_EXE.lower() else f"-m sla_agent {command}"
