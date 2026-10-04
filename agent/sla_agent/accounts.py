"""Checking and saving this laptop's accounts: the website connection, EduSoft, Blackboard and Outlook, and turning
on automatic sync (spec 2026-10-01-accounts-window-design.md, 4.1).

`sla-agent setup` (terminal) and the School-Life-Assistant window both use it. Nothing here prints, asks or draws:
each step returns a Result whose message the caller shows, in the words `sla-agent setup` has always used. A login
is checked once before it is saved, and one that fails changes nothing. Passwords and the device key go only to
Windows Credential Manager (credentials.py)."""

import logging
import os
import re
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Callable

from sla_agent import credentials, launcher
from sla_agent.connect import Cancelled, Closed, TimedOut
from sla_agent.errors import (
    AgentError,
    BadCredentials,
    ConnectRefused,
    DeviceKeyRejected,
    ExtraVerification,
    ServerError,
    UnexpectedAnswer,
)
from sla_agent.log import protect
from sla_agent.scheduler import SchedulerError, current_user
from sla_agent.server_client import check_server_url
from sla_agent.state import State, agent_home, load_state, save_state

log = logging.getLogger(__name__)

SAVED = "Saved. Your password is in Windows Credential Manager, not in any file."
BLACKBOARD_SAVED = "Blackboard saved. Its password is in Windows Credential Manager too."
SYNC_ON = "Automatic sync is on: this laptop checks in every minute while you're logged in."
DESKTOP_ICON_ADDED = "The School-Life-Assistant icon is on your Desktop."
SITE_ACCEPTED = "The website accepted this key."
DEVICE_KEY = re.compile(r"sla_[A-Za-z0-9_-]{43}")  # the website's keys: sla_ and 32 random bytes, URL-safe Base64
SITE_SAVED = "Saved. This laptop now syncs with {}."
CONNECT_WAIT = 5 * 60  # seconds for the student to log in, or to create an account first
CONNECT_CANCELLED = "You pressed Cancel on the website. Nothing was saved."
CONNECT_TIMED_OUT = "Nothing came back from the website. Press Connect to try again."
CONNECT_REFUSED = "The website didn't accept the connection. Press Connect to try again. Nothing was saved."
CONNECT_UNAVAILABLE = "Connect isn't working on this laptop. Use Paste a key instead."


@dataclass(frozen=True)
class Result:
    ok: bool
    message: str
    notes: tuple = ()  # more lines that don't change `ok`, e.g. a shortcut that couldn't be made


@dataclass
class Tools:
    """What this module talks to. `sla-agent` passes the real ones (cli.tools()); tests pass fakes."""

    make_server: Callable  # (address, device key) -> ServerClient
    make_edusoft: Callable  # () -> EduSoftClient
    make_blackboard: Callable  # () -> BlackboardClient
    find_outlook_accounts: Callable  # () -> [account address]; raises AgentError
    outlook_state: Callable  # () -> outlook_reader.MISSING, NOT_SIGNED_IN or SIGNED_IN, from the registry
    start_outlook: Callable  # () -> opens Outlook (classic) for the student to sign in; raises OSError
    program: Callable  # () -> what the task, the links and the shortcuts start: the built app or a windowless Python
    install_task: Callable  # (program, user, folder=) ; raises SchedulerError
    task_program: Callable  # () -> the program the scheduled task starts, or None
    register_mail_link: Callable  # (program)
    register_window_link: Callable  # (program)
    make_shortcuts: Callable  # (program, folder): the Start menu entries; points a Desktop icon that is there too
    add_desktop_shortcut: Callable  # (program, folder): the Desktop icon, when the student asks for it
    has_desktop_shortcut: Callable  # () -> whether the Desktop icon is there
    has_window_link: Callable  # () -> whether the sla-agent: link type is registered
    open_site: Callable  # (address) -> opens the website as an app (app_window.open_app)
    listen: Callable  # () -> connect.Connection, listening on 127.0.0.1 for Connect; raises OSError
    open_browser: Callable  # (url) -> opens the default browser at url
    connect: Callable  # (address, code, verifier) -> ConnectResult: Connect's trade-in; raises ConnectRefused, ServerError


def _clean(address):
    """Saved without surrounding spaces or a trailing /, so the device key is found under the same address."""
    return address.strip().rstrip("/")


# ---- checks (save nothing) -------------------------------------------------------


