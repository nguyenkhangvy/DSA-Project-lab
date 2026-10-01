"""Checking and saving this laptop's accounts: the website connection, EduSoft, Blackboard and Outlook, and turning
on automatic sync (spec 2026-10-01-accounts-window-design.md, 4.1).

`sla-agent setup` (terminal) and the School-Life-Assistant window both use it. Nothing here prints, asks or draws:
each step returns a Result whose message the caller shows, in the words `sla-agent setup` has always used. A login
is checked once before it is saved, and one that fails changes nothing. Passwords and the device key go only to
Windows Credential Manager (credentials.py)."""

import logging
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Callable

from sla_agent import credentials
from sla_agent.errors import AgentError, BadCredentials, DeviceKeyRejected, ExtraVerification, ServerError
from sla_agent.log import protect
from sla_agent.scheduler import SchedulerError, current_user
from sla_agent.server_client import check_server_url
from sla_agent.state import State, agent_home, load_state, save_state

log = logging.getLogger(__name__)

SAVED = "Saved. Your password is in Windows Credential Manager, not in any file."
BLACKBOARD_SAVED = "Blackboard saved. Its password is in Windows Credential Manager too."
SYNC_ON = "Automatic sync is on: this laptop checks in every minute while you're logged in."


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
    find_open_outlook_accounts: Callable  # the same, asking only an Outlook that is already open
    python: Callable  # () -> the windowless Python the task, the links and the shortcuts start
    install_task: Callable  # (python, user, folder=) ; raises SchedulerError
    task_program: Callable  # () -> the program the scheduled task starts, or None
    register_mail_link: Callable  # (python)
    register_window_link: Callable  # (python)
    make_shortcuts: Callable  # (python, folder)
    has_window_link: Callable  # () -> whether the sla-agent: link type is registered


@dataclass(frozen=True)
class SetupForm:
    """The first-time form. Blackboard and Outlook are optional: empty means skipped."""

    address: str
    key: str
    student_id: str
    password: str
    bb_user: str = ""
    bb_password: str = ""
    outlook: str = ""


def _clean(address):
    """Saved without surrounding spaces or a trailing /, so the device key is found under the same address."""
    return address.strip().rstrip("/")


# ---- checks (save nothing) -------------------------------------------------------


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
        return Result(False, f"Couldn't reach the web app: {error} Nothing was saved.")
    return Result(True, "The web app accepted this device key.")


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
    return Result(True, f"Saved. This laptop now syncs with {state.server_url}.")


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


def outlook_accounts(tools, start=True):
    """(the accounts in classic Outlook, None), or ([], what's wrong). start=False asks only an Outlook that is
    already open, so it never starts Outlook (or its first-run wizard where classic Outlook was never set up)."""
    try:
        found = tools.find_outlook_accounts() if start else tools.find_open_outlook_accounts()
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
        tools.register_mail_link(tools.python())
    except (ImportError, OSError) as error:  # not Windows, or the registry refused
        return Result(True, on, (f"Couldn't add the sla-mail: link type ({error}). Mailbox's \"Open in Outlook\" "
                                 "won't open emails on this laptop; use \"Outlook on the web\" instead.",))
    return Result(True, on)


# ---- automatic sync -----------------------------------------------------------------


def _links_and_shortcuts(tools, python):
    """The sla-agent: and sla-mail: link types and the shortcuts, pointing at `python`; a note for each part that
    failed."""
    notes = []
    for register, name in ((tools.register_window_link, "sla-agent:"), (tools.register_mail_link, "sla-mail:")):
        try:
            register(python)
        except (ImportError, OSError) as error:  # not Windows, or the registry refused
            notes.append(f"Couldn't add the {name} link type ({error}).")
    try:
        tools.make_shortcuts(python, agent_home())
    except Exception as error:  # pywin32 raises its own com_error, not an OSError
        log.warning("Couldn't make the shortcuts: %s", error)
        notes.append(f"Couldn't make the Desktop and Start menu shortcuts ({error.__class__.__name__}). "
                     "School-Life-Assistant.cmd in the project folder opens this window too.")
    return notes


def turn_on_sync(tools):
    """The sla-agent: and sla-mail: link types, the shortcuts and the scheduled task, all pointing at this folder's
    Python (so this also repairs them after the project folder moved). `ok` says whether the task was made; a link
    or shortcut that fails becomes a note, and nothing saved is undone."""
    python = tools.python()
    notes = _links_and_shortcuts(tools, python)
    try:
        tools.install_task(python, current_user(), folder=agent_home())
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
    return _links_and_shortcuts(tools, tools.python())


def sync_task_state(tools):
    """"on", "off" (no task) or "nowhere" (the task starts a Python that no longer exists: the folder moved)."""
    program = tools.task_program()
    if not program:
        return "off"
    return "on" if Path(program).exists() else "nowhere"


# ---- the first-time form ---------------------------------------------------------------


def _after_saving(work):
    """A first-setup step that runs once the website and EduSoft are saved: an unexpected error (a bug, not a login
    problem) stops only this step, so automatic sync is still turned on and the screen says what was saved."""
    try:
        return work()
    except Exception as error:  # anything unexpected; the details go to agent.log, secrets scrubbed
        log.exception("First setup: unexpected error")
        return Result(False, f"Something went wrong ({error.__class__.__name__}), so this part wasn't saved. "
                             "The details are in agent.log.")


def first_setup(state, form, tools):
    """Check and save a first setup in the order of spec 3.1: the website and EduSoft, saved together only when
    both pass; then Blackboard and Outlook when filled in; then automatic sync. Returns {step: Result} for the steps
    that ran, in order: site, edusoft, blackboard, outlook, sync."""
    results = {"site": check_site(form.address, form.key, tools)}
    if not results["site"].ok:
        return results
    results["edusoft"] = check_edusoft(form.student_id, form.password, tools)
    if not results["edusoft"].ok:
        return results
    results["edusoft"] = save_site_and_edusoft(state, form.address, form.key, form.student_id, form.password)
    if form.bb_user or form.bb_password:
        results["blackboard"] = _after_saving(lambda: change_blackboard(state, form.bb_user, form.bb_password, tools))
    if form.outlook:
        results["outlook"] = _after_saving(lambda: choose_outlook(state, form.outlook, tools))
    results["sync"] = _after_saving(lambda: turn_on_sync(tools))
    return results
