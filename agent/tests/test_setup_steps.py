"""The setup pages (setup_steps.py), built for real but hidden, with fakes behind them (accounts_fakes.Fakes). Checks
run at once instead of on a thread (run_at_once), except where a test holds them to look at the page meanwhile; the
clipboard and the browser are fakes too."""

import logging

import pytest

from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, KEY, ME, PASSWORD, SERVER, STUDENT, Fakes
from sla_agent import accounts, credentials, launcher, outlook_page, setup_steps, window, window_parts
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ServerUnreachable
from sla_agent.log import setup_logging
from sla_agent.outlook_reader import MISSING
from sla_agent.scheduler import SchedulerError
from sla_agent.state import load_state


@pytest.fixture
def fakes():
    return Fakes()


@pytest.fixture(autouse=True)
def clipboard(monkeypatch):
    """What the clipboard holds (clipboard[0]): tests never read the real one."""
    held = [""]
    monkeypatch.setattr(setup_steps, "read_clipboard", lambda root: held[0])
    return held


@pytest.fixture
def browser(monkeypatch):
    opened = []
    monkeypatch.setattr(setup_steps.webbrowser, "open", opened.append)
    return opened


def start(root, fakes, run=window_parts.run_at_once):
    """A new laptop's window on step 1, with the website at SERVER (as after Change)."""
    app = window.App(root, fakes.tools(), run=run)
    app.screen.values["address"].set(SERVER)
    return app, app.screen


def disabled(button):
    return button.instate(["disabled"])


def to_step_2(steps):
    steps.values["key"].set(KEY)
    steps.page.next_button.invoke()


def through_step_2(steps, password=PASSWORD):
    to_step_2(steps)
    steps.values["student_id"].set(STUDENT)
    steps.values["password"].set(password)
    steps.page.next_button.invoke()


def to_the_end(steps):
    """Through every step, skipping what can be skipped: on to the last page."""
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard
    steps.page.skip_button.invoke()  # Outlook


def logged(log_path, tmp_path):
    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    return log_path.read_text(encoding="utf-8")


# ---- step 1: the website ---------------------------------------------------------------------


def test_a_new_laptop_starts_at_step_1_with_its_guide(root, fakes):
    steps = window.App(root, fakes.tools(), run=window_parts.run_at_once).screen

    assert isinstance(steps.page, setup_steps.SitePage)
    assert steps.page.header() == "Step 1 of 4 · Connect to the website"
    assert steps.values["address"].get() == setup_steps.LOCAL_ADDRESS  # from source; the built app: online
    assert disabled(steps.page.next_button)


def test_the_built_app_starts_with_the_website_online(root, fakes, monkeypatch):
    monkeypatch.setattr(launcher, "frozen", lambda: True)

    steps = window.App(root, fakes.tools(), run=window_parts.run_at_once).screen

    assert steps.values["address"].get() == "https://school-life-assistant.onrender.com"


def test_open_the_website_opens_the_devices_page(root, fakes, browser):
    _, steps = start(root, fakes)

    steps.page.open_button.invoke()

    assert browser == [f"{SERVER}/school/devices"]


def test_a_key_is_checked_as_soon_as_it_is_there_and_next_works_once_accepted(root, fakes):
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert fakes.servers == [(SERVER, KEY)]
    assert steps.page.answer.get() == "✓ The website accepted this key."
    assert not disabled(steps.page.next_button)


def test_a_key_with_spaces_or_a_line_break_around_it_is_checked_without_them(root, fakes):  # Review Focus 2
    _, steps = start(root, fakes)

    steps.values["key"].set(f"  {KEY}\n")

    assert fakes.servers == [(SERVER, KEY)]
    assert not disabled(steps.page.next_button)


def test_text_that_isnt_a_device_key_is_never_sent(root, fakes):
    _, steps = start(root, fakes)

    steps.values["key"].set("My laptop")

    assert fakes.servers == []
    assert steps.page.answer.get() == "✗ " + setup_steps.NOT_A_KEY
    assert disabled(steps.page.next_button)


