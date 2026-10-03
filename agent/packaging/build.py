"""Builds the app and the setup on Windows (spec 2026-10-02-agent-exe-design.md, 3):

    python agent\\packaging\\build.py

dist\\School-Life-Assistant\\       the app: a folder whose School-Life-Assistant.exe starts without unpacking
dist\\School-Life-Assistant.exe     the setup students download; it carries the app as app.zip
dist\\SHA256SUMS.txt                the setup's checksum, which the agent's update compares

Needs `pip install -r requirements-dev.txt -r agent/packaging/requirements.txt`. smoke-test.ps1 starts the result."""

import hashlib
import os
import shutil
from pathlib import Path

import PyInstaller.__main__
from PyInstaller.utils.win32.versioninfo import (
    FixedFileInfo,
    StringFileInfo,
    StringStruct,
    StringTable,
    VarFileInfo,
    VarStruct,
    VSVersionInfo,
)

from sla_agent import __version__

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DIST = ROOT / "dist"
WORK = ROOT / "build"
NAME = "School-Life-Assistant"


def version_file(kind, description):
    """Windows' version details for one .exe (Properties → Details), in PyInstaller's version-file format."""
    numbers = (*(int(part) for part in __version__.split(".")), 0)
    info = VSVersionInfo(
        ffi=FixedFileInfo(filevers=numbers, prodvers=numbers),
        kids=[
            StringFileInfo([StringTable("040904B0", [
                StringStruct("CompanyName", "School-Life-Assistant, an IU student project"),
                StringStruct("FileDescription", description),
                StringStruct("FileVersion", __version__),
                StringStruct("InternalName", NAME),
                StringStruct("OriginalFilename", f"{NAME}.exe"),
                StringStruct("ProductName", NAME),
                StringStruct("ProductVersion", __version__),
            ])]),
            VarFileInfo([VarStruct("Translation", [0x0409, 1200])]),
        ])
    path = WORK / f"version-{kind}.txt"
    path.write_text(str(info), encoding="utf-8")
    return path


def pyinstaller(entry, kind, *options):
    PyInstaller.__main__.run([
        str(HERE / entry), "--name", NAME, "--windowed", "--noupx", "--noconfirm",
        "--distpath", str(DIST), "--workpath", str(WORK / kind), "--specpath", str(WORK / kind),
        "--paths", str(ROOT / "agent"), "--paths", str(ROOT / "contract"), *options])


def main():
    for folder in (DIST, WORK):
        shutil.rmtree(folder, ignore_errors=True)
    WORK.mkdir(parents=True)

    # win32timezone: pywin32 imports it from C for every COM date (each email's received time), unseen by PyInstaller.
    pyinstaller("app_entry.py", "app", "--onedir", "--hidden-import", "win32timezone", "--version-file",
                str(version_file("app", "School-Life-Assistant: syncs EduSoft, Blackboard, IUPay and Outlook")))
    app = DIST / NAME
    (app / "version.txt").write_text(__version__, encoding="utf-8")

    archive = shutil.make_archive(str(WORK / "app"), "zip", root_dir=app)
    pyinstaller("setup_entry.py", "setup", "--onefile", "--add-data", f"{archive}{os.pathsep}.",
                "--version-file", str(version_file("setup", "School-Life-Assistant setup")))

    setup = DIST / f"{NAME}.exe"
    digest = hashlib.sha256(setup.read_bytes()).hexdigest()
    # LF even on Windows: `sha256sum -c` reads a CR as part of the file name.
    (DIST / "SHA256SUMS.txt").write_text(f"{digest}  {setup.name}\n", encoding="utf-8", newline="\n")
    print(f"Built School-Life-Assistant {__version__} in {DIST}")


if __name__ == "__main__":
    main()
