"""The setup: School-Life-Assistant.exe as students download it (spec 2026-10-02-agent-exe-design.md, 4).

It carries the app (app.zip, a folder build that starts without unpacking anything) and installs it into
%LOCALAPPDATA%\\SchoolLifeAssistant\\app:

    School-Life-Assistant.exe                  double-clicked: install, then open School-Life-Assistant
    School-Life-Assistant.exe --update PID     started by the agent's update (update.py): wait for that process
                                               to end, then install with no window and no messages

A new app is unpacked into app.new and used only once its own `self-check` passes; then app is renamed to app.old
and app.new to app. Windows refuses to rename a folder while a file in it is open, and every running app keeps its
version.txt open (launcher.hold_app_folder), so the swap is tried again for a while. Any failure leaves the old app
as it was. Standard library only, so the setup stays small."""

import logging
import os
import shutil
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from sla_agent import __version__
from sla_agent.state import agent_home
from sla_agent.versions import is_newer, parse

log = logging.getLogger(__name__)

APP, NEW, OLD = "app", "app.new", "app.old"
EXE = "School-Life-Assistant.exe"
VERSION_FILE = "version.txt"
INSTALL_WAIT = 60  # seconds a double-clicked install keeps trying the swap
UPDATE_WAIT = 600  # an update waits longer: a full sync can take a few minutes
RETRY_EVERY = 5
SELF_CHECK_SECONDS = 120
TITLE = "School-Life-Assistant"
MUTEX = "SchoolLifeAssistant-Setup"  # one setup at a time
ERROR_ALREADY_EXISTS = 183
BUSY = ("School-Life-Assistant is being installed or updated right now. Wait a minute, then run this file again "
        "if its window hasn't opened.")
DID_NOT_START = ("The new School-Life-Assistant didn't start correctly, so nothing was changed. "
                 "Download it again from School → Devices on the website.")
STILL_RUNNING = "School-Life-Assistant is still running. Close its window, then run this file again."
WENT_WRONG = ("Something went wrong while installing School-Life-Assistant. The details are in setup.log in "
              "%LOCALAPPDATA%\\SchoolLifeAssistant.")


@dataclass
class Machine:
    """What the setup does to the laptop; tests pass fakes."""

    unpack: Callable  # (folder): unpack the app this setup carries into `folder`
    self_check: Callable  # (exe, version) -> whether `exe self-check version` exits with 0
    wait_for_exit: Callable  # (process id, seconds)
    start: Callable  # ([program, argument, ...]): start it and don't wait
    tell: Callable  # (message): a message box
    claim: Callable  # () -> whether no other setup is running; this one then stays the only one until it ends
    sleep: Callable = time.sleep
    rename: Callable = os.rename
    clock: Callable = time.monotonic


def installed_version(app):
    """The version in app\\version.txt, or None when there is no readable one."""
    try:
        text = (app / VERSION_FILE).read_text(encoding="utf-8").strip()
        parse(text)
        return text
    except (OSError, ValueError):
        return None


def install(home, version, machine, update_of=None):
    """Install the app this setup carries (`version`) into home\\app. `update_of` is the process id of the agent
    that started an update: no window and no messages then. Returns the exit code."""
    app, new, old = home / APP, home / NEW, home / OLD
    quiet = update_of is not None
    if not quiet and _keep_installed(app, version, machine):
        machine.start([str(app / EXE), "open"])
        return 0
    for leftover in (new, old):
        shutil.rmtree(leftover, ignore_errors=True)
    machine.unpack(new)
    if not machine.self_check(new / EXE, version):
        shutil.rmtree(new, ignore_errors=True)
        return _failed(machine, quiet, DID_NOT_START)
    if quiet:
        machine.wait_for_exit(update_of, UPDATE_WAIT)
    if not _swap(app, new, old, machine, UPDATE_WAIT if quiet else INSTALL_WAIT):
        shutil.rmtree(new, ignore_errors=True)
        return _failed(machine, quiet, STILL_RUNNING)
    shutil.rmtree(old, ignore_errors=True)
    log.info("Installed %s", version)
    if not quiet:
        machine.start([str(app / EXE), "open"])
    return 0


