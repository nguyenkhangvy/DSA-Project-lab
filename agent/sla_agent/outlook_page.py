"""The Outlook page (spec 2026-10-02-easy-install-design.md, 4), shown by the setup's step 4 and by Accounts →
Outlook. It reads the registry first, which never starts Outlook: no Outlook (classic) gets the install guide (A);
Outlook nobody signed in to gets the sign-in guide (B); only then (C) is Outlook asked for its accounts, which may
start a hidden Outlook for a moment."""

import tkinter as tk
import webbrowser
from tkinter import ttk

from sla_agent import accounts
from sla_agent.accounts import Result
from sla_agent.outlook_reader import MISSING, NOT_SIGNED_IN, SIGNED_IN
from sla_agent.window_parts import answer_line, text_line

STUDENT_DOMAIN = "@student.hcmiu.edu.vn"
INSTALL_OFFICE = "https://www.microsoft365.com/"
OTHER_WAY = ("https://support.microsoft.com/en-us/office/"
             "install-or-reinstall-classic-outlook-on-a-windows-pc-5c94902b-31a5-4274-abb0-b07f4661edf5")
LOOKING = "Looking for Outlook (classic)…"
MISSING_INTRO = ("School-Life-Assistant reads your IU mail through Outlook (classic). The new Outlook app can't be "
                 "used. This laptop doesn't have Outlook (classic) yet:")
MISSING_STEPS = (
    "1. Press Install Office: microsoft365.com opens. Sign in with your IU email (it ends with "
    "@student.hcmiu.edu.vn).",
    "2. Press Install apps, then Microsoft 365 apps. Open the downloaded file and wait until it finishes "
    "(10–20 minutes). No Install apps button? Your IU account has only the web version of Office: press Other way "
    "for Microsoft's own Outlook (classic) download (it may say it isn't licensed), or skip Outlook.",
    "3. Open Outlook (classic) from the Start menu and sign in with your IU email.",
    "4. Come back and press Check again.",
)
LATER = ("This takes a while: you can press Skip now and set up Outlook later in School-Life-Assistant Accounts "
         "(Start menu).")
SIGN_IN_STEPS = (
    "1. Press Open Outlook and sign in with your IU email (it ends with @student.hcmiu.edu.vn). If the new Outlook "
    "opens instead, turn off its New Outlook switch (top right) to get Outlook (classic).",
    "2. Wait until it says \"All folders are up to date\", then press Check again.",
)
CHOOSE = "Read the Inbox of:"
NO_START = "Couldn't open Outlook (classic) ({error}). Open it from the Start menu."
BUTTONS = {MISSING: ("Install Office", "Other way", "Check again"), NOT_SIGNED_IN: ("Open Outlook", "Check again"),
           SIGNED_IN: ("Check again",)}


def first_choice(found, current=""):
    """The account chosen first: the one chosen before, else the first IU student account, else the first."""
    if current in found:
        return current
    return next((address for address in found if address.lower().endswith(STUDENT_DOMAIN)), found[0])


class OutlookPage:
    """A frame (`frame`, placed by the caller) that looks when it is made and on Check again. `variable` holds the
    chosen account in C and is empty otherwise; `changed()` is called whenever that may have changed, so the caller
    can turn its Next or Check and save on and off (`ready`). `skippable`: the setup's step 4, which offers Skip."""

    def __init__(self, app, parent, variable, skippable, changed=lambda: None):
        self.app, self.variable, self.skippable, self.changed = app, variable, skippable, changed
        self.current = variable.get()  # the account chosen before (Accounts → Change)
        self.frame = ttk.Frame(parent)
        self.frame.columnconfigure(0, weight=1)
        self.note = tk.StringVar(self.frame)
        answer_line(self.frame, self.note, 1)
        self.state, self.lines, self.buttons, self.box, self.body = None, [], {}, None, None
        self.look()

    @property
    def ready(self):
        return self.state == SIGNED_IN and bool(self.variable.get())

    def look(self):
        """The registry, then (signed in) Outlook's accounts, on a worker thread; Check again does it again."""
        self.state = None
        self.variable.set("")
        self.note.set("")
        self.show([LOOKING], ())
        self.changed()
        self.app.run(self.find, self.found)

    def find(self):
        state = self.app.tools.outlook_state()
        if state != SIGNED_IN:
            return state, [], None
        found, problem = accounts.outlook_accounts(self.app.tools)
        return state, found, problem

    def found(self, answer):
        if not self.frame.winfo_exists():  # the student left the page meanwhile
            return
        state, found, problem = (NOT_SIGNED_IN, [], answer.message) if isinstance(answer, Result) else answer
        if state == SIGNED_IN and not found:  # no account after all, or Outlook didn't answer
            state = NOT_SIGNED_IN
        self.state = state
        if state == MISSING:
            self.show([MISSING_INTRO, *MISSING_STEPS, *([LATER] if self.skippable else [])], BUTTONS[MISSING])
        elif state == NOT_SIGNED_IN:
            self.show([*([problem] if problem else []), *SIGN_IN_STEPS], BUTTONS[NOT_SIGNED_IN])
        else:
            self.show([CHOOSE], BUTTONS[SIGNED_IN], found)
        self.changed()

    def show(self, lines, buttons, found=()):
        if self.body is not None:
            self.body.destroy()
        self.body = ttk.Frame(self.frame)
        self.body.grid(row=0, column=0, sticky="ew")
        self.lines, self.box = list(lines), None
        for row, line in enumerate(lines):
            text_line(self.body, line, row)
        if found:
            self.box = ttk.Combobox(self.body, textvariable=self.variable, state="readonly", values=list(found),
                                    width=38)
            self.box.grid(row=len(lines), column=0, sticky="w")
            self.box.bind("<<ComboboxSelected>>", lambda event: self.changed())
            self.variable.set(first_choice(found, self.current))
        bar = ttk.Frame(self.body)
        bar.grid(row=len(lines) + 1, column=0, sticky="w", pady=(8, 0))
        commands = {"Install Office": lambda: webbrowser.open(INSTALL_OFFICE),
                    "Other way": lambda: webbrowser.open(OTHER_WAY),
                    "Open Outlook": self.start_outlook, "Check again": self.look}
        self.buttons = {}
        for column, name in enumerate(buttons):
            self.buttons[name] = ttk.Button(bar, text=name, command=commands[name])
            self.buttons[name].grid(row=0, column=column, padx=(0, 8))

    def start_outlook(self):
        try:
            self.app.tools.start_outlook()
        except OSError as error:
            self.note.set(NO_START.format(error=error))
