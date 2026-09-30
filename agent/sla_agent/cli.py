"""sla-agent: the laptop side of School-Life-Assistant.

    sla-agent setup                  enter your details once; schedules automatic sync
    sla-agent setup --outlook        read your Inbox through classic Outlook at each sync
    sla-agent run                    what the scheduled task calls every 15 minutes
    sla-agent sync-now               sync right away
    sla-agent status                 show the last result and whether sync is paused
    sla-agent fetch --save-html DIR  save your EduSoft pages on this laptop (for building the readers)
    sla-agent import FOLDER          read pages saved by `fetch` (or your browser) and upload them
    sla-agent forget                 delete the saved secrets and the scheduled task
    sla-agent open-mail LINK         what Mailbox's "Open in Outlook" button runs: shows one email
"""

import argparse
import getpass
import json
import logging
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sla_contract.schema import EDUSOFT_SECTIONS, FinishRun

from sla_agent import credentials, mail_link
from sla_agent.blackboard_client import BlackboardClient
from sla_agent.blackboard_reader import read_blackboard
from sla_agent.edusoft_client import EduSoftClient
from sla_agent.errors import (
    AgentError,
    BadCredentials,
    DeviceKeyRejected,
    EmailNotFound,
    ExtraVerification,
    ParseError,
    RunInProgress,
    ServerError,
)
from sla_agent.iupay_client import IupayClient
from sla_agent.log import protect, setup_logging
from sla_agent.outlook_reader import (
    accounts,
    entry_id_from_link,
    open_email,
    open_outlook,
    read_outlook,
    with_time_limit,
)
from sla_agent.parsers import PARSERS
from sla_agent.parsers.registration import RegisteredCourse, parse_registered_courses
from sla_agent.scheduler import SchedulerError, current_user, install_task, remove_task, windowless_python
from sla_agent.server_client import ServerClient, check_server_url
from sla_agent.state import agent_home, load_state, save_state
from sla_agent.sync import PAUSE_MESSAGES, collect_iupay, everything_paused, run_sync

log = logging.getLogger(__name__)

MIN_MANUAL_GAP = timedelta(minutes=5)
NOT_SET_UP = "sla-agent isn't set up yet. Run `sla-agent setup` first."

# Replaced in tests.
ask = input
ask_secret = getpass.getpass


def make_edusoft():
    return EduSoftClient()


def make_server(url, key):
    return ServerClient(url, key)


def make_blackboard():
    return BlackboardClient()


def make_iupay():
    return IupayClient()


def find_outlook_accounts():
    return with_time_limit(lambda: accounts(open_outlook()))


def say(message):
    print(message)


def show_message(message):
    """A Windows message box: `open-mail` runs from the browser, without a console."""
    import ctypes

    ctypes.windll.user32.MessageBoxW(None, message, "School-Life-Assistant", 0x40)  # MB_ICONINFORMATION


def _now():
    return datetime.now(timezone.utc)


def _load():
    """(state, password, device key), or None if setup hasn't been done."""
    state = load_state()
    if not state.server_url or not state.student_id:
        return None
    password = credentials.load_edusoft(state.student_id)
    key = credentials.load_device_key(state.server_url)
    if not password or not key:
        return None
    return state, password, key


# ---- setup -------------------------------------------------------------------


