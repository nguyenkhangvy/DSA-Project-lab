"""The School-Life-Assistant window (spec 2026-10-01-accounts-window-design.md, 3; spec
2026-10-02-easy-install-design.md): the setup pages while this laptop isn't set up (setup_steps.py), then Accounts,
to see and change its accounts, with no terminal.

The screens only collect what the student types and show the answers; accounts.py checks and saves, each check on a
worker thread (window_parts.py). One window at a time: a second start brings the open one forward
(claim_single_window)."""

import logging
import tkinter as tk
from datetime import datetime
from tkinter import ttk

from sla_agent import __version__, accounts
from sla_agent.log import protect
from sla_agent.outlook_page import OutlookPage
from sla_agent.setup_steps import PASTE_INSTEAD, WAITING, SetupSteps
from sla_agent.state import load_state
from sla_agent.window_parts import CHECKING, answer_line, field, heading, mark, run_in_background

log = logging.getLogger(__name__)

TITLE = "School-Life-Assistant"
MUTEX = "SchoolLifeAssistant-Window"
EDUSOFT_PAUSES = {"bad_credentials": "paused: wrong student ID or password",
                  "extra_verification": "paused: EduSoft asked for extra verification"}
BLACKBOARD_PAUSES = {"bad_credentials": "paused: wrong username or password",
                     "extra_verification": "paused: Blackboard asked for extra verification"}
SYNC_STATES = {"on": "on: every minute", "off": "off", "nowhere": "points to a program that no longer exists",
               "elsewhere": "runs another copy of School-Life-Assistant"}


def local_time(iso):
    return datetime.fromisoformat(iso).astimezone().strftime("%d/%m %H:%M")


# ---- the window ----------------------------------------------------------------------------


class App:
    """The window: the setup pages (setup_steps.SetupSteps) or Accounts (AccountsScreen), rebuilt after each save. It
    holds Connect's listener, one at a time: a new press of Connect closes the old one, and rebuilding, leaving step 1,
    cancelling the Website editor or closing the window stops it (spec 2026-10-04-connect-button-design.md, 5.3)."""

    def __init__(self, root, tools, run=None, notice=""):
        self.root, self.tools = root, tools
        self.run = run or run_in_background(root)
        self.connection = None  # Connect's listener (connect.Connection), while it waits
        root.title(TITLE)
        root.minsize(560, 0)
        root.columnconfigure(0, weight=1)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.body = None
        self.screen = None
        self.show(notice)

    def show(self, notice=""):
        self.stop_listening()
        if self.body is not None:
            self.body.destroy()
        self.body = ttk.Frame(self.root, padding=16)
        self.body.grid(row=0, column=0, sticky="nsew")
        self.body.columnconfigure(1, weight=1)
        state = load_state()
        if state.server_url and state.student_id:
            self.screen = AccountsScreen(self, state, notice)
        else:
            self.screen = SetupSteps(self, state)

    def listen(self):
        """A new listener for Connect (tools.listen), closing the one before. Raises OSError when it can't listen."""
        self.stop_listening()
        self.connection = self.tools.listen()
        return self.connection

    def stop_listening(self):
        """Ends Connect's wait, if one is running: its answer comes back Closed and is dropped."""
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def bring_forward(self):
        """Back in front of the browser after Connect (Windows may only flash the taskbar button)."""
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(200, lambda: self.root.attributes("-topmost", False))

    def close(self):
        """The window's ✕. Connect's wait runs on a thread that isn't a daemon, so it is ended first; otherwise the app
        would stay in the background until the wait ran out."""
        self.stop_listening()
        self.root.destroy()