def _trouble(address, error, doing):
    """The sentence for a website that answered with an error page (e.g. a host's "service suspended" page: its HTML
    goes to the log only) or couldn't be reached."""
    if isinstance(error, UnexpectedAnswer):
        log.warning("%s: %s", doing, error)
        return Result(False, f"The website at {address} isn't working right now (HTTP {error.status}). "
                             "Try again later. Nothing was saved.")
    return Result(False, f"Couldn't reach the web app: {error} Nothing was saved.")


def check_site(address, key, tools):
    """Whether the website at `address` accepts this device key."""
    protect(key)
    address = _clean(address)
    try:
        check_server_url(address)
    except ValueError as error:
        return Result(False, str(error))
    if not key:
        return Result(False, "Paste the device key from the Devices page. Nothing was saved.")
    try:
        tools.make_server(address, key).check()
    except DeviceKeyRejected:
        return Result(False, "The web app rejected this device key. Create a new one on the Devices page. "
                             "Nothing was saved.")
    except ServerError as error:
        return _trouble(address, error, "Checking the device key")
    return Result(True, SITE_ACCEPTED)


def is_device_key(text):
    """Whether `text`, without the spaces or line break around it, has a device key's exact shape (the website's
    DeviceKeys.java). Text of any other shape is never sent to the website."""
    return bool(DEVICE_KEY.fullmatch((text or "").strip()))


def check_edusoft(student_id, password, tools):
    """One EduSoft login attempt."""
    protect(password)
    if not (student_id and password):
        return Result(False, "Enter your student ID and password. Nothing was saved.")
    try:
        tools.make_edusoft().login(student_id, password)
    except BadCredentials:
        return Result(False, "EduSoft rejected the student ID or password. Nothing was saved.")
    except ExtraVerification:
        return Result(False, "EduSoft asked for extra verification (CAPTCHA or code), so automatic sync can't be "
                             "used. Nothing was saved. You can still use `sla-agent import` with pages saved from "
                             "your browser.")
    except AgentError as error:
        return Result(False, f"Couldn't check your EduSoft login: {error} Nothing was saved; try again later.")
    return Result(True, "EduSoft accepted your login.")


# ---- saving -------------------------------------------------------------------------


def _save(state, change):
    """Apply `change` to the state as it is saved now, not to `state` as it was read before a check that took
    seconds (a sync may have saved since), save it, and bring `state` up to date."""
    current = load_state()
    change(current)
    save_state(current)
    for field in fields(State):
        setattr(state, field.name, getattr(current, field.name))


def _keep_site(state, address, key):
    address = _clean(address)
    if state.server_url and state.server_url != address:
        credentials.forget(None, state.server_url)
    credentials.save_device_key(address, key)
    state.server_url = address


def _keep_edusoft(state, student_id, password):
    if state.student_id and state.student_id != student_id:
        credentials.forget(state.student_id, None)
    credentials.save_edusoft(student_id, password)
    state.student_id, state.paused = student_id, None


def save_site_and_edusoft(state, address, key, student_id, password):
    """Save a checked website connection and EduSoft login together (a first setup)."""

    def keep(current):
        _keep_site(current, address, key)
        _keep_edusoft(current, student_id, password)

    _save(state, keep)
    return Result(True, SAVED)


def change_site(state, address, key, tools):
    """Check, then save, a new website address or device key."""
    checked = check_site(address, key, tools)
    if not checked.ok:
        return checked
    _save(state, lambda current: _keep_site(current, address, key))
    return Result(True, SITE_SAVED.format(state.server_url))


# ---- Connect (spec 2026-10-04-connect-button-design.md) ---------------------------------------------------


def computer_name():
    """This laptop's name on the Devices page: Windows' computer name (e.g. LAPTOP-KHANG), else "My laptop"."""
    return os.environ.get("COMPUTERNAME", "").strip() or "My laptop"


