"""The "School-Life-Assistant" shortcuts on the Desktop and in the Start menu: they open the accounts window
(spec 2026-10-01-accounts-window-design.md, 4.3).

Made with Windows' own WScript.Shell (through pywin32), for this Windows user only. Tests replace `_shell`."""

from pathlib import Path

from sla_agent.launcher import arguments

NAME = "School-Life-Assistant.lnk"
DESCRIPTION = "Enter and change your School-Life-Assistant accounts"


def _shell():
    import pythoncom
    import win32com.client

    pythoncom.CoInitialize()  # COM is per thread, and Repair and the first-time form run on a worker thread
    return win32com.client.Dispatch("WScript.Shell")


def _places(shell):
    """This user's Desktop and Start menu Programs folders (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return [Path(shell.SpecialFolders("Desktop")), Path(shell.SpecialFolders("Programs"))]


def make(program, folder):
    """Make, or replace, both shortcuts: `program` opening the window (launcher.arguments), started in `folder`."""
    shell = _shell()
    for place in _places(shell):
        shortcut = shell.CreateShortcut(str(place / NAME))
        shortcut.TargetPath = str(program)
        shortcut.Arguments = arguments(program, "window")
        shortcut.WorkingDirectory = str(folder)
        shortcut.Description = DESCRIPTION
        shortcut.Save()


def remove():
    for place in _places(_shell()):
        (place / NAME).unlink(missing_ok=True)
