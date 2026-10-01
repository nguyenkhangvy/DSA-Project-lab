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
from sla_agent import accounts, credentials
from sla_agent.accounts import SetupForm
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ExtraVerification, OutlookNotSetUp, ServerError
from sla_agent.log import setup_logging
from sla_agent.scheduler import SchedulerError
from sla_agent.state import State, load_state, save_state


@pytest.fixture
def fakes():
    return Fakes()


def form(**changes):
    values = dict(address=SERVER, key=KEY, student_id=STUDENT, password=PASSWORD)
    values.update(changes)
    return SetupForm(**values)


EVERYTHING = dict(bb_user=BB_USER, bb_password=BB_PASSWORD, outlook=ME)


# ---- first setup ------------------------------------------------------------------


def test_a_first_setup_checks_then_saves_everything_and_turns_on_sync(fakes):
    results = accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    assert list(results) == ["site", "edusoft", "blackboard", "outlook", "sync"]
    assert all(result.ok for result in results.values())
    assert results["edusoft"].message == accounts.SAVED
    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    state = load_state()
    assert (state.server_url, state.student_id, state.blackboard_username, state.outlook_account) == (
        SERVER, STUDENT, BB_USER, ME)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)


@pytest.mark.parametrize("break_it, step", [
    (lambda fakes: setattr(fakes.server, "check_error", DeviceKeyRejected("rejected")), "site"),
    (lambda fakes: setattr(fakes.server, "check_error", ServerError("down")), "site"),
    (lambda fakes: setattr(fakes.edusoft, "login_error", BadCredentials("rejected")), "edusoft"),
    (lambda fakes: setattr(fakes.edusoft, "login_error", ExtraVerification("captcha")), "edusoft"),
], ids=["key-rejected", "site-down", "wrong-password", "captcha"])
def test_a_first_setup_saves_nothing_unless_the_site_and_edusoft_pass(fakes, isolated_agent, break_it, step):
    break_it(fakes)

    results = accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    assert list(results)[-1] == step
    assert not results[step].ok
    assert "Nothing was saved" in results[step].message
    assert isolated_agent.entries == {}
    assert load_state() == State()
    assert fakes.done == []


def test_a_first_setup_refuses_a_plain_http_address_without_contacting_it(fakes):
    results = accounts.first_setup(State(), form(address="http://sla.example.com"), fakes.tools())

    assert not results["site"].ok
    assert "https://" in results["site"].message
    assert fakes.servers == []


def test_a_rejected_blackboard_login_keeps_edusoft_saved(fakes):
    fakes.blackboard.login_error = BadCredentials("rejected")

    results = accounts.first_setup(State(), form(bb_user=BB_USER, bb_password="wrong"), fakes.tools())

    assert results["edusoft"].ok
    assert results["blackboard"].message == "Blackboard rejected the username or password. Nothing was saved."
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert load_state().blackboard_username is None
    assert results["sync"].ok


def test_half_a_blackboard_login_is_refused(fakes):
    results = accounts.first_setup(State(), form(bb_user=BB_USER), fakes.tools())

    assert results["blackboard"].message == "Blackboard username and password are both needed. Nothing was saved."
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
    fakes.fail = {"shortcuts": RuntimeError(f"failed for {PASSWORD} {BB_PASSWORD} {KEY}")}

    accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "***" in text
    assert PASSWORD not in text and BB_PASSWORD not in text and KEY not in text


def test_an_unexpected_error_after_saving_stops_only_that_step(fakes):
    fakes.blackboard.login_error = RuntimeError("a bug")

    results = accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    assert results["edusoft"].ok
    assert not results["blackboard"].ok
    assert results["blackboard"].message.startswith("Something went wrong (RuntimeError)")
    assert results["outlook"].ok and results["sync"].ok
    assert "task" in fakes.done


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

    assert len(notes) == 1 and "shortcuts" in notes[0]


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


def test_outlook_accounts_can_ask_only_an_outlook_that_is_already_open(fakes):
    assert accounts.outlook_accounts(fakes.tools(), start=False) == ([ME], None)
    assert accounts.outlook_accounts(fakes.tools()) == ([ME], None)

    assert fakes.looks == ["open", "start"]