def _setup_blackboard(state, username=None):
    """Ask for (or use) the Blackboard login, check it once, save it. Returns an exit code."""
    username = username or ask(f"Blackboard username [{state.blackboard_username or ''}]: ").strip()         or state.blackboard_username or ""
    password = ask_secret("Blackboard password (not shown): ")
    protect(password)
    if not (username and password):
        say("Blackboard username and password are both needed. Nothing was saved.")
        return 1
    say("Checking your Blackboard login (one attempt)...")
    blackboard = make_blackboard()
    try:
        blackboard.login(username, password)
    except BadCredentials:
        say("Blackboard rejected the username or password. Nothing was saved.")
        return 1
    except ExtraVerification:
        say("Blackboard asked for extra verification, so automatic Blackboard sync can't be used. Nothing was saved.")
        return 1
    except AgentError as error:
        say(f"Couldn't check your Blackboard login: {error} Nothing was saved; try again later.")
        return 1
    finally:
        blackboard.logout()
    if state.blackboard_username and state.blackboard_username != username:
        credentials.forget(None, None, state.blackboard_username)
    credentials.save_blackboard(username, password)
    state.blackboard_username, state.blackboard_paused = username, None
    save_state(state)
    say("Blackboard saved. Its password is in Windows Credential Manager too.")
    return 0


OUTLOOK_HOW_TO = ("Open Outlook (classic), sign in with your IU account, wait until it says "
                  "'All folders are up to date', then run `sla-agent setup --outlook` again.")


def _setup_outlook(state):
    """Choose the Outlook account whose Inbox each sync reads, and add the sla-mail: link type."""
    say("Looking for classic Outlook on this laptop...")
    try:
        found = find_outlook_accounts()
    except AgentError as error:
        say(f"{error} {OUTLOOK_HOW_TO}")
        return 1
    if not found:
        say(f"Classic Outlook has no account yet. {OUTLOOK_HOW_TO}")
        return 1
    if len(found) == 1:
        if ask(f"Read the Inbox of {found[0]}? [Y/n]: ").strip().lower() not in ("", "y", "yes"):
            say("Nothing was changed.")
            return 1
        address = found[0]
    else:
        for number, candidate in enumerate(found, 1):
            say(f"  {number}. {candidate}")
        answer = ask("Which account's Inbox should be read? Number: ").strip()
        if not answer.isdigit() or not 1 <= int(answer) <= len(found):
            say("Nothing was changed.")
            return 1
        address = found[int(answer) - 1]
    state.outlook_account = address
    save_state(state)
    try:
        mail_link.register(windowless_python())
    except OSError as error:
        say(f"Couldn't add the sla-mail: link type ({error}). Mailbox's \"Open in Outlook\" won't open emails "
            "on this laptop; use \"Outlook on the web\" instead.")
    say(f"Outlook is on: each sync reads the Inbox of {address}, sorts it on this laptop and uploads only the "
        "results, never the text.")
    return 0


def cmd_setup(args):
    state = load_state()
    if args.blackboard or args.outlook:
        if not state.server_url or not state.student_id:
            say(NOT_SET_UP)
            return 1
        return _setup_blackboard(state) if args.blackboard else _setup_outlook(state)
    server_url = ask(f"Web app address [{state.server_url or 'https://...'}]: ").strip() or state.server_url or ""
    try:
        check_server_url(server_url)
    except ValueError as error:
        say(str(error))
        return 1
    device_key = ask_secret("Device key (from the Devices page, not shown): ").strip()
    student_id = ask(f"EduSoft student ID [{state.student_id or ''}]: ").strip() or state.student_id or ""
    password = ask_secret("EduSoft password (not shown): ")
    protect(device_key)
    protect(password)
    if not (device_key and student_id and password):
        say("All answers are needed. Nothing was saved.")
        return 1

    say("Checking the device key with the web app...")
    try:
        make_server(server_url, device_key).check()
    except DeviceKeyRejected:
        say("The web app rejected this device key. Create a new one on the Devices page. Nothing was saved.")
        return 1
    except ServerError as error:
        say(f"Couldn't reach the web app: {error} Nothing was saved.")
        return 1

    say("Checking your EduSoft login (one attempt)...")
    try:
        make_edusoft().login(student_id, password)
    except BadCredentials:
        say("EduSoft rejected the student ID or password. Nothing was saved.")
        return 1
    except ExtraVerification:
        say("EduSoft asked for extra verification (CAPTCHA or code), so automatic sync can't be used. "
            "Nothing was saved. You can still use `sla-agent import` with pages saved from your browser.")
        return 1
    except AgentError as error:
        say(f"Couldn't check your EduSoft login: {error} Nothing was saved; try again later.")
        return 1

    if state.student_id and state.student_id != student_id:
        credentials.forget(state.student_id, None)
    credentials.save_edusoft(student_id, password)
    credentials.save_device_key(server_url, device_key)
    state.server_url, state.student_id, state.paused = server_url, student_id, None
    save_state(state)
    say("Saved. Your password is in Windows Credential Manager, not in any file.")

    blackboard_user = ask(f"Blackboard username (press Enter to skip) [{state.blackboard_username or ''}]: ").strip()
    if blackboard_user and _setup_blackboard(state, blackboard_user) != 0:
        say("EduSoft is saved; set up Blackboard later with `sla-agent setup --blackboard`.")

    if not args.no_schedule:
        try:
            install_task(windowless_python(), current_user(), folder=agent_home())
        except SchedulerError as error:
            say(f"{error} You can still sync by hand with `sla-agent sync-now`.")
            return 1
        say("Automatic sync is on: this laptop checks in every 15 minutes while you're logged in.")
    say("Run `sla-agent sync-now` to sync right away.")
    return 0


