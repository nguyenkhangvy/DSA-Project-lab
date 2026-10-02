"""The School-Life-Assistant shortcuts (spec 2026-10-01-accounts-window-design.md, 4.3; spec
2026-10-02-easy-install-design.md, 5): "School-Life-Assistant" in the Start menu opens the website as an app
(`open`), and "School-Life-Assistant Accounts" in the Start menu opens the accounts window (`window`), through the
built app or a windowless Python (launcher.py). The Desktop icon is the student's choice: only add_desktop makes one,
and one they deleted stays deleted.

Made with Windows' own WScript.Shell (through pywin32), for this Windows user only. Tests replace `_shell`."""

from pathlib import Path

from sla_agent.launcher import arguments

APP = "School-Life-Assistant.lnk"
ACCOUNTS = "School-Life-Assistant Accounts.lnk"
OPEN_DESCRIPTION = "Open School-Life-Assistant"
ACCOUNTS_DESCRIPTION = "Enter and change your School-Life-Assistant accounts"


def _shell():
    import pythoncom
    import win32com.client

    pythoncom.CoInitialize()  # COM is per thread, and Repair and the setup pages save on a worker thread
    return win32com.client.Dispatch("WScript.Shell")


def _desktop(shell):
    """The Desktop icon's path (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return Path(shell.SpecialFolders("Desktop")) / APP


def _start_menu(shell):
    """(path, command, description) of the Start menu entries: the app, and Accounts."""
    programs = Path(shell.SpecialFolders("Programs"))
    return [(programs / APP, "open", OPEN_DESCRIPTION), (programs / ACCOUNTS, "window", ACCOUNTS_DESCRIPTION)]


def _save(shell, path, program, command, description, folder):
    shortcut = shell.CreateShortcut(str(path))
    shortcut.TargetPath = str(program)
    shortcut.Arguments = arguments(program, command)
    shortcut.WorkingDirectory = str(folder)
    shortcut.Description = description
    shortcut.Save()


def make(program, folder):
    """Make, or replace, the Start menu entries, each starting `program` (launcher.arguments) in `folder`, and point
    the Desktop icon at `program` too when it is there. Never adds a Desktop icon."""
    shell = _shell()
    for path, command, description in _start_menu(shell):
        _save(shell, path, program, command, description, folder)
    desktop = _desktop(shell)
    if desktop.exists():
        _save(shell, desktop, program, "open", OPEN_DESCRIPTION, folder)


def add_desktop(program, folder):
    """The School-Life-Assistant icon on the Desktop, starting `program` in `folder`: the student asked for it."""
    shell = _shell()
    _save(shell, _desktop(shell), program, "open", OPEN_DESCRIPTION, folder)


def on_desktop():
    """Whether the School-Life-Assistant icon is on this user's Desktop."""
    return _desktop(_shell()).exists()


def remove():
    shell = _shell()
    for path in [_desktop(shell), *(path for path, _, _ in _start_menu(shell))]:
        path.unlink(missing_ok=True)