class AccountsScreen:
    """Set up: one row per account with Change (spec 3.2); Change opens that account's fields under its row. Repair
    and the Desktop icon's Add work at one press."""

    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync", "desktop", "version")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync", "desktop": "Desktop icon", "version": "Version"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
        self.desktop = accounts.desktop_icon_on(app.tools)
        frame = app.body
        heading(frame, "Accounts", 0)
        self.open_button = ttk.Button(frame, text="Open School-Life-Assistant",
                                      command=lambda: app.tools.open_site(state.server_url))
        self.open_button.grid(row=0, column=2, sticky="ne")
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
        if row == "version":
            return __version__ + (f", updated by itself on {local_time(state.updated_at)}" if state.updated_at else "")
        if row == "desktop":
            return "on" if self.desktop else "off"
        return SYNC_STATES[self.sync_state]

    def button_label(self, row):
        if row == "version":
            return None
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
        if row == "desktop":
            return None if self.desktop else "Add"
        if (row == "blackboard" and not self.state.blackboard_username) or (
                row == "outlook" and not self.state.outlook_account):
            return "Set up"
        return "Change"

    def open(self, row):
        if row in ("sync", "desktop"):  # Repair and Add: one press, no fields
            action = accounts.turn_on_sync if row == "sync" else accounts.add_desktop_icon
            self.buttons[row].state(["disabled"])
            self.answers[row].set(CHECKING)
            self.app.run(lambda: action(self.app.tools), lambda result: self.app.show(notice=mark(result)))
            return
        for button in self.buttons.values():
            button.state(["disabled"])
        self.editor = Editor(self, row)

    def closed(self):
        self.editor = None
        for button in self.buttons.values():
            button.state(["!disabled"])


class Editor:
    """One account's fields under its row, with Check and save and Cancel; Outlook's is the Outlook page."""

    FIELDS = {
        "site": (("Address", "address", False),),
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
        self.connecting = None  # Website: the connection of the newest press of Connect
        if row == "site":
            self.add_connect()
        if row == "outlook":
            self.values["outlook"] = tk.StringVar(self.frame, state.outlook_account or "")
            self.outlook = OutlookPage(screen.app, self.frame, self.values["outlook"], skippable=False)
            self.outlook.frame.grid(row=0, column=0, columnspan=3, sticky="ew")
        buttons = ttk.Frame(self.frame)
        buttons.grid(row=5, column=0, columnspan=3, sticky="e", pady=(4, 0))
        self.save_button = ttk.Button(buttons, text="Check and save", command=self.save)
        self.save_button.grid(row=0, column=0)
        self.cancel_button = ttk.Button(buttons, text="Cancel", command=self.cancel)
        self.cancel_button.grid(row=0, column=1, padx=(8, 0))

    def add_connect(self):
        """Website: Connect next to the address, and today's key box under Paste a key instead (spec
        2026-10-04-connect-button-design.md, 2)."""
        self.connect_button = ttk.Button(self.frame, text="Connect", command=self.connect)
        self.connect_button.grid(row=0, column=2, padx=(8, 0))
        self.values["key"] = tk.StringVar(self.frame)
        self.paste_link = ttk.Button(self.frame, text=PASTE_INSTEAD, style="Toolbutton", command=self.show_paste)
        self.paste_link.grid(row=1, column=0, columnspan=3, sticky="w")
        self.key_row = ttk.Frame(self.frame)
        self.key_row.grid(row=2, column=0, columnspan=3, sticky="ew")
        self.key_row.columnconfigure(1, weight=1)
        field(self.key_row, "Device key", self.values["key"], 0, secret=True)
        self.key_row.grid_remove()  # until Paste a key instead

    def show_paste(self):
        self.key_row.grid()

    def connect(self):
        app = self.screen.app
        address = self.values["address"].get().strip()
        try:
            connection = app.listen()
        except OSError as error:
            log.warning("Connect: can't listen on 127.0.0.1 (%s)", error)
            self.screen.answers["site"].set(mark(accounts.Result(False, accounts.CONNECT_UNAVAILABLE)))
            return
        self.connecting = connection
        self.screen.answers["site"].set(WAITING)
        tools, state = app.tools, load_state()
        app.run(lambda: accounts.connect_site(state, address, connection, tools),
                lambda result: self.connected(connection, result))

    def connected(self, connection, result):
        if self.connecting is not connection or not self.frame.winfo_exists():  # pressed again, or closed
            return
        if not result.message:  # closed meanwhile: nothing to say
            return
        if result.ok:
            self.screen.app.bring_forward()
        self.saved(result)

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
        self.screen.app.stop_listening()
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
