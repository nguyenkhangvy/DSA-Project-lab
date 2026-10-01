"""How the scheduled task, the links and the shortcuts start the agent (launcher.py): the built app, or a Python
running the source."""

import sys

from sla_agent import launcher, mail_link, shortcuts
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

    made = list(isolated_agent.shell.root.rglob("*.lnk"))
    assert len(made) == 2
    assert all(path.read_text(encoding="utf-8").startswith(f"{APP} window\n") for path in made)