# ---- syncing -----------------------------------------------------------------


def _blackboard_login(state):
    """(client, password) when Blackboard is set up, else (None, None)."""
    if not state.blackboard_username:
        return None, None
    password = credentials.load_blackboard(state.blackboard_username)
    return (make_blackboard(), password) if password else (None, None)


def _sync(trigger, state, password, server):
    blackboard, blackboard_password = _blackboard_login(state)
    try:
        outcome = run_sync(trigger, state=state, edusoft=make_edusoft(), server=server, parsers=PARSERS,
                           password=password, now=_now(), blackboard=blackboard,
                           blackboard_password=blackboard_password, read_blackboard=read_blackboard,
                           read_outlook=read_outlook, iupay=make_iupay())
    except RunInProgress:
        say("A sync is already running.")
        return 0
    except DeviceKeyRejected as error:
        say(f"{error} Run `sla-agent setup` with a new key.")
        return 1
    except ServerError as error:
        log.warning("Web app problem: %s", error)
        say(f"Couldn't reach the web app: {error}")
        save_state(state)
        return 1
    save_state(state)
    say(outcome.message)
    return 0 if outcome.status in ("success", "partial") else 1


def cmd_run(args):
    loaded = _load()
    if loaded is None:
        say(NOT_SET_UP)
        return 1
    state, password, key = loaded
    server = make_server(state.server_url, key)
    try:
        decision = server.check()  # also tells the web app this laptop is alive
    except ServerError as error:
        log.warning("Check-in failed: %s", error)
        return 1
    if everything_paused(state):
        log.info("Automatic sync is paused for every system; not syncing")
        return 0
    if not decision.due:
        log.info("No sync due (%s)", decision.reason)
        return 0
    return _sync("manual" if decision.reason == "requested" else "scheduled", state, password, server)


def cmd_sync_now(args):
    loaded = _load()
    if loaded is None:
        say(NOT_SET_UP)
        return 1
    state, password, key = loaded
    if everything_paused(state):
        say(PAUSE_MESSAGES.get(state.paused, "Automatic sync is paused."))
        return 1
    if state.last_attempt_at and _now() - datetime.fromisoformat(state.last_attempt_at) < MIN_MANUAL_GAP:
        say("The last sync was less than 5 minutes ago. Please try again in a few minutes.")
        return 1
    return _sync("manual", state, password, make_server(state.server_url, key))


# ---- saved pages ----------------------------------------------------------------


def _file_name(section, part):
    """timetable + semester -> timetable-semester.html; exams + exams -> exams.html."""
    return f"{section}.html" if part == section else f"{section}-{part}.html"


