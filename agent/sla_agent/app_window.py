"""Opening the website like an app: Microsoft Edge's app mode shows it in its own window, without an address bar or
tabs and with its own taskbar button. Every Windows 10 and 11 has Edge; where it can't be found or started, the
default browser opens the website instead."""

import subprocess
import webbrowser
from pathlib import Path

EDGE = r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe"


def edge():
    """Where msedge.exe is, from Windows' App Paths, or None."""
    try:
        import winreg
    except ImportError:  # not Windows
        return None
    for root in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        try:
            with winreg.OpenKey(root, EDGE) as key:
                path = winreg.QueryValueEx(key, "")[0]
        except OSError:
            continue
        if path and Path(path).exists():
            return path
    return None


def open_app(address, find_edge=edge, start=subprocess.Popen, fallback=webbrowser.open):
    """Open the website at `address` (the one saved with this laptop's device key) as an app."""
    url = address.rstrip("/") + "/"
    program = find_edge()
    if program:
        try:
            start([program, f"--app={url}"])
            return
        except OSError:
            pass
    fallback(url)