def connect_laptop(address, connection, tools, wait=CONNECT_WAIT):
    """Connect: open the website's Connect page for `connection` (connect.Connection), wait for the browser to come
    back, trade the code for a device key and check it, as a pasted key is checked. Saves nothing.

    Returns (Result, key); the key is "" unless the result is ok. A connection closed meanwhile (Connect pressed
    again, the page left, the window closed) gives an empty message: nobody shows it."""
    address = _clean(address)
    try:
        check_server_url(address)
    except ValueError as error:
        connection.close()
        return Result(False, str(error)), ""
    tools.open_browser(connection.url(address, computer_name()))
    try:
        code = connection.wait(wait)
    except Cancelled:
        return Result(False, CONNECT_CANCELLED), ""
    except TimedOut:
        return Result(False, CONNECT_TIMED_OUT), ""
    except Closed:
        return Result(False, ""), ""
    protect(code)
    protect(connection.verifier)
    try:
        connected = tools.connect(address, code, connection.verifier)
    except ConnectRefused:
        return Result(False, CONNECT_REFUSED), ""
    except ServerError as error:
        return _trouble(address, error, "Connecting"), ""
    protect(connected.key)
    checked = check_site(address, connected.key, tools)
    if not checked.ok:
        return checked, ""
    return Result(True, f"Connected to {connected.email}."), connected.key


def connect_site(state, address, connection, tools):
    """Accounts → Website → Connect: connect, then save the new key at once, as change_site does."""
    result, key = connect_laptop(address, connection, tools)
    if not result.ok:
        return result
    _save(state, lambda current: _keep_site(current, address, key))
    return Result(True, SITE_SAVED.format(state.server_url))


def change_edusoft(state, student_id, password, tools):
    """Check, then save, the EduSoft login; a new student ID forgets the old one's password."""
    checked = check_edusoft(student_id, password, tools)
    if not checked.ok:
        return checked
    _save(state, lambda current: _keep_edusoft(current, student_id, password))
    return Result(True, SAVED)


def change_blackboard(state, username, password, tools):
    """Check, then save, the Blackboard login (one attempt)."""
    protect(password)
    if not (username and password):
        return Result(False, "Blackboard username and password are both needed. Nothing was saved.")
    blackboard = tools.make_blackboard()
    try:
        blackboard.login(username, password)
    except BadCredentials:
        return Result(False, "Blackboard rejected the username or password. Nothing was saved.")
    except ExtraVerification:
        return Result(False, "Blackboard asked for extra verification, so automatic Blackboard sync can't be "
                             "used. Nothing was saved.")
    except AgentError as error:
        return Result(False, f"Couldn't check your Blackboard login: {error} Nothing was saved; try again later.")
    finally:
        blackboard.logout()

    def keep(current):
        if current.blackboard_username and current.blackboard_username != username:
            credentials.forget(None, None, current.blackboard_username)
        credentials.save_blackboard(username, password)
        current.blackboard_username, current.blackboard_paused = username, None

    _save(state, keep)
    return Result(True, BLACKBOARD_SAVED)


def outlook_accounts(tools):
    """(the accounts in classic Outlook, None), or ([], what's wrong). It may start a hidden Outlook for a moment, so
    the Outlook page asks it only once someone has signed in to Outlook (outlook_reader.classic_outlook)."""
    try:
        found = tools.find_outlook_accounts()
    except AgentError as error:
        return [], str(error)
    if not found:
        return [], "Classic Outlook has no account yet."
    return list(found), None


def choose_outlook(state, address, tools):
    """Read this account's Inbox at each sync, and add the sla-mail: link type."""

    def keep(current):
        current.outlook_account = address

    _save(state, keep)
    on = (f"Outlook is on: each sync reads the Inbox of {address}, sorts it on this laptop and uploads only the "
          "results, never the text.")
    try:
        tools.register_mail_link(tools.program())
    except (ImportError, OSError) as error:  # not Windows, or the registry refused
        return Result(True, on, (f"Couldn't add the sla-mail: link type ({error}). Mailbox's \"Open in Outlook\" "
                                 "won't open emails on this laptop; use \"Outlook on the web\" instead.",))
    return Result(True, on)


# ---- automatic sync -----------------------------------------------------------------


def _links_and_shortcuts(tools, program):
    """The sla-agent: and sla-mail: link types and the shortcuts, pointing at `program`; a note for each part that
    failed."""
    notes = []
    for register, name in ((tools.register_window_link, "sla-agent:"), (tools.register_mail_link, "sla-mail:")):
        try:
            register(program)
        except (ImportError, OSError) as error:  # not Windows, or the registry refused
            notes.append(f"Couldn't add the {name} link type ({error}).")
    try:
        tools.make_shortcuts(program, agent_home())
    except Exception as error:  # pywin32 raises its own com_error, not an OSError
        log.warning("Couldn't make the shortcuts: %s", error)
        notes.append(f"Couldn't make the Start menu entries ({error.__class__.__name__}). "
                     "The Accounts page on the website opens this window too.")
    return notes


