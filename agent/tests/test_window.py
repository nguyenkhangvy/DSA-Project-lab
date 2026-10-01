"""The School-Life-Assistant window, built for real but hidden, with fakes behind it (accounts_fakes.Fakes). Checks
run at once instead of on a thread (run_at_once), except where a test holds them to look at the screen meanwhile."""

import logging
import tkinter as tk

import pytest

from agent.tests.accounts_fakes import (
    BB_PASSWORD,
    BB_USER,
    KEY,
    ME,
    PASSWORD,
    SERVER,
    STUDENT,
    Fakes,
    set_up,
)
from sla_agent import accounts, credentials, window
from sla_agent.errors import BadCredentials, OutlookNotSetUp
from sla_agent.log import setup_logging
from sla_agent.state import save_state


@pytest.fixture
def root(request):
    # While pytest redirects the output file descriptors, Tcl on Windows now and then fails to read its own library
    # files ("couldn't read file .../tk8.6/entry.tcl"), so Tk starts with that redirection paused.
    capture = request.config.pluginmanager.getplugin("capturemanager")
    try:
        with capture.global_and_fixture_disabled():
            root = tk.Tk()
    except tk.TclError as error:  # a machine without a desktop
        pytest.skip(f"Tk can't start here: {error}")
    root.withdraw()
    yield root
    root.destroy()


@pytest.fixture
def fakes():
    return Fakes()


def open_window(root, fakes, run=window.run_at_once):
    return window.App(root, fakes.tools(), run=run)


def fill(screen, **values):
    for name, value in values.items():
        screen.values[name].set(value)


FIRST_TIME = dict(address=SERVER, key=KEY, student_id=STUDENT, password=PASSWORD)


def disabled(button):
    return button.instate(["disabled"])


# ---- first time -------------------------------------------------------------------


def test_a_new_laptop_gets_the_first_time_form(root, fakes):
    app = open_window(root, fakes)

    assert isinstance(app.screen, window.SetupScreen)
    assert app.screen.values["address"].get() == "http://localhost:5000"
    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK, ME)
    assert app.screen.values["outlook"].get() == window.NO_OUTLOOK


def test_the_first_time_form_saves_and_switches_to_accounts(root, fakes):
    app = open_window(root, fakes)
    fill(app.screen, bb_user=BB_USER, bb_password=BB_PASSWORD, outlook=ME, **FIRST_TIME)

    app.screen.save()

    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.notice.get() == window.DONE
    assert app.screen.values["edusoft"].get() == f"{STUDENT}: on"
    assert app.screen.values["blackboard"].get() == f"{BB_USER}: on"
    assert app.screen.values["outlook"].get() == ME
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_a_wrong_password_keeps_the_form_filled_in_and_saves_nothing(root, fakes, isolated_agent):
    fakes.edusoft.login_error = BadCredentials("rejected")
    app = open_window(root, fakes)
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert isinstance(app.screen, window.SetupScreen)
    assert app.screen.answers["site"].get() == "✓ The web app accepted this device key."
    assert app.screen.answers["edusoft"].get() == "✗ EduSoft rejected the student ID or password. Nothing was saved."
    assert app.screen.values["password"].get() == PASSWORD
    assert not disabled(app.screen.save_button)
    assert isolated_agent.entries == {}


def test_a_pasted_key_with_spaces_or_a_line_break_is_trimmed(root, fakes):
    app = open_window(root, fakes)
    fill(app.screen, **dict(FIRST_TIME, key=f"  {KEY}\n"))

    app.screen.save()

    assert fakes.servers == [(SERVER, KEY)]
    assert credentials.load_device_key(SERVER) == KEY


def test_while_checking_the_button_is_off_and_a_late_outlook_list_is_ignored(root, fakes):
    held = []
    app = open_window(root, fakes, run=lambda work, done: held.append((work, done)))
    outlook_lookup = held.pop()
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert disabled(app.screen.save_button)
    assert app.screen.answers["sync"].get() == window.CHECKING
    work, done = held.pop()
    done(window.attempt(work))
    assert isinstance(app.screen, window.AccountsScreen)
    work, done = outlook_lookup
    done(window.attempt(work))  # the form it was for is gone: nothing happens
    assert isinstance(app.screen, window.AccountsScreen)


def test_something_unexpected_is_shown_not_raised(root, fakes):
    fakes.server.check_error = RuntimeError("a bug")  # not a ServerError: nothing expects it
    app = open_window(root, fakes)
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert app.screen.answers["sync"].get() == "✗ Something went wrong (RuntimeError). Nothing was saved."
    assert not disabled(app.screen.save_button)


def test_without_outlook_the_form_says_so_and_refresh_looks_again(root, fakes):
    fakes.found = OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")
    app = open_window(root, fakes)

    assert app.screen.outlook.note.get().startswith("Classic Outlook isn't set up on this laptop.")
    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK,)

    fakes.found = [ME]
    app.screen.outlook.refresh()

    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK, ME)
    assert app.screen.outlook.note.get() == ""


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


def test_repair_turns_sync_on_again(root, fakes, tmp_path):
    set_up()
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "points to a Python that no longer exists"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"
    assert "sync" not in app.screen.buttons


def test_the_window_never_logs_a_password(root, fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.blackboard.login_error = RuntimeError(f"a bug with {BB_PASSWORD}")
    app = open_window(root, fakes)
    fill(app.screen, bb_user=BB_USER, bb_password=BB_PASSWORD, **FIRST_TIME)

    app.screen.save()

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "RuntimeError" in text
    assert BB_PASSWORD not in text and PASSWORD not in text and KEY not in text
    assert isinstance(app.screen, window.AccountsScreen)  # EduSoft was saved and sync turned on
    assert "✗ Something went wrong (RuntimeError)" in app.screen.notice.get()


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


def test_the_form_looks_only_at_an_open_outlook_and_refresh_may_start_it(root, fakes):
    app = open_window(root, fakes)

    assert fakes.looks == ["open"]  # opening the window never starts Outlook or its first-run wizard

    app.screen.outlook.refresh_button.invoke()

    assert fakes.looks == ["open", "start"]
