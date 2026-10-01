"""The School-Life-Assistant window (spec 2026-10-01-accounts-window-design.md, 3): set up this laptop the first
time, then see and change its accounts, with no terminal.

The screens only collect what the student types and show the answers; accounts.py checks and saves. Each check runs
on a worker thread so the window never freezes, and its answer comes back through Tk's event loop, since Tk may only
be used from its own thread (tests pass `run=run_at_once`). One window at a time: a second start brings the open one
forward (claim_single_window)."""

import logging
import queue
import threading
import tkinter as tk
import webbrowser
from datetime import datetime
from tkinter import ttk

from sla_agent import accounts
from sla_agent.accounts import Result, SetupForm
from sla_agent.log import protect
from sla_agent.state import load_state

log = logging.getLogger(__name__)

TITLE = "School-Life-Assistant"
MUTEX = "SchoolLifeAssistant-Window"
DEFAULT_ADDRESS = "http://localhost:5000"
NO_OUTLOOK = "Don't read Outlook"
LOOKING = "Looking for classic Outlook…"
OUTLOOK_HELP = "Open Outlook (classic), sign in, wait for \"All folders are up to date\", then press Refresh."
CHECKING = "Checking…"
DONE = "Done. This laptop checks in every minute; everything syncs every 30 minutes."
EDUSOFT_PAUSES = {"bad_credentials": "paused: wrong student ID or password",
                  "extra_verification": "paused: EduSoft asked for extra verification"}
BLACKBOARD_PAUSES = {"bad_credentials": "paused: wrong username or password",
                     "extra_verification": "paused: Blackboard asked for extra verification"}
SYNC_STATES = {"on": "on: every minute", "off": "off", "nowhere": "points to a Python that no longer exists"}
SECRETS = ("key", "password", "bb_password")


# ---- running checks ---------------------------------------------------------------------


def attempt(work):
    """work(), or a Result saying something went wrong; the details go to agent.log (secrets scrubbed)."""
    try:
        return work()
    except Exception as error:  # anything unexpected is shown on screen, never a crash
        log.exception("Accounts window: unexpected error")
        return Result(False, f"Something went wrong ({error.__class__.__name__}). Nothing was saved.")


def run_at_once(work, done):
    done(attempt(work))


def run_in_background(root):
    """A runner that does `work` on a worker thread and calls `done` with its answer on Tk's thread. The thread is
    not a daemon: closing the window during a check lets the check finish and save."""

    def run(work, done):
        answers = queue.Queue(maxsize=1)
        threading.Thread(target=lambda: answers.put(attempt(work)), name="accounts-check").start()

        def collect():
            try:
                answer = answers.get_nowait()
            except queue.Empty:
                root.after(100, collect)
                return
            done(answer)

        root.after(100, collect)

    return run


# ---- small pieces --------------------------------------------------------------------------


def mark(result):
    return ("✓ " if result.ok else "✗ ") + "\n".join((result.message, *result.notes))


def heading(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 14, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 8))


def section(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 10, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(12, 2))


def field(parent, label, variable, row, secret=False):
    ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 8), pady=2)
    ttk.Entry(parent, textvariable=variable, show="•" if secret else "", width=40).grid(
        row=row, column=1, sticky="ew", pady=2)