def cmd_fetch(args):
    loaded = _load()
    if loaded is None:
        say(NOT_SET_UP)
        return 1
    state, password, _ = loaded
    if state.paused:
        say(PAUSE_MESSAGES.get(state.paused, "Automatic sync is paused."))
        return 1
    folder = Path(args.save_html)
    edusoft = make_edusoft()
    say("Reading EduSoft…")
    try:
        edusoft.login(state.student_id, password)
        files = {"home.html": edusoft.get_page("home")}
        for section in EDUSOFT_SECTIONS:
            for part, html in edusoft.read(section).items():
                if part != "term":
                    files[_file_name(section, part)] = html
        files["registration.html"] = edusoft.read("registration")["registration"]
    except (BadCredentials, ExtraVerification) as error:
        state.paused = error.code
        save_state(state)
        say(PAUSE_MESSAGES[error.code])
        return 1
    except AgentError as error:
        say(f"Couldn't read EduSoft: {error}")
        return 1
    blackboard, blackboard_password = _blackboard_login(state)
    if blackboard is not None and not state.blackboard_paused:
        try:  # this semester's courses, from the registration page just saved
            registered = parse_registered_courses({"registration": files["registration.html"]})
        except ParseError:
            registered = [RegisteredCourse(code, group) for code, group in state.registered_courses or []]
        blackboard.capture = {}
        say(f"Reading Blackboard ({len(registered)} courses). This can take a few minutes; please wait…")
        try:
            blackboard.login(state.blackboard_username, blackboard_password)
            read_blackboard(blackboard, registered)
        except AgentError as error:
            say(f"Couldn't read Blackboard: {error}")
        finally:
            blackboard.logout()
        files["blackboard-raw.json"] = json.dumps(blackboard.capture, ensure_ascii=False, indent=1)
    folder.mkdir(parents=True, exist_ok=True)
    for name, html in files.items():
        (folder / name).write_text(html, encoding="utf-8")
    say(f"Saved {len(files)} pages to {folder.resolve()}.\n"
        "They contain your personal data. Before sharing them, remove your name, student ID and "
        "date of birth. Never commit them to GitHub.")
    return 0


def _read_folder(folder, term):
    """Section results from pages saved by `fetch` (or from a browser, with the same file names)."""
    def load(section, part):
        path = folder / _file_name(section, part)
        return path.read_text(encoding="utf-8") if path.exists() else None

    found = {
        "timetable": {"weekly": load("timetable", "weekly"), "semester": load("timetable", "semester")},
        "exams": {"final": load("exams", "final"), "midterm": load("exams", "midterm")},
    }
    found["timetable"] = found["timetable"] if found["timetable"]["semester"] else None
    found = {name: pages for name, pages in found.items() if pages and any(pages.values())}

    results = {}
    for name, pages in found.items():
        try:
            if name == "exams":
                if term is None:
                    timetable = results.get("timetable", {}).get("data")
                    term = timetable.term_code if timetable else None
                if term is None:
                    raise ValueError("exams need the semester: add --term, e.g. --term 20261")
                pages = {"term": term, **pages}
            results[name] = {"status": "ok", "data": PARSERS[name](pages)}
        except ParseError as error:
            results[name] = {"status": "failed", "error_code": error.code, "error_message": str(error)[:500]}
    return results


def cmd_import(args):
    state = load_state()
    key = credentials.load_device_key(state.server_url) if state.server_url else None
    if not key:
        say(NOT_SET_UP)
        return 1
    folder = Path(args.folder)
    try:
        results = _read_folder(folder, args.term)
    except ValueError as error:
        say(f"Couldn't import: {error}")
        return 1
    if not results:
        say(f"No saved EduSoft pages found in {folder}. Use the file names that `sla-agent fetch` saves.")
        return 1
    if state.student_id:
        results["iupay"] = collect_iupay(state.student_id, make_iupay())  # IUPay needs no saved pages
    server = make_server(state.server_url, key)
    try:
        run_id = server.start("import")
        status = server.finish(run_id, FinishRun.model_validate(results))
    except ServerError as error:
        say(f"Couldn't upload: {error}")
        return 1
    say(f"Imported {', '.join(results)} ({status}).")
    return 0


