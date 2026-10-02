"""`self-check <version>` (selfcheck.py): whether the built app has everything it needs. The real checks run on
the built app in CI (agent/packaging/smoke-test.ps1); here they are replaced."""

from sla_agent import __version__, cli, launcher, selfcheck


def test_a_complete_app_of_the_expected_version_has_no_problems():
    assert selfcheck.problems(__version__, checks=[lambda: None]) == []


def test_another_version_is_a_problem():
    assert selfcheck.problems("9.9.9", checks=[]) == [f"this is version {__version__}, not 9.9.9"]


def test_each_failing_check_is_named():
    def outlook_link():
        raise ImportError("No module named 'win32com'")

    assert selfcheck.problems(__version__, checks=[outlook_link]) == [
        "outlook_link: ImportError: No module named 'win32com'"]


def test_the_checks_cover_what_the_built_app_needs():
    assert [check.__name__ for check in selfcheck.CHECKS] == [
        "tk_with_its_files", "outlook_link", "credential_manager", "data_contract", "https_certificates",
        "setup_window"]


def test_the_window_and_its_pages_load():
    selfcheck.setup_window()  # what the built app does: a module PyInstaller missed would fail here


def test_self_check_answers_with_its_exit_code(monkeypatch):
    monkeypatch.setattr(selfcheck, "CHECKS", ())

    assert cli.main(["self-check", __version__]) == 0
    assert cli.main(["self-check", "9.9.9"]) == 1


def test_the_app_double_clicked_opens_school_life_assistant(monkeypatch):
    opened = []
    monkeypatch.setitem(cli.COMMANDS, "open", lambda args: opened.append(args.command) or 0)
    monkeypatch.setattr(launcher, "frozen", lambda: True)

    assert cli.main([]) == 0
    assert opened == ["open"]