def answer_line(parent, variable, row):
    ttk.Label(parent, textvariable=variable, wraplength=500, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w")


def local_time(iso):
    return datetime.fromisoformat(iso).astimezone().strftime("%d/%m %H:%M")


# ---- the window ----------------------------------------------------------------------------


class App:
    """The window: the first-time form (SetupScreen) or Accounts (AccountsScreen), rebuilt after each save."""

    def __init__(self, root, tools, run=None, notice=""):
        self.root, self.tools = root, tools
        self.run = run or run_in_background(root)
        root.title(TITLE)
        root.minsize(560, 0)
        root.columnconfigure(0, weight=1)
        self.body = None
        self.screen = None
        self.show(notice)

    def show(self, notice=""):
        if self.body is not None:
            self.body.destroy()
        self.body = ttk.Frame(self.root, padding=16)
        self.body.grid(row=0, column=0, sticky="nsew")
        self.body.columnconfigure(1, weight=1)
        state = load_state()
        if state.server_url and state.student_id:
            self.screen = AccountsScreen(self, state, notice)
        else:
            self.screen = SetupScreen(self, state)


class OutlookPicker:
    """A dropdown of the accounts in classic Outlook, looked for in the background, with Refresh. The first look
    asks only an Outlook that is already open: opening the window never starts Outlook (or, where classic Outlook was
    never set up, its first-run wizard). Refresh, the student's choice, may start it."""

    def __init__(self, app, frame, variable, row, allow_none):
        self.app, self.variable, self.allow_none = app, variable, allow_none
        ttk.Label(frame, text="Account").grid(row=row, column=0, sticky="w", padx=(0, 8))
        self.box = ttk.Combobox(frame, textvariable=variable, state="readonly", width=38)
        self.box.grid(row=row, column=1, sticky="ew")
        self.refresh_button = ttk.Button(frame, text="Refresh", command=self.refresh)
        self.refresh_button.grid(row=row, column=2, padx=(8, 0))
        self.note = tk.StringVar(frame)
        answer_line(frame, self.note, row + 1)
        self.refresh(start=False)

    def refresh(self, start=True):
        self.note.set(LOOKING)
        self.refresh_button.state(["disabled"])
        self.app.run(lambda: accounts.outlook_accounts(self.app.tools, start=start), self.found)

    def found(self, answer):
        if not self.box.winfo_exists():  # the form was saved and replaced meanwhile
            return
        self.refresh_button.state(["!disabled"])
        found, problem = ([], answer.message) if isinstance(answer, Result) else answer
        choices = ([NO_OUTLOOK] if self.allow_none else []) + found
        self.box["values"] = choices
        if self.variable.get() not in choices:
            self.variable.set(choices[0] if choices else "")
        self.note.set(f"{problem} {OUTLOOK_HELP}" if problem else "")


class SetupScreen:
    """First time: one form for the website, EduSoft, Blackboard (optional) and Outlook (optional) (spec 3.1)."""

    def __init__(self, app, state):
        self.app = app
        frame = app.body
        names = ("address", "key", "student_id", "password", "bb_user", "bb_password", "outlook")
        self.values = {name: tk.StringVar(frame) for name in names}
        self.values["address"].set(state.server_url or DEFAULT_ADDRESS)
        self.values["student_id"].set(state.student_id or "")
        self.values["outlook"].set(NO_OUTLOOK)
        self.answers = {name: tk.StringVar(frame) for name in ("site", "edusoft", "blackboard", "outlook", "sync")}

        heading(frame, "Set up this laptop", 0)
        section(frame, "Website", 1)
        field(frame, "Address", self.values["address"], 2)
        field(frame, "Device key", self.values["key"], 3, secret=True)
        ttk.Button(frame, text="Get a key", command=self.get_key).grid(row=3, column=2, padx=(8, 0))
        answer_line(frame, self.answers["site"], 4)
        section(frame, "EduSoft", 5)
        field(frame, "Student ID", self.values["student_id"], 6)
        field(frame, "Password", self.values["password"], 7, secret=True)
        answer_line(frame, self.answers["edusoft"], 8)
        section(frame, "Blackboard (optional)", 9)
        field(frame, "Username", self.values["bb_user"], 10)
        field(frame, "Password", self.values["bb_password"], 11, secret=True)
        answer_line(frame, self.answers["blackboard"], 12)
        section(frame, "Outlook (optional)", 13)
        self.outlook = OutlookPicker(app, frame, self.values["outlook"], 14, allow_none=True)
        answer_line(frame, self.answers["outlook"], 16)
        self.save_button = ttk.Button(frame, text="Check and save", command=self.save)
        self.save_button.grid(row=17, column=0, columnspan=3, sticky="e", pady=(16, 0))
        answer_line(frame, self.answers["sync"], 18)

    def get_key(self):
        webbrowser.open(self.values["address"].get().strip().rstrip("/") + "/school/devices")

    def form(self):
        value = {name: variable.get() for name, variable in self.values.items()}
        for secret in SECRETS:
            protect(value[secret])
        outlook = "" if value["outlook"] == NO_OUTLOOK else value["outlook"]
        return SetupForm(address=value["address"].strip(), key=value["key"].strip(),
                         student_id=value["student_id"].strip(), password=value["password"],
                         bb_user=value["bb_user"].strip(), bb_password=value["bb_password"], outlook=outlook)

    def save(self):
        form = self.form()
        for answer in self.answers.values():
            answer.set("")
        self.answers["sync"].set(CHECKING)
        self.save_button.state(["disabled"])
        self.app.run(lambda: accounts.first_setup(load_state(), form, self.app.tools), self.saved)

    def saved(self, results):
        self.save_button.state(["!disabled"])
        if isinstance(results, Result):  # something unexpected went wrong
            self.answers["sync"].set(mark(results))
            return
        self.answers["sync"].set("")
        for step, result in results.items():
            self.answers[step].set(mark(result))
        if not results.get("edusoft", Result(False, "")).ok:
            return  # nothing saved: the form stays filled in to correct
        for secret in SECRETS:
            self.values[secret].set("")
        lines = [DONE] if results["sync"].ok else []
        lines += [mark(result) for step, result in results.items()
                  if step in ("blackboard", "outlook", "sync") and (not result.ok or result.notes)]
        self.app.show(notice="\n".join(lines))


class AccountsScreen:
    """Set up: one row per account with Change (spec 3.2); Change opens that account's fields under its row."""

    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
        frame = app.body
        heading(frame, "Accounts", 0)
        self.notice = tk.StringVar(frame, notice)
        answer_line(frame, self.notice, 1)
        self.values, self.answers, self.buttons, self.boxes = {}, {}, {}, {}
        self.editor = None
        for index, row in enumerate(self.ROWS):
            box = ttk.Frame(frame)
            box.grid(row=2 + index, column=0, columnspan=3, sticky="ew", pady=(8, 0))
            box.columnconfigure(1, weight=1)
            ttk.Label(box, text=self.NAMES[row], width=16, font=("Segoe UI", 10, "bold")).grid(
                row=0, column=0, sticky="w")
            self.values[row] = tk.StringVar(box, self.describe(row))
            ttk.Label(box, textvariable=self.values[row]).grid(row=0, column=1, sticky="w")
            label = self.button_label(row)
            if label:
                self.buttons[row] = ttk.Button(box, text=label, command=lambda row=row: self.open(row))
                self.buttons[row].grid(row=0, column=2)
            self.answers[row] = tk.StringVar(box)
            answer_line(box, self.answers[row], 2)
            self.boxes[row] = box

    def describe(self, row):
        state = self.state
        if row == "site":
            last = state.last_result
            when = f"; last sync {last['status']} {local_time(last['at'])}" if last else "; never synced"
            return state.server_url + when
        if row == "edusoft":
            paused = state.paused
            return f"{state.student_id}: " + (EDUSOFT_PAUSES.get(paused, f"paused ({paused})") if paused else "on")
        if row == "blackboard":
            if not state.blackboard_username:
                return "not set up"
            paused = state.blackboard_paused
            return f"{state.blackboard_username}: " + (
                BLACKBOARD_PAUSES.get(paused, f"paused ({paused})") if paused else "on")
        if row == "outlook":
            return state.outlook_account or "not set up"
        return SYNC_STATES[self.sync_state]

    def button_label(self, row):
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
        if (row == "blackboard" and not self.state.blackboard_username) or (
                row == "outlook" and not self.state.outlook_account):
            return "Set up"
        return "Change"

    def open(self, row):
        if row == "sync":
            self.buttons["sync"].state(["disabled"])
            self.answers["sync"].set(CHECKING)
            self.app.run(lambda: accounts.turn_on_sync(self.app.tools),
                         lambda result: self.app.show(notice=mark(result)))
            return
        for button in self.buttons.values():
            button.state(["disabled"])
        self.editor = Editor(self, row)

    def closed(self):
        self.editor = None
        for button in self.buttons.values():
            button.state(["!disabled"])


class Editor:
    """One account's fields under its row, with Check and save and Cancel."""

    FIELDS = {
        "site": (("Address", "address", False), ("Device key", "key", True)),
        "edusoft": (("Student ID", "student_id", False), ("Password", "password", True)),
        "blackboard": (("Username", "username", False), ("Password", "password", True)),
        "outlook": (),
    }

    def __init__(self, screen, row):
        self.screen, self.row = screen, row
        state = screen.state
        self.frame = ttk.Frame(screen.boxes[row], padding=(16, 4, 0, 4))
        self.frame.grid(row=1, column=0, columnspan=3, sticky="ew")
        self.frame.columnconfigure(1, weight=1)
        start = {"address": state.server_url or "", "student_id": state.student_id or "",
                 "username": state.blackboard_username or ""}
        self.values = {}
        for index, (label, name, secret) in enumerate(self.FIELDS[row]):
            self.values[name] = tk.StringVar(self.frame, start.get(name, ""))
            field(self.frame, label, self.values[name], index, secret=secret)
        if row == "outlook":
            self.values["outlook"] = tk.StringVar(self.frame, state.outlook_account or "")
            OutlookPicker(screen.app, self.frame, self.values["outlook"], 0, allow_none=False)
        buttons = ttk.Frame(self.frame)
        buttons.grid(row=5, column=0, columnspan=3, sticky="e", pady=(4, 0))
        self.save_button = ttk.Button(buttons, text="Check and save", command=self.save)
        self.save_button.grid(row=0, column=0)
        self.cancel_button = ttk.Button(buttons, text="Cancel", command=self.cancel)
        self.cancel_button.grid(row=0, column=1, padx=(8, 0))

    def save(self):
        value = {name: variable.get() for name, variable in self.values.items()}
        protect(value.get("key"))
        protect(value.get("password"))
        if self.row == "outlook" and not value["outlook"]:
            self.screen.answers["outlook"].set("✗ Choose an account first.")
            return
        tools, state = self.screen.app.tools, load_state()
        work = {
            "site": lambda: accounts.change_site(state, value["address"], value["key"].strip(), tools),
            "edusoft": lambda: accounts.change_edusoft(state, value["student_id"].strip(), value["password"], tools),
            "blackboard": lambda: accounts.change_blackboard(state, value["username"].strip(), value["password"],
                                                             tools),
            "outlook": lambda: accounts.choose_outlook(state, value["outlook"], tools),
        }[self.row]
        self.screen.answers[self.row].set(CHECKING)
        self.save_button.state(["disabled"])
        self.cancel_button.state(["disabled"])
        self.screen.app.run(work, self.saved)

    def saved(self, result):
        if result.ok:
            self.screen.app.show(notice=mark(result))
            return
        self.screen.answers[self.row].set(mark(result))
        self.save_button.state(["!disabled"])
        self.cancel_button.state(["!disabled"])

    def cancel(self):
        self.frame.destroy()
        self.screen.answers[self.row].set("")
        self.screen.closed()


# ---- starting -----------------------------------------------------------------------------

_held = []  # the mutex handle, kept for the life of this process


def _first_window(name):
    """True when no other window holds the named mutex (Windows only; elsewhere always True)."""
    try:
        import win32api
        import win32event
        import winerror
    except ImportError:
        return True
    handle = win32event.CreateMutex(None, False, name)
    if win32api.GetLastError() == winerror.ERROR_ALREADY_EXISTS:
        return False
    _held.append(handle)
    return True


def _bring_forward(title):
    try:
        import win32con
        import win32gui

        found = win32gui.FindWindow(None, title)
        if found:
            win32gui.ShowWindow(found, win32con.SW_RESTORE)
            win32gui.SetForegroundWindow(found)
    except Exception as error:  # ImportError off Windows; pywin32's error when Windows refuses the focus change
        log.debug("Couldn't bring the open window forward (%s)", error.__class__.__name__)


def claim_single_window(first=None, bring_forward=None):
    """True when this is the only window; otherwise brings the open one to the front and returns False."""
    if (first or _first_window)(MUTEX):
        return True
    (bring_forward or _bring_forward)(TITLE)
    return False


def _sharp_on_high_dpi():
    try:
        import ctypes

        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):  # not Windows, or an old one
        pass


def main(tools, link=None):
    """What `sla-agent window` runs (also the sla-agent: link, the shortcuts and School-Life-Assistant.cmd)."""
    if link:
        log.debug("Opened from %s", link)
    if not claim_single_window():
        return 0
    notes = accounts.add_window_links(tools)  # a laptop set up before the window existed
    _sharp_on_high_dpi()
    root = tk.Tk()
    root.report_callback_exception = lambda *exc: log.error("Accounts window error", exc_info=exc)
    App(root, tools, notice="\n".join(notes))
    root.mainloop()
    return 0
