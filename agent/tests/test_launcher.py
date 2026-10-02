"""How the scheduled task, the links and the shortcuts start the agent (launcher.py): the built app, or a Python
running the source."""

import os
import sys
from pathlib import Path

import pytest

from sla_agent import cli, launcher, mail_link, shortcuts
from sla_agent.scheduler import task_xml

APP = r"C:\Users\Nguyễn Văn An\AppData\Local\SchoolLifeAssistant\app\School-Life-Assistant.exe"
PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


def test_the_app_is_started_with_just_the_command():
    assert launcher.arguments(APP, "run") == "run"


def test_a_python_runs_the_agent_as_a_module():
    assert launcher.arguments(PYTHONW, "run") == "-m sla_agent run"


def test_from_source_the_program_is_the_windowless_python_beside_this_one(monkeypatch, tmp_path):
    (tmp_path / "python.exe").write_text("")
    (tmp_path / "pythonw.exe").write_text("")
    monkeypatch.setattr(sys, "executable", str(tmp_path / "python.exe"))
    monkeypatch.delattr(sys, "frozen", raising=False)

    assert not launcher.frozen()
    assert launcher.program() == str(tmp_path / "pythonw.exe")


def test_without_a_windowless_python_the_running_one_is_used(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "executable", str(tmp_path / "python3"))
    monkeypatch.delattr(sys, "frozen", raising=False)

    assert launcher.program() == str(tmp_path / "python3")


def test_the_built_app_is_its_own_program(monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", APP)

    assert launcher.frozen()
    assert launcher.program() == APP


def test_the_task_the_links_and_the_shortcuts_start_the_app_without_python(isolated_agent, tmp_path):
    assert f"<Command>{APP}</Command>" in task_xml(APP, "LAPTOP\\An")
    assert "<Arguments>run</Arguments>" in task_xml(APP, "LAPTOP\\An")
    assert mail_link.command(APP) == f'"{APP}" open-mail "%1"'
    assert mail_link.window_command(APP) == f'"{APP}" window "%1"'

    shortcuts.make(APP, tmp_path)
    shortcuts.add_desktop(APP, tmp_path)

    made = sorted((path.name, path.read_text(encoding="utf-8").splitlines()[0])
                  for path in isolated_agent.shell.root.rglob("*.lnk"))
    assert made == [("School-Life-Assistant Accounts.lnk", f"{APP} window"),
                    ("School-Life-Assistant.lnk", f"{APP} open"), ("School-Life-Assistant.lnk", f"{APP} open")]


# ---- the built app holds its own folder (review C1) -------------------------------------------


def built_app_in(monkeypatch, folder):
    """This process as the built app installed in `folder`, holding nothing yet."""
    folder.mkdir()
    (folder / "version.txt").write_text("0.2.0", encoding="utf-8")
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(folder / launcher.APP_EXE))
    monkeypatch.setattr(launcher, "_held", None)


def test_the_built_app_keeps_its_version_file_open_while_it_runs(monkeypatch, tmp_path):
    built_app_in(monkeypatch, tmp_path / "app")

    held = launcher.hold_app_folder()
    try:
        assert not held.closed
        assert Path(held.name) == tmp_path / "app" / "version.txt"
    finally:
        held.close()


def test_from_source_nothing_is_held(monkeypatch):
    monkeypatch.delattr(sys, "frozen", raising=False)
    monkeypatch.setattr(launcher, "_held", None)

    assert launcher.hold_app_folder() is None


@pytest.mark.skipif(sys.platform != "win32", reason="Windows' rule: a folder with an open file can't be renamed")
def test_while_the_built_app_runs_windows_refuses_to_swap_its_folder(monkeypatch, tmp_path):
    built_app_in(monkeypatch, tmp_path / "app")

    held = launcher.hold_app_folder()
    try:
        with pytest.raises(PermissionError):
            os.rename(tmp_path / "app", tmp_path / "app.old")
    finally:
        held.close()
    os.rename(tmp_path / "app", tmp_path / "app.old")  # once it has ended, the setup can swap it


def test_every_command_of_the_built_app_holds_its_folder_first(monkeypatch):
    held = []
    monkeypatch.setattr(launcher, "hold_app_folder", lambda: held.append("held"))
    monkeypatch.setitem(cli.COMMANDS, "status", lambda args: 0)

    assert cli.main(["status"]) == 0
    assert held == ["held"]