def test_a_rejected_key_says_so_and_next_stays_off(root, fakes):
    fakes.server.check_error = DeviceKeyRejected("rejected")
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert steps.page.answer.get() == ("✗ The web app rejected this device key. Create a new one on the Devices "
                                       "page. Nothing was saved.")
    assert disabled(steps.page.next_button)


def test_a_website_that_cant_be_reached_says_so_and_saves_nothing(root, fakes, isolated_agent):  # Review Focus 1
    fakes.server.check_error = ServerUnreachable("The web app couldn't be reached (ConnectionError).")
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert steps.page.answer.get() == ("✗ Couldn't reach the web app: The web app couldn't be reached "
                                       "(ConnectionError). Nothing was saved.")
    assert disabled(steps.page.next_button)
    assert isolated_agent.entries == {}


def test_paste_takes_a_copied_key(root, fakes, clipboard):  # Review Focus 2
    _, steps = start(root, fakes)
    clipboard[0] = f"{KEY}\r\n"

    steps.page.paste_button.invoke()

    assert steps.values["key"].get() == KEY
    assert not disabled(steps.page.next_button)


def test_paste_says_when_the_copied_text_isnt_a_key(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = "My laptop"

    steps.page.paste_button.invoke()

    assert steps.values["key"].get() == ""
    assert steps.page.answer.get() == "✗ " + setup_steps.NOT_A_KEY


def test_coming_back_to_the_window_fills_in_a_copied_key(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = KEY

    steps.came_forward()

    assert steps.values["key"].get() == KEY
    assert fakes.servers == [(SERVER, KEY)]


def test_coming_back_never_replaces_a_key_already_there_or_takes_other_text(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = "some other text"

    steps.came_forward()

    assert steps.values["key"].get() == ""
    steps.values["key"].set("sla_typed-by-hand")
    clipboard[0] = KEY

    steps.came_forward()

    assert steps.values["key"].get() == "sla_typed-by-hand"


def test_change_shows_the_address_box_and_a_new_address_checks_the_key_again(root, fakes):  # Review Focus 3
    _, steps = start(root, fakes)
    steps.values["key"].set(KEY)

    steps.page.change_button.invoke()
    steps.values["address"].set("https://other.example.com/")
    steps.page.key_changed()  # what Enter, or leaving the address box, does

    assert steps.page.change_button is None
    assert fakes.servers == [(SERVER, KEY), ("https://other.example.com", KEY)]
    assert not disabled(steps.page.next_button)


def test_a_plain_http_address_is_refused_without_sending_the_key(root, fakes):  # Review Focus 3
    _, steps = start(root, fakes)
    steps.page.change_button.invoke()
    steps.values["address"].set("http://sla.example.com")

    steps.values["key"].set(KEY)

    assert fakes.servers == []
    assert "must start with https://" in steps.page.answer.get()
    assert disabled(steps.page.next_button)


def test_a_late_answer_for_a_key_changed_meanwhile_is_dropped(root, fakes):
    held = []
    _, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))
    steps.values["key"].set(KEY)
    assert steps.page.answer.get() == window_parts.CHECKING
    work, done = held.pop()

    steps.values["key"].set("")  # cleared while the website answered
    done(window_parts.attempt(work))

    assert steps.page.answer.get() == ""
    assert disabled(steps.page.next_button)


# ---- step 2: EduSoft ---------------------------------------------------------------------


def test_step_2_saves_the_key_and_edusoft_and_turns_on_sync_without_a_desktop_icon(root, fakes):
    _, steps = start(root, fakes)

    through_step_2(steps)

    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert (load_state().server_url, load_state().student_id) == (SERVER, STUDENT)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)
    assert "desktop icon" not in fakes.done
    assert steps.values["key"].get() == steps.values["password"].get() == ""
    assert isinstance(steps.page, steps.after(setup_steps.EdusoftPage))


