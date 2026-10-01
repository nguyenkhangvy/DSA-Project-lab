"""The Desktop and Start menu shortcuts, made in a fake Windows shell (conftest.py): tests never touch the real ones."""

from pathlib import Path

from sla_agent import shortcuts

PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


def made(isolated_agent):
    return sorted(isolated_agent.shell.root.rglob("*.lnk"))


def test_make_puts_a_shortcut_on_the_desktop_and_in_the_start_menu(isolated_agent, tmp_path):
    shortcuts.make(PYTHONW, tmp_path / "home")

    desktop = isolated_agent.shell.root / "OneDrive" / "Máy tính" / "School-Life-Assistant.lnk"
    start_menu = isolated_agent.shell.root / "Start Menu" / "Programs" / "School-Life-Assistant.lnk"
    assert made(isolated_agent) == sorted([desktop, start_menu])
    for shortcut in (desktop, start_menu):
        assert shortcut.read_text(encoding="utf-8").splitlines() == [
            f"{PYTHONW} -m sla_agent window", str(tmp_path / "home")]


def test_make_again_replaces_them_and_remove_is_fine_when_they_are_gone(isolated_agent, tmp_path):
    shortcuts.make(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.make(PYTHONW, tmp_path)

    assert all(path.read_text(encoding="utf-8").startswith(PYTHONW) for path in made(isolated_agent))

    shortcuts.remove()
    shortcuts.remove()

    assert made(isolated_agent) == []


def test_the_project_folder_has_a_double_click_starter():
    starter = Path(__file__).resolve().parents[2] / "School-Life-Assistant.cmd"

    text = starter.read_text(encoding="utf-8")

    assert r'start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window' in text
