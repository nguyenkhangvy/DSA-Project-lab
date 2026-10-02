"""The School-Life-Assistant window's Accounts screen, built for real but hidden, with fakes behind it
(accounts_fakes.Fakes). The setup pages have their own tests (test_setup_steps.py). Checks run at once instead of on a
thread (run_at_once)."""

import pytest

from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, ME, SERVER, STUDENT, Fakes, set_up
from sla_agent import __version__, accounts, credentials, launcher, outlook_page, setup_steps, window, window_parts
from sla_agent.errors import BadCredentials
from sla_agent.outlook_reader import MISSING
from sla_agent.state import save_state


@pytest.fixture
def fakes():
    return Fakes()


def open_window(root, fakes, run=window_parts.run_at_once):
    return window.App(root, fakes.tools(), run=run)


def disabled(button):
    return button.instate(["disabled"])


def test_a_new_laptop_gets_the_setup_pages(root, fakes):
    app = open_window(root, fakes)

    assert isinstance(app.screen, setup_steps.SetupSteps)


# ---- Accounts ---------------------------------------------------------------------


def test_accounts_shows_each_account_and_a_pause(root, fakes):
    state = set_up()
    state.paused = "bad_credentials"
    save_state(state)

    app = open_window(root, fakes)

    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.values["site"].get() == f"{SERVER}; never synced"
    assert app.screen.values["edusoft"].get() == f"{STUDENT}: paused: wrong student ID or password"
    assert app.screen.values["blackboard"].get() == "not set up"
    assert app.screen.buttons["blackboard"].cget("text") == "Set up"
    assert app.screen.values["sync"].get() == "off"
    assert app.screen.buttons["sync"].cget("text") == "Repair"


def test_a_wrong_new_blackboard_password_keeps_the_old_login(root, fakes):
    state = set_up()
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())
    app = open_window(root, fakes)

    app.screen.open("blackboard")

    assert all(disabled(button) for button in app.screen.buttons.values())
    editor = app.screen.editor
    assert editor.values["username"].get() == BB_USER
    fakes.blackboard.login_error = BadCredentials("rejected")
    editor.values["password"].set("wrong")
    editor.save()
    assert app.screen.answers["blackboard"].get() == "✗ Blackboard rejected the username or password. Nothing was saved."
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD

    editor.cancel()

    assert app.screen.editor is None
    assert not any(disabled(button) for button in app.screen.buttons.values())


def test_a_change_that_passes_goes_back_to_accounts_with_its_message(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("edusoft")
    app.screen.editor.values["password"].set("new-pass")

    app.screen.editor.save()

    assert app.screen.editor is None
    assert app.screen.notice.get() == "✓ " + accounts.SAVED
    assert credentials.load_edusoft(STUDENT) == "new-pass"


def test_outlook_change_picks_from_the_accounts_found(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("outlook")

    assert app.screen.editor.values["outlook"].get() == ME
    app.screen.editor.save()

    assert app.screen.values["outlook"].get() == ME


def test_accounts_outlook_set_up_guides_the_install_when_classic_outlook_is_missing(root, fakes):
    set_up()
    fakes.outlook = MISSING
    app = open_window(root, fakes)

    app.screen.open("outlook")

    editor = app.screen.editor
    assert editor.outlook.lines[0] == outlook_page.MISSING_INTRO
    assert outlook_page.LATER not in editor.outlook.lines  # Accounts has Cancel, not Skip
    editor.save()
    assert app.screen.answers["outlook"].get() == "✗ Choose an account first."


def test_repair_turns_sync_on_again(root, fakes, tmp_path):
    set_up()
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "points to a program that no longer exists"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"
    assert "sync" not in app.screen.buttons


def test_accounts_offers_the_desktop_icon_and_add_puts_it_there(root, fakes):
    set_up()
    app = open_window(root, fakes)
    assert app.screen.values["desktop"].get() == "off"

    app.screen.buttons["desktop"].invoke()

    assert "desktop icon" in fakes.done
    assert app.screen.notice.get() == "✓ " + accounts.DESKTOP_ICON_ADDED
    assert app.screen.values["desktop"].get() == "on"
    assert "desktop" not in app.screen.buttons


def test_a_desktop_icon_that_cannot_be_added_says_so(root, fakes):
    set_up()
    fakes.fail = {"desktop icon": RuntimeError("com_error")}
    app = open_window(root, fakes)

    app.screen.buttons["desktop"].invoke()

    assert app.screen.notice.get().startswith("✗ Couldn't add the Desktop icon (RuntimeError).")
    assert app.screen.values["desktop"].get() == "off"


# ---- one window at a time -------------------------------------------------------------


def test_a_second_start_brings_the_open_window_forward_instead():
    forward = []

    assert window.claim_single_window(first=lambda name: True, bring_forward=forward.append)
    assert not window.claim_single_window(first=lambda name: False, bring_forward=forward.append)
    assert forward == [window.TITLE]


def test_opening_on_a_laptop_set_up_before_the_window_adds_its_link_and_shortcuts(fakes, monkeypatch):
    set_up()
    opened = []

    class Root:
        def mainloop(self):
            pass

    monkeypatch.setattr(window, "claim_single_window", lambda: True)
    monkeypatch.setattr(window.tk, "Tk", Root)
    monkeypatch.setattr(window, "App", lambda root, tools, notice="": opened.append(notice))

    assert window.main(fakes.tools()) == 0

    assert {"window link", "mail link", "shortcuts"} <= set(fakes.done)
    assert opened == [""]


# ---- the built app ------------------------------------------------------------------------


def test_accounts_shows_the_version_and_when_it_last_updated_itself(root, fakes):
    state = set_up()
    state.updated_at = "2026-10-05T07:02:00+00:00"
    save_state(state)

    app = open_window(root, fakes)

    when = window.local_time("2026-10-05T07:02:00+00:00")
    assert app.screen.values["version"].get() == f"{__version__}, updated by itself on {when}"
    assert "version" not in app.screen.buttons


def test_before_any_update_accounts_shows_just_the_version(root, fakes):
    set_up()

    app = open_window(root, fakes)

    assert app.screen.values["version"].get() == __version__


def test_the_built_app_offers_repair_when_the_task_runs_another_copy(root, fakes, monkeypatch, tmp_path):
    set_up()
    monkeypatch.setattr(launcher, "frozen", lambda: True)
    (tmp_path / "pythonw.exe").write_text("")
    (tmp_path / "School-Life-Assistant.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # set up from source before
    fakes.me = str(tmp_path / "School-Life-Assistant.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "runs another copy of School-Life-Assistant"
    fakes.program = fakes.me  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"


# ---- the website as an app ---------------------------------------------------------------------


def test_accounts_has_a_button_that_opens_the_website_as_an_app(root, fakes):
    set_up()
    app = open_window(root, fakes)

    app.screen.open_button.invoke()

    assert fakes.opened == [SERVER]