def _keep_installed(app, version, machine):
    """Whether a double-clicked setup should just open the installed app: it is newer (an old download double-clicked
    again), or it is this version and still passes its self-check."""
    installed = installed_version(app)
    if installed is None or is_newer(version, installed):
        return False
    return parse(installed) != parse(version) or machine.self_check(app / EXE, version)


def _swap(app, new, old, machine, seconds):
    """app → app.old, then app.new → app, each tried again for up to `seconds` while something holds a file in it (a
    running app, or the antivirus scanning the new files). False when either never worked: app is then as it was
    (app.old is renamed back)."""
    if app.exists() and not _rename_when_free(app, old, machine, seconds):
        return False
    if not _rename_when_free(new, app, machine, seconds):
        if old.exists() and not app.exists():
            machine.rename(old, app)
        return False
    return True


def _rename_when_free(source, target, machine, seconds):
    deadline = machine.clock() + seconds
    while True:
        try:
            machine.rename(source, target)
            return True
        except OSError as error:
            if machine.clock() >= deadline:
                log.warning("Couldn't rename %s: %s", source.name, error)
                return False
            machine.sleep(RETRY_EVERY)


def _failed(machine, quiet, message):
    log.warning(message)
    if not quiet:
        machine.tell(message)
    return 1


# ---- the real laptop -----------------------------------------------------------------------


def _carried_app():
    """app.zip, which agent/packaging/build.py puts inside this setup (PyInstaller unpacks it to sys._MEIPASS)."""
    return Path(getattr(sys, "_MEIPASS", ".")) / "app.zip"


def _unpack(folder):
    with zipfile.ZipFile(_carried_app()) as archive:
        archive.extractall(folder)


def _self_check(exe, version):
    try:
        return subprocess.run([str(exe), "self-check", version], cwd=exe.parent.parent,
                              timeout=SELF_CHECK_SECONDS).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def _wait_for_exit(process_id, seconds):
    """Wait until that process has ended, or `seconds`. (Not os.kill: on Windows it ends the process.)"""
    import ctypes

    synchronize = 0x00100000
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(synchronize, False, process_id)
    if not handle:  # already gone
        return
    try:
        kernel32.WaitForSingleObject(handle, int(seconds * 1000))
    finally:
        kernel32.CloseHandle(handle)


def _start(command):
    subprocess.Popen(command, cwd=Path(command[0]).parent.parent, close_fds=True)


def _tell(message):
    import ctypes

    ctypes.windll.user32.MessageBoxW(None, message, TITLE, 0x30)  # MB_ICONWARNING


_claimed = []  # the named mutex, held until this setup ends


def _claim():
    """Whether no other setup is running (a second double-click while this one works, or an update meanwhile)."""
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    handle = kernel32.CreateMutexW(None, False, MUTEX)
    if handle and ctypes.get_last_error() == ERROR_ALREADY_EXISTS:
        return False
    _claimed.append(handle)
    return True


def windows():
    return Machine(unpack=_unpack, self_check=_self_check, wait_for_exit=_wait_for_exit, start=_start, tell=_tell,
                   claim=_claim)


def main(argv=None, machine=None):
    """The setup's entry point (agent/packaging/setup_entry.py). Returns the exit code."""
    argv = sys.argv[1:] if argv is None else argv
    update_of = int(argv[1]) if len(argv) == 2 and argv[0] == "--update" and argv[1].isdigit() else None
    machine = machine or windows()
    home = agent_home()
    home.mkdir(parents=True, exist_ok=True)
    os.chdir(home)  # never work from inside app\, which is renamed
    handler = logging.FileHandler(home / "setup.log", encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    try:
        log.info("Setup %s: %s", __version__,
                 "install" if update_of is None else f"update after process {update_of} ends")
        if not machine.claim():
            log.info("Another setup is running; this one stops")
            if update_of is None:
                machine.tell(BUSY)
            return 1
        return install(home, __version__, machine, update_of)
    except Exception:  # anything unexpected: the old app stays, and setup.log says why
        log.exception("Setup failed")
        if update_of is None:
            machine.tell(WENT_WRONG)
        return 1
    finally:
        log.removeHandler(handler)
        handler.close()
