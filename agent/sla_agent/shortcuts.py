"""The "School-Life-Assistant" shortcuts on the Desktop and in the Start menu: they open the accounts window
(spec 2026-10-01-accounts-window-design.md, 4.3).

Made with Windows' own WScript.Shell (through pywin32), for this Windows user only. Tests replace `_shell`."""

from pathlib import Path

NAME = "School-Life-Assistant.lnk"
ARGUMENTS = "-m sla_agent window"
DESCRIPTION = "Enter and change your School-Life-Assistant accounts"


def _shell():
    import win32com.client

    return win32com.client.Dispatch("WScript.Shell")


def _places(shell):
    """This user's Desktop and Start menu Programs folders (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return [Path(shell.SpecialFolders("Desktop")), Path(shell.SpecialFolders("Programs"))]


def make(python_exe, folder):
    """Make, or replace, both shortcuts: `python_exe -m sla_agent window`, started in `folder`."""
    shell = _shell()
    for place in _places(shell):
        shortcut = shell.CreateShortcut(str(place / NAME))
        shortcut.TargetPath = str(python_exe)
        shortcut.Arguments = ARGUMENTS
        shortcut.WorkingDirectory = str(folder)
        shortcut.Description = DESCRIPTION
        shortcut.Save()


def remove():
    for place in _places(_shell()):
        (place / NAME).unlink(missing_ok=True)
