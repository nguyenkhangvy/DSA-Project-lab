"""`self-check <version>`: whether this built app has everything it needs (spec 2026-10-02-agent-exe-design.md, 3).
The setup runs it on a new app before using it, and CI and the release run it on every build. The app has no
console, so the answer is the exit code; the problems go to agent.log."""

import sys

from sla_agent import __version__


def tk_with_its_files():
    import tkinter

    tkinter.Tcl().eval("info patchlevel")  # Tcl had to find its own library files in the bundle


def outlook_link():
    import win32com.client  # noqa: F401  (pywin32: reads classic Outlook, makes the shortcuts)


def outlook_dates():
    """A COM date read, as each email's received time is: pywin32 needs win32timezone for it, a module it imports
    from C, where PyInstaller can't see it. Without it every email is skipped."""
    import pythoncom
    import win32com.client

    pythoncom.CoInitialize()
    win32com.client.Dispatch("Scripting.FileSystemObject").GetFile(sys.executable).DateCreated


def credential_manager():
    import keyring

    backend = type(keyring.get_keyring()).__name__
    if backend != "WinVaultKeyring":
        raise RuntimeError(f"keyring uses {backend}, not Windows Credential Manager")


def data_contract():
    from sla_contract.schema import FinishRun  # noqa: F401


def https_certificates():
    import ssl

    import certifi

    ssl.create_default_context(cafile=certifi.where())


def setup_window():
    import sla_agent.window  # noqa: F401  (the setup pages, the Outlook page and what they share come with it)


CHECKS = (tk_with_its_files, outlook_link, outlook_dates, credential_manager, data_contract, https_certificates,
          setup_window)


def problems(expected_version, checks=None):
    """What is wrong with this app, one sentence each; empty when it is fine."""
    found = [] if expected_version == __version__ else [f"this is version {__version__}, not {expected_version}"]
    for check in CHECKS if checks is None else checks:
        try:
            check()
        except Exception as error:  # any problem is an answer, never a crash
            found.append(f"{check.__name__}: {error.__class__.__name__}: {error}")
    return found