def test_a_wrong_edusoft_password_saves_nothing_and_back_keeps_the_accepted_key(root, fakes, isolated_agent):
    fakes.edusoft.login_error = BadCredentials("rejected")
    _, steps = start(root, fakes)

    through_step_2(steps, password="wrong")

    assert isinstance(steps.page, setup_steps.EdusoftPage)
    assert steps.page.answer.get() == "✗ EduSoft rejected the student ID or password. Nothing was saved."
    assert isolated_agent.entries == {}
    assert steps.values["password"].get() == "wrong"
    assert not disabled(steps.page.next_button)

    steps.page.back_button.invoke()

    assert isinstance(steps.page, setup_steps.SitePage)
    assert steps.values["key"].get() == KEY
    assert steps.page.answer.get() == "✓ The website accepted this key."
    assert not disabled(steps.page.next_button)
    assert len(fakes.servers) == 1  # an accepted key isn't checked again


def test_something_unexpected_on_step_2_is_shown_not_raised(root, fakes):
    fakes.edusoft.login_error = RuntimeError("a bug")
    _, steps = start(root, fakes)

    through_step_2(steps)

    assert isinstance(steps.page, setup_steps.EdusoftPage)
    assert steps.page.answer.get() == "✗ Something went wrong (RuntimeError). Nothing was saved."
    assert not disabled(steps.page.back_button)


def test_while_edusoft_is_checked_both_buttons_are_off(root, fakes):
    held = []
    app, steps = start(root, fakes)
    to_step_2(steps)
    app.run = lambda work, done: held.append((work, done))
    steps.values["student_id"].set(STUDENT)
    steps.values["password"].set(PASSWORD)

    steps.page.next_button.invoke()

    assert steps.page.answer.get() == window_parts.CHECKING
    assert disabled(steps.page.back_button) and disabled(steps.page.next_button)
    work, done = held.pop()
    done(window_parts.attempt(work))
    assert isinstance(steps.page, steps.after(setup_steps.EdusoftPage))


# ---- the last page --------------------------------------------------------------------------


def test_the_last_page_says_what_is_on(root, fakes):
    _, steps = start(root, fakes)

    to_the_end(steps)

    page = steps.page
    assert isinstance(page, setup_steps.DonePage)
    assert page.header() == "All set"
    assert page.lines == [setup_steps.ALL_SET, f"✓ EduSoft: {STUDENT}",
                          "– Blackboard: skipped: set it up later in Accounts",
                          "– Outlook: skipped: set it up later in Accounts"]


def test_yes_adds_the_desktop_icon_then_shows_accounts_and_opens_the_app(root, fakes):
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.yes_button.invoke()

    assert "desktop icon" in fakes.done
    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.notice.get() == "✓ " + accounts.DESKTOP_ICON_ADDED
    assert app.screen.values["desktop"].get() == "on"
    assert fakes.opened == [SERVER]


def test_no_adds_no_desktop_icon_and_still_opens_the_app(root, fakes):
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.no_button.invoke()

    assert "desktop icon" not in fakes.done
    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.values["desktop"].get() == "off"
    assert fakes.opened == [SERVER]


def test_a_desktop_icon_that_cant_be_added_says_so_and_still_opens_the_app(root, fakes):
    fakes.fail = {"desktop icon": RuntimeError("com_error")}
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.yes_button.invoke()

    assert app.screen.notice.get().startswith("✗ Couldn't add the Desktop icon (RuntimeError).")
    assert fakes.opened == [SERVER]


