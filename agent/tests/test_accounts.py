"""accounts.py: checking and saving this laptop's accounts, with fakes for everything it talks to."""

import logging

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
from sla_agent import accounts, credentials, launcher
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ExtraVerification, OutlookNotSetUp
from sla_agent.log import setup_logging
from sla_agent.scheduler import SchedulerError
from sla_agent.state import State, load_state, save_state


@pytest.fixture
def fakes():
    return Fakes()


# ---- the website and the device key --------------------------------------------------


def test_check_site_says_when_the_website_accepts_the_key(fakes):
    assert accounts.check_site(SERVER, KEY, fakes.tools()) == accounts.Result(True, accounts.SITE_ACCEPTED)


def test_check_site_refuses_a_plain_http_address_without_contacting_it(fakes):
    result = accounts.check_site("http://sla.example.com", KEY, fakes.tools())

    assert not result.ok
    assert "https://" in result.message
    assert fakes.servers == []


@pytest.mark.parametrize("text, shaped", [
    (KEY, True), (f"  {KEY}\r\n", True), ("sla_short", False), ("My laptop", False), ("", False), (None, False),
    (KEY + "x", False), ("SLA_" + KEY[4:], False), (KEY[:-1] + "!", False),
])
def test_is_device_key_takes_only_a_keys_exact_shape(text, shaped):
    assert accounts.is_device_key(text) is shaped


# ---- the setup's step 2 -------------------------------------------------------------


def test_step_2_checks_edusoft_then_saves_the_key_and_edusoft_and_turns_on_sync(fakes):
    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert list(results) == ["edusoft", "sync"]
    assert results["edusoft"] == accounts.Result(True, accounts.SAVED)
    assert results["sync"].ok
    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert (load_state().server_url, load_state().student_id) == (SERVER, STUDENT)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)
    assert "desktop icon" not in fakes.done


@pytest.mark.parametrize("error", [BadCredentials("rejected"), ExtraVerification("captcha")],
                         ids=["wrong-password", "captcha"])
def test_step_2_saves_nothing_when_edusoft_refuses(fakes, isolated_agent, error):
    fakes.edusoft.login_error = error

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert list(results) == ["edusoft"]
    assert "Nothing was saved" in results["edusoft"].message
    assert isolated_agent.entries == {}
    assert load_state() == State()
    assert fakes.done == []


def test_step_2_keeps_edusoft_saved_when_sync_cannot_be_turned_on(fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert results["edusoft"].ok
    assert not results["sync"].ok
    assert "Access is denied" in results["sync"].message
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_an_unexpected_error_after_saving_stops_only_turning_on_sync(fakes):
    fakes.fail = {"task": RuntimeError("a bug")}

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert results["edusoft"].ok
    assert results["sync"].message.startswith("Something went wrong (RuntimeError)")
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_half_a_blackboard_login_is_refused(fakes):
    result = accounts.change_blackboard(set_up(), BB_USER, "", fakes.tools())

    assert result.message == "Blackboard username and password are both needed. Nothing was saved."
    assert fakes.blackboard.logins == []


# ---- changes ----------------------------------------------------------------------


def test_changing_the_student_id_forgets_the_old_password(fakes):
    state = set_up()

    result = accounts.change_edusoft(state, "ITITIU20002", "new-pass", fakes.tools())

    assert result == accounts.Result(True, accounts.SAVED)
    assert credentials.load_edusoft(STUDENT) is None
    assert credentials.load_edusoft("ITITIU20002") == "new-pass"
    assert load_state().student_id == "ITITIU20002"


def test_a_wrong_new_login_keeps_the_old_one(fakes):
    state = set_up()
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())
    fakes.edusoft.login_error = BadCredentials("rejected")
    fakes.blackboard.login_error = BadCredentials("rejected")
    fakes.server.check_error = DeviceKeyRejected("rejected")

    assert not accounts.change_edusoft(state, STUDENT, "wrong", fakes.tools()).ok
    assert not accounts.change_blackboard(state, BB_USER, "wrong", fakes.tools()).ok
    assert not accounts.change_site(state, "https://new.example.com", "sla_other-key-0000", fakes.tools()).ok

    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert credentials.load_device_key(SERVER) == KEY
    assert load_state() == state


def test_a_saved_login_clears_its_pause(fakes):
    state = set_up()
    state.paused = state.blackboard_paused = "bad_credentials"
    save_state(state)

    accounts.change_edusoft(state, STUDENT, "new-pass", fakes.tools())
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())

    assert (load_state().paused, load_state().blackboard_paused) == (None, None)


def test_changing_the_website_moves_the_key(fakes):
    state = set_up()

    result = accounts.change_site(state, "https://new.example.com", "sla_new-key-0123456789", fakes.tools())

    assert result.ok
    assert credentials.load_device_key(SERVER) is None
    assert credentials.load_device_key("https://new.example.com") == "sla_new-key-0123456789"
    assert load_state().server_url == "https://new.example.com"


def test_an_address_with_a_trailing_slash_is_saved_without_it(fakes):
    state = set_up()

    assert accounts.change_site(state, " https://new.example.com/ ", KEY, fakes.tools()).ok

    assert fakes.servers[-1] == ("https://new.example.com", KEY)
    assert load_state().server_url == "https://new.example.com"
    assert credentials.load_device_key("https://new.example.com") == KEY


# ---- Outlook ----------------------------------------------------------------------


def test_outlook_accounts_lists_them_or_says_what_is_wrong(fakes):
    assert accounts.outlook_accounts(fakes.tools()) == ([ME], None)
    fakes.found = []
    assert accounts.outlook_accounts(fakes.tools()) == ([], "Classic Outlook has no account yet.")
    fakes.found = OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")
    assert accounts.outlook_accounts(fakes.tools()) == ([], "Classic Outlook isn't set up on this laptop.")