# ---- status / forget ----------------------------------------------------------------


def cmd_status(args):
    state = load_state()
    if not state.server_url:
        say(NOT_SET_UP)
        return 1
    say(f"Web app:     {state.server_url}")
    say(f"Student ID:  {state.student_id}")
    if state.paused:
        say(f"EduSoft:     PAUSED. {PAUSE_MESSAGES.get(state.paused, '')}")
    else:
        say("EduSoft:     on")
    if not state.blackboard_username:
        say("Blackboard:  not set up (run `sla-agent setup --blackboard`)")
    elif state.blackboard_paused:
        say(f"Blackboard:  PAUSED ({state.blackboard_paused}). Run `sla-agent setup --blackboard`.")
    else:
        say("Blackboard:  on")
    if state.outlook_account:
        say(f"Outlook:     on ({state.outlook_account})")
    else:
        say("Outlook:     not set up (run `sla-agent setup --outlook`)")
    if state.last_result:
        say(f"Last sync:   {state.last_result['status']} at {state.last_result['at']}: {state.last_result['message']}")
    else:
        say("Last sync:   never")
    return 0


def cmd_forget(args):
    state = load_state()
    credentials.forget(state.student_id, state.server_url, state.blackboard_username)
    remove_task()
    try:
        mail_link.unregister()
    except (ImportError, OSError):  # not Windows, or already gone
        pass
    (agent_home() / "state.json").unlink(missing_ok=True)
    say("Removed your saved EduSoft password, the device key, the scheduled task and the sla-mail: link type "
        "from this laptop.")
    return 0


def cmd_open_mail(args):
    entry_id = entry_id_from_link(args.link)
    if entry_id is None:
        show_message("This isn't a School-Life-Assistant email link.")
        return 1
    try:
        open_email(entry_id)
    except EmailNotFound as error:
        show_message(str(error))
        return 1
    except AgentError as error:
        show_message(f"{error} Open Outlook (classic) and sign in, then try again.")
        return 1
    return 0


COMMANDS = {
    "setup": cmd_setup,
    "run": cmd_run,
    "sync-now": cmd_sync_now,
    "status": cmd_status,
    "fetch": cmd_fetch,
    "import": cmd_import,
    "forget": cmd_forget,
    "open-mail": cmd_open_mail,
}


def main(argv=None):
    parser = argparse.ArgumentParser(prog="sla-agent", description="School-Life-Assistant laptop sync agent.")
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("setup", help="enter your details once; schedules automatic sync")
    setup.add_argument("--no-schedule", action="store_true", help="don't create the scheduled task")
    setup.add_argument("--blackboard", action="store_true", help="set or change only the Blackboard login")
    setup.add_argument("--outlook", action="store_true", help="read your Inbox through classic Outlook")
    commands.add_parser("run", help="scheduled check-in: sync if the web app says it's due")
    commands.add_parser("sync-now", help="sync right away")
    commands.add_parser("status", help="show the last result and whether sync is paused")
    fetch = commands.add_parser("fetch", help="save your EduSoft pages on this laptop")
    fetch.add_argument("--save-html", metavar="FOLDER", required=True)
    importer = commands.add_parser("import", help="upload EduSoft pages saved in a folder")
    importer.add_argument("folder")
    importer.add_argument("--term", help="semester code for exam pages, e.g. 20261")
    commands.add_parser("forget", help="delete saved secrets and the scheduled task")
    open_mail = commands.add_parser("open-mail", help="show one email in Outlook (run by Mailbox's links)")
    open_mail.add_argument("link")

    args = parser.parse_args(argv)
    # Output piped or redirected on Windows uses cp1252; never crash on Vietnamese text.
    for stream in (sys.stdout, sys.stderr):
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    setup_logging()
    return COMMANDS[args.command](args)
