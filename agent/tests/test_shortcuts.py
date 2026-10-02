"""The Start menu entries and the Desktop icon, made in a fake Windows shell (conftest.py): tests never touch the real
ones."""

from pathlib import Path

from sla_agent import shortcuts

PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


def made(isolated_agent):
    return sorted(isolated_agent.shell.root.rglob("*.lnk"))


def places(isolated_agent):
    """(the Desktop icon, the app in the Start menu, Accounts in the Start menu), where the fake shell keeps them."""
    root = isolated_agent.shell.root
    return (root / "OneDrive" / "Máy tính" / "School-Life-Assistant.lnk",
            root / "Start Menu" / "Programs" / "School-Life-Assistant.lnk",
            root / "Start Menu" / "Programs" / "School-Life-Assistant Accounts.lnk")


def test_make_puts_the_app_and_accounts_in_the_start_menu_but_no_icon_on_the_desktop(isolated_agent, tmp_path):
    _, start_menu, accounts = places(isolated_agent)

    shortcuts.make(PYTHONW, tmp_path / "home")

    assert made(isolated_agent) == sorted([start_menu, accounts])
    assert start_menu.read_text(encoding="utf-8").splitlines() == [
        f"{PYTHONW} -m sla_agent open", str(tmp_path / "home")]
    assert accounts.read_text(encoding="utf-8").splitlines() == [
        f"{PYTHONW} -m sla_agent window", str(tmp_path / "home")]
    assert not shortcuts.on_desktop()


def test_add_desktop_puts_the_app_on_the_desktop(isolated_agent, tmp_path):
    desktop, _, _ = places(isolated_agent)

    shortcuts.add_desktop(PYTHONW, tmp_path / "home")

    assert desktop.read_text(encoding="utf-8").splitlines() == [f"{PYTHONW} -m sla_agent open", str(tmp_path / "home")]
    assert shortcuts.on_desktop()


def test_make_points_a_desktop_icon_that_is_there_at_the_new_program_and_never_brings_back_a_deleted_one(
        isolated_agent, tmp_path):
    desktop, _, _ = places(isolated_agent)
    shortcuts.add_desktop(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)

    shortcuts.make(PYTHONW, tmp_path)

    assert desktop.read_text(encoding="utf-8").startswith(f"{PYTHONW} -m sla_agent open")
    desktop.unlink()  # the student deleted it

    shortcuts.make(PYTHONW, tmp_path)

    assert not desktop.exists()


def test_make_again_replaces_them_and_remove_is_fine_when_they_are_gone(isolated_agent, tmp_path):
    shortcuts.make(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.add_desktop(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.make(PYTHONW, tmp_path)

    assert len(made(isolated_agent)) == 3
    assert all(path.read_text(encoding="utf-8").startswith(PYTHONW) for path in made(isolated_agent))

    shortcuts.remove()
    shortcuts.remove()

    assert made(isolated_agent) == []


def test_the_project_folder_has_a_double_click_starter():
    starter = Path(__file__).resolve().parents[2] / "School-Life-Assistant.cmd"

    text = starter.read_text(encoding="utf-8")

    assert r'start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window' in text


# ---- COM on a background thread (Repair and the first-time form save run on one) -------------

import sys  # noqa: E402
import threading  # noqa: E402

import pytest  # noqa: E402

from sla_agent.scheduler import _task_scheduler as real_task_scheduler  # noqa: E402
from sla_agent.shortcuts import _shell as real_shell  # noqa: E402  (taken before the tests' fake replaces it)


def on_a_background_thread(work):
    """Run `work` the way the window runs Repair: on a worker thread, after pywin32 was first loaded on this one."""
    import win32com.client  # noqa: F401  (loaded on the main thread first, as the Accounts screen does)

    errors = []
    thread = threading.Thread(target=lambda: errors.extend(_attempt(work)))
    thread.start()
    thread.join()
    return errors


def _attempt(work):
    try:
        work()
        return []
    except Exception as error:
        return [error]


@pytest.mark.skipif(sys.platform != "win32", reason="Windows' own shell and Task Scheduler")
def test_the_windows_shell_can_be_reached_from_a_background_thread():
    assert on_a_background_thread(real_shell) == []


@pytest.mark.skipif(sys.platform != "win32", reason="Windows' own shell and Task Scheduler")
def test_task_scheduler_can_be_reached_from_a_background_thread():
    assert on_a_background_thread(real_task_scheduler) == []