def test_choosing_outlook_saves_it_and_adds_the_mail_link(fakes):
    state = set_up()

    result = accounts.choose_outlook(state, ME, fakes.tools())

    assert result.ok
    assert "never the text" in result.message
    assert load_state().outlook_account == ME
    assert fakes.done == ["mail link"]


def test_choosing_outlook_still_saves_it_when_the_link_fails(fakes):
    state = set_up()
    fakes.fail = {"mail link": OSError("denied")}

    result = accounts.choose_outlook(state, ME, fakes.tools())

    assert result.ok
    assert "sla-mail:" in result.notes[0]
    assert load_state().outlook_account == ME


# ---- automatic sync ---------------------------------------------------------------


def test_turn_on_sync_reports_a_failed_link_or_shortcut_but_still_makes_the_task(fakes):
    fakes.fail = {"window link": OSError("denied"), "shortcuts": RuntimeError("com_error")}

    result = accounts.turn_on_sync(fakes.tools())

    assert result == accounts.Result(True, accounts.SYNC_ON, result.notes)
    assert len(result.notes) == 2
    assert {"task", "mail link"} <= set(fakes.done)


def test_turn_on_sync_fails_when_the_task_cannot_be_made(fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}

    result = accounts.turn_on_sync(fakes.tools())

    assert not result.ok
    assert "Access is denied" in result.message
    assert {"mail link", "window link", "shortcuts"} <= set(fakes.done)


def test_turn_on_sync_makes_the_start_menu_entries_but_no_desktop_icon(fakes):
    assert accounts.turn_on_sync(fakes.tools()).ok

    assert "shortcuts" in fakes.done
    assert "desktop icon" not in fakes.done


# ---- the Desktop icon ---------------------------------------------------------------


def test_the_desktop_icon_is_added_when_asked(fakes):
    assert not accounts.desktop_icon_on(fakes.tools())

    assert accounts.add_desktop_icon(fakes.tools()) == accounts.Result(True, accounts.DESKTOP_ICON_ADDED)

    assert accounts.desktop_icon_on(fakes.tools())


def test_a_desktop_icon_that_cannot_be_added_says_where_the_app_is(fakes):
    fakes.fail = {"desktop icon": RuntimeError("com_error")}

    result = accounts.add_desktop_icon(fakes.tools())

    assert not result.ok
    assert result.message == ("Couldn't add the Desktop icon (RuntimeError). School-Life-Assistant is in the Start "
                              "menu.")


def test_the_desktop_icon_counts_as_off_when_windows_cannot_say(fakes):
    def broken():
        raise RuntimeError("com_error")

    tools = fakes.tools()
    tools.has_desktop_shortcut = broken

    assert accounts.desktop_icon_on(tools) is False


def test_sync_task_state(fakes, tmp_path):
    assert accounts.sync_task_state(fakes.tools()) == "off"
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    assert accounts.sync_task_state(fakes.tools()) == "nowhere"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")
    assert accounts.sync_task_state(fakes.tools()) == "on"


# ---- secrets ----------------------------------------------------------------------


def test_no_password_or_key_reaches_the_log_file(fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.fail = {"shortcuts": RuntimeError(f"failed for {PASSWORD} {KEY}")}

    accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "***" in text
    assert PASSWORD not in text and KEY not in text


# ---- a laptop set up before the window existed ------------------------------------------


def test_the_window_adds_its_link_type_and_shortcuts_to_a_laptop_set_up_before_it_existed(fakes):
    set_up()

    assert accounts.add_window_links(fakes.tools()) == []

    assert {"window link", "mail link", "shortcuts"} <= set(fakes.done)


def test_the_window_adds_nothing_where_the_link_type_exists_or_nothing_is_set_up(fakes):
    assert accounts.add_window_links(fakes.tools()) == []  # not set up: the first-time form makes them
    assert fakes.done == []

    set_up()
    fakes.done.append("window link")  # already registered, e.g. by `sla-agent setup` since this change

    assert accounts.add_window_links(fakes.tools()) == []
    assert fakes.done == ["window link"]


def test_a_link_or_shortcut_that_fails_is_reported(fakes):
    set_up()
    fakes.fail = {"shortcuts": RuntimeError("com_error")}

    notes = accounts.add_window_links(fakes.tools())

    assert len(notes) == 1 and "Start menu entries" in notes[0]


# ---- saving while a sync runs -------------------------------------------------------------


def test_a_change_keeps_what_a_sync_saved_while_it_was_being_checked(fakes):
    state = set_up()  # what the window read when the student pressed Check and save
    synced = load_state()
    synced.last_result = {"at": "2026-10-01T02:00:00+00:00", "status": "success", "message": "Sync finished."}
    synced.blackboard_paused = "bad_credentials"
    save_state(synced)  # a sync that finished during the check

    assert accounts.change_edusoft(state, STUDENT, "new-pass", fakes.tools()).ok

    saved = load_state()
    assert saved.last_result == synced.last_result
    assert saved.blackboard_paused == "bad_credentials"
    assert saved.paused is None
    assert state == saved


def test_the_built_app_sees_a_task_that_runs_another_copy(fakes, tmp_path, monkeypatch):
    monkeypatch.setattr(launcher, "frozen", lambda: True)
    (tmp_path / "pythonw.exe").write_text("")
    (tmp_path / "School-Life-Assistant.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # set up from source before
    fakes.me = str(tmp_path / "School-Life-Assistant.exe")

    assert accounts.sync_task_state(fakes.tools()) == "elsewhere"

    fakes.program = fakes.me
    assert accounts.sync_task_state(fakes.tools()) == "on"
