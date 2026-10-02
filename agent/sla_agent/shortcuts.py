"""The School-Life-Assistant shortcuts (spec 2026-10-01-accounts-window-design.md, 4.3): "School-Life-Assistant" on
the Desktop and in the Start menu opens the website as an app (`open`), and "School-Life-Assistant Accounts" in the
Start menu opens the accounts window (`window`), through the built app or a windowless Python (launcher.py).

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

    pythoncom.CoInitialize()  # COM is per thread, and Repair and the first-time form run on a worker thread
    return win32com.client.Dispatch("WScript.Shell")


def _places(shell):
    """This user's Desktop and Start menu Programs folders (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return [Path(shell.SpecialFolders("Desktop")), Path(shell.SpecialFolders("Programs"))]


def _shortcuts(shell):
    """(path, command, description) of each shortcut: the app on the Desktop and in the Start menu, Accounts in the
    Start menu."""
    desktop, programs = _places(shell)
    return [(desktop / APP, "open", OPEN_DESCRIPTION), (programs / APP, "open", OPEN_DESCRIPTION),
            (programs / ACCOUNTS, "window", ACCOUNTS_DESCRIPTION)]


def make(program, folder):
    """Make, or replace, the shortcuts, each starting `program` (launcher.arguments) in `folder`."""
    shell = _shell()
    for path, command, description in _shortcuts(shell):
        shortcut = shell.CreateShortcut(str(path))
        shortcut.TargetPath = str(program)
        shortcut.Arguments = arguments(program, command)
        shortcut.WorkingDirectory = str(folder)
        shortcut.Description = description
        shortcut.Save()


def remove():
    for path, _, _ in _shortcuts(_shell()):
        path.unlink(missing_ok=True)