def test_the_last_page_says_when_sync_couldnt_be_turned_on(root, fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    assert steps.page.lines[:2] == ["✗ Couldn't create the scheduled task: Access is denied.",
                                    setup_steps.SYNC_REPAIR]


def test_the_last_page_lists_a_start_menu_entry_that_couldnt_be_made(root, fakes):
    fakes.fail = {"shortcuts": RuntimeError("com_error")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    assert steps.page.lines[0] == setup_steps.ALL_SET
    assert any(line.startswith("Couldn't make the Start menu entries (RuntimeError).") for line in steps.page.lines)


def test_the_setup_pages_never_log_a_password_or_the_key(root, fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.fail = {"shortcuts": RuntimeError(f"a bug with {PASSWORD} and {KEY}")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    text = logged(log_path, tmp_path)
    assert "Couldn't make the shortcuts" in text
    assert PASSWORD not in text and KEY not in text


# ---- steps 3 and 4: Blackboard and Outlook ------------------------------------------------


def test_after_edusoft_comes_blackboard_and_skip_saves_nothing(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)

    assert isinstance(steps.page, setup_steps.BlackboardPage)
    assert steps.page.header() == "Step 3 of 4 · Blackboard (optional)"

    steps.page.skip_button.invoke()

    assert isinstance(steps.page, setup_steps.OutlookStep)
    assert fakes.blackboard.logins == []
    assert load_state().blackboard_username is None


def test_a_blackboard_login_is_checked_and_saved(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set(BB_PASSWORD)

    steps.page.next_button.invoke()

    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert steps.values["bb_password"].get() == ""
    assert isinstance(steps.page, setup_steps.OutlookStep)


def test_a_wrong_blackboard_password_stays_on_the_page(root, fakes):
    fakes.blackboard.login_error = BadCredentials("rejected")
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set("wrong")

    steps.page.next_button.invoke()

    assert isinstance(steps.page, setup_steps.BlackboardPage)
    assert steps.page.answer.get() == "✗ Blackboard rejected the username or password. Nothing was saved."
    assert not disabled(steps.page.skip_button) and not disabled(steps.page.next_button)


def test_the_outlook_step_chooses_the_iu_account_and_next_saves_it(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard

    assert steps.page.header() == "Step 4 of 4 · Outlook (optional)"
    assert steps.values["outlook"].get() == ME
    assert not disabled(steps.page.next_button)

    steps.page.next_button.invoke()

    assert load_state().outlook_account == ME
    assert isinstance(steps.page, setup_steps.DonePage)
    assert f"✓ Outlook: {ME}" in steps.page.lines


def test_without_classic_outlook_next_is_off_and_skip_goes_on(root, fakes):
    fakes.outlook = MISSING
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard

    assert steps.page.outlook.lines[0] == outlook_page.MISSING_INTRO
    assert outlook_page.LATER in steps.page.outlook.lines
    assert disabled(steps.page.next_button)

    steps.page.skip_button.invoke()

    assert isinstance(steps.page, setup_steps.DonePage)
    assert "– Outlook: skipped: set it up later in Accounts" in steps.page.lines


def test_skip_works_while_outlook_is_still_being_looked_for(root, fakes):  # Review Focus 4
    held = []
    app, steps = start(root, fakes)
    through_step_2(steps)
    app.run = lambda work, done: held.append((work, done))

    steps.page.skip_button.invoke()  # Blackboard: the Outlook step starts looking

    assert steps.page.outlook.lines == [outlook_page.LOOKING]
    assert disabled(steps.page.next_button) and not disabled(steps.page.skip_button)
    steps.page.skip_button.invoke()
    work, done = held.pop()
    done(window_parts.attempt(work))  # Outlook answers after the student moved on

    assert isinstance(steps.page, setup_steps.DonePage)


def test_the_last_page_lists_blackboard_and_outlook_once_set_up(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set(BB_PASSWORD)
    steps.page.next_button.invoke()
    steps.page.next_button.invoke()  # Outlook: the IU account

    assert steps.page.lines == [setup_steps.ALL_SET, f"✓ EduSoft: {STUDENT}", f"✓ Blackboard: {BB_USER}",
                                f"✓ Outlook: {ME}"]