def turn_on_sync(tools):
    """The sla-agent: and sla-mail: link types, the shortcuts and the scheduled task, all pointing at
    launcher.program() (the built app, or this folder's Python), so this also repairs them after the project folder
    moved. `ok` says whether the task was made; a link or shortcut that fails becomes a note, and nothing saved is
    undone."""
    program = tools.program()
    notes = _links_and_shortcuts(tools, program)
    try:
        tools.install_task(program, current_user(), folder=agent_home())
    except SchedulerError as error:
        return Result(False, str(error), tuple(notes))
    return Result(True, SYNC_ON, tuple(notes))


def add_window_links(tools):
    """A laptop set up before the window existed has neither the sla-agent: link type nor the shortcuts, so neither
    the website's button nor the Start menu can open the window. The first time the window opens there (from
    School-Life-Assistant.cmd), add them; a laptop not set up yet gets them from its first setup. Returns a note for
    each part that failed (tried again the next time)."""
    state = load_state()
    if not (state.server_url and state.student_id) or tools.has_window_link():
        return []
    return _links_and_shortcuts(tools, tools.program())


def point_links_and_shortcuts_here(tools):
    """The built app's first run of a new version (cli._updating): the sla-agent: and sla-mail: link types and the
    shortcuts point at this app again, so a laptop gets them where 0.2.0 couldn't make the shortcuts, or where they
    still start a Python set up from source. Returns a note for each part that failed."""
    return _links_and_shortcuts(tools, tools.program())


# ---- the Desktop icon -------------------------------------------------------------------


def add_desktop_icon(tools):
    """The School-Life-Assistant icon on the Desktop, which only the student asks for (spec
    2026-10-02-easy-install-design.md, 5): the setup's last page, or Accounts' Add."""
    try:
        tools.add_desktop_shortcut(tools.program(), agent_home())
    except Exception as error:  # pywin32 raises its own com_error, not an OSError
        log.warning("Couldn't add the Desktop icon: %s", error)
        return Result(False, f"Couldn't add the Desktop icon ({error.__class__.__name__}). "
                             "School-Life-Assistant is in the Start menu.")
    return Result(True, DESKTOP_ICON_ADDED)


def desktop_icon_on(tools):
    """Whether the School-Life-Assistant icon is on the Desktop; False when Windows can't say."""
    try:
        return bool(tools.has_desktop_shortcut())
    except Exception as error:  # pywin32's com_error, or not Windows
        log.debug("Couldn't look for the Desktop icon (%s)", error.__class__.__name__)
        return False


def sync_task_state(tools):
    """"on", "off" (no task), "nowhere" (the task starts a program that no longer exists: the folder moved) or
    "elsewhere" (this is the built app, but the task starts another copy, such as a Python set up from source)."""
    program = tools.task_program()
    if not program:
        return "off"
    if not Path(program).exists():
        return "nowhere"
    if launcher.frozen() and Path(program) != Path(tools.program()):
        return "elsewhere"
    return "on"


# ---- the setup pages ---------------------------------------------------------------


def _after_saving(work):
    """A first-setup step that runs once the website and EduSoft are saved: an unexpected error (a bug, not a login
    problem) stops only this step, so automatic sync is still turned on and the screen says what was saved."""
    try:
        return work()
    except Exception as error:  # anything unexpected; the details go to agent.log, secrets scrubbed
        log.exception("First setup: unexpected error")
        return Result(False, f"Something went wrong ({error.__class__.__name__}), so this part wasn't saved. "
                             "The details are in agent.log.")


def save_and_turn_on_sync(state, address, key, student_id, password, tools):
    """The setup's step 2 (spec 2026-10-02-easy-install-design.md, 3): one EduSoft login attempt; when it passes, the
    website's address and device key (accepted on step 1) and EduSoft are saved together, then automatic sync is
    turned on. Returns {"edusoft": Result} when the login failed and nothing was saved, else {"edusoft": Result,
    "sync": Result}."""
    protect(key)
    protect(password)
    checked = check_edusoft(student_id, password, tools)
    if not checked.ok:
        return {"edusoft": checked}
    saved = save_site_and_edusoft(state, address, key, student_id, password)
    return {"edusoft": saved, "sync": _after_saving(lambda: turn_on_sync(tools))}
