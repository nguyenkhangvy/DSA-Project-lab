"""The setup pages (spec 2026-10-02-easy-install-design.md, 3): a laptop that isn't set up gets one page per step,
each with its own guide, instead of one long form. Step 1 connects to the website with a device key and saves
nothing; step 2 checks EduSoft, then saves both and turns on automatic sync; the last page asks about the Desktop
icon and opens School-Life-Assistant.

The pages only collect what the student types and show the answers; accounts.py checks and saves, each check on a
worker thread (app.run), as Accounts does."""

import tkinter as tk
import webbrowser
from tkinter import ttk

from sla_agent import accounts, launcher
from sla_agent.accounts import Result
from sla_agent.log import protect
from sla_agent.state import load_state
from sla_agent.window_parts import CHECKING, answer_line, field, heading, mark, text_line

LOCAL_ADDRESS = "http://localhost:5000"  # a developer's own site, running from source
ONLINE_ADDRESS = "https://school-life-assistant.onrender.com"  # the website students use
SITE_STEPS = (
    "1. Press Open the website.",
    "2. Log in, or create an account.",
    "3. Go to School → Devices.",
    "4. Type a name for this laptop (for example \"My laptop\") and press Add device.",
    "5. Press Copy next to the new key.",
    "6. Come back to this window: the key is filled in by itself (or press Paste).",
)
NOT_A_KEY = "This isn't a device key. Press Copy on the Devices page, then Paste."
EDUSOFT_HINT = "The student ID and password you use on edusoftweb.hcmiu.edu.vn."
ALL_SET = "✓ All set! This laptop checks in every minute, and everything syncs every 30 minutes."
SYNC_REPAIR = "Automatic sync isn't on yet: press Repair next to it in Accounts."
SKIPPED = "skipped: set it up later in Accounts"
DESKTOP_QUESTION = "Put a School-Life-Assistant icon on your Desktop, to open it quickly?"


def default_address():
    """The address step 1 starts with: the website online for the built app, a developer's own site from source."""
    return ONLINE_ADDRESS if launcher.frozen() else LOCAL_ADDRESS


def read_clipboard(root):
    """The clipboard's text, or "" when it holds none. Tests replace it: they never read the real clipboard."""
    try:
        return root.clipboard_get()
    except tk.TclError:
        return ""


class SetupSteps:
    """The pages, one at a time (`page`). What the student typed is kept across them (`values`), with the address and
    key the website accepted on step 1 (`accepted`) and what each step saved (`results`)."""

    def __init__(self, app, state):
        self.app = app
        names = ("address", "key", "student_id", "password", "bb_user", "bb_password", "outlook")
        self.values = {name: tk.StringVar(app.body) for name in names}
        self.values["address"].set(state.server_url or default_address())
        self.values["student_id"].set(state.student_id or "")
        self.accepted = None  # (address, key)
        self.checking = None  # the (address, key) whose check counts: a newer change drops older answers
        self.address_open = False  # Change was pressed: the address box shows
        self.results = {}  # step -> Result
        self.page = None
        self.values["key"].trace_add("write", lambda *args: self.key_changed())
        app.root.bind("<FocusIn>", lambda event: self.came_forward() if event.widget is app.root else None,
                      add="+")
        self.show(SitePage)

    def show(self, page_class):
        if self.page is not None:
            self.page.frame.destroy()
        page_class(self)  # it makes itself self.page before it builds anything

    def after(self, page_class):
        """The page after `page_class`: the next step, or the last page."""
        index = STEPS.index(page_class) + 1
        return STEPS[index] if index < len(STEPS) else DonePage

    def key_changed(self):
        if isinstance(self.page, SitePage):
            self.page.key_changed()

    def came_forward(self):
        """The window came back to the front, as after copying the key in the browser."""
        if self.app.screen is self and isinstance(self.page, SitePage):
            self.page.fill_from_clipboard()


class Page:
    """One page: its heading, then what each page adds, row by row. `answer` is the line its checks answer on."""

    title = ""

    def __init__(self, steps):
        steps.page = self
        self.steps, self.app = steps, steps.app
        self.frame = ttk.Frame(self.app.body)
        self.frame.grid(row=0, column=0, columnspan=3, sticky="nsew")
        self.frame.columnconfigure(1, weight=1)
        self.answer = tk.StringVar(self.frame)
        self.row = 0
        heading(self.frame, self.header(), self.next_row())

    def header(self):
        return f"Step {STEPS.index(type(self)) + 1} of {len(STEPS)} · {self.title}"

    def next_row(self):
        self.row += 1
        return self.row - 1

    def add_line(self, text):
        text_line(self.frame, text, self.next_row())

    def add_field(self, label, name, secret=False):
        return field(self.frame, label, self.steps.values[name], self.next_row(), secret=secret)

    def add_answer(self):
        answer_line(self.frame, self.answer, self.next_row())

    def add_bar(self, *buttons):
        """The page's buttons, bottom right, from (text, command) pairs; returns them in that order."""
        bar = ttk.Frame(self.frame)
        bar.grid(row=self.next_row(), column=0, columnspan=3, sticky="e", pady=(16, 0))
        made = []
        for column, (text, command) in enumerate(buttons):
            made.append(ttk.Button(bar, text=text, command=command))
            made[-1].grid(row=0, column=column, padx=(8, 0))
        return made

    def busy(self, *buttons):
        self.answer.set(CHECKING)
        for button in buttons:
            button.state(["disabled"])

    def free(self, *buttons):
        for button in buttons:
            button.state(["!disabled"])

    def current(self):
        """Whether this page is still the one shown: a check's late answer for a page left meanwhile is dropped."""
        return self.steps.page is self


class SitePage(Page):
    """Step 1: the device key, with its guide. Nothing is saved yet: a key the website accepts turns Next on."""

    title = "Connect to the website"

    def __init__(self, steps):
        super().__init__(steps)
        for text in SITE_STEPS:
            self.add_line(text)
        self.open_button = ttk.Button(self.frame, text="Open the website", command=self.open_site)
        self.open_button.grid(row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(4, 8))
        self.paste_button = ttk.Button(self.frame, text="Paste", command=self.paste)
        self.paste_button.grid(row=self.row, column=2, padx=(8, 0))
        self.add_field("Device key", "key", secret=True)
        self.add_answer()
        self.address_row = self.next_row()
        self.address_box = self.change_button = None
        self.show_address()
        [self.next_button] = self.add_bar(("Next →", lambda: steps.show(EdusoftPage)))
        self.key_changed()  # coming Back from step 2, the accepted key is still there

    def show_address(self):
        """"Website: <address>" with Change, or, once Change was pressed, the address box. Enter, or leaving the
        box, checks the key with the new address (each keystroke would ask a half-typed one)."""
        if self.address_box is not None:
            self.address_box.destroy()
        self.address_box = ttk.Frame(self.frame)
        self.address_box.grid(row=self.address_row, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        self.address_box.columnconfigure(1, weight=1)
        if self.steps.address_open:
            self.change_button = None
            box = field(self.address_box, "Website", self.steps.values["address"], 0)
            for event in ("<Return>", "<FocusOut>"):
                box.bind(event, lambda event: self.key_changed())
            return
        ttk.Label(self.address_box, text="Website:", foreground="gray").grid(row=0, column=0, sticky="w")
        ttk.Label(self.address_box, textvariable=self.steps.values["address"], foreground="gray").grid(
            row=0, column=1, sticky="w", padx=(4, 0))
        self.change_button = ttk.Button(self.address_box, text="Change", command=self.change)
        self.change_button.grid(row=0, column=2, padx=(8, 0))

    def change(self):
        self.steps.address_open = True
        self.show_address()

    def open_site(self):
        webbrowser.open(self.steps.values["address"].get().strip().rstrip("/") + "/school/devices")

    def paste(self):
        text = read_clipboard(self.app.root).strip()
        if not accounts.is_device_key(text):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
            return
        self.steps.values["key"].set(text)  # checked by key_changed

    def fill_from_clipboard(self):
        """A device key copied meanwhile fills the empty box; anything else on the clipboard is left alone."""
        text = read_clipboard(self.app.root).strip()
        if not self.steps.values["key"].get().strip() and accounts.is_device_key(text):
            self.steps.values["key"].set(text)

    def key_changed(self):
        """Check a key-shaped key with the website at once; say so for any other text. Next only once accepted."""
        address = self.steps.values["address"].get().strip()
        key = self.steps.values["key"].get().strip()
        self.next_button.state(["disabled"])
        self.steps.checking = (address, key)
        if not key:
            self.answer.set("")
        elif not accounts.is_device_key(key):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
        elif self.steps.accepted == (address, key):
            self.checked(address, key, Result(True, accounts.SITE_ACCEPTED))
        else:
            protect(key)
            self.answer.set(CHECKING)
            self.app.run(lambda: accounts.check_site(address, key, self.app.tools),
                         lambda result: self.checked(address, key, result))

    def checked(self, address, key, result):
        if not self.current() or self.steps.checking != (address, key):  # left, or changed again meanwhile
            return
        if result.ok:
            self.steps.accepted = (address, key)
            self.free(self.next_button)
        self.answer.set(mark(result))


class EdusoftPage(Page):
    """Step 2: one EduSoft login attempt. When it passes, the key and EduSoft are saved and automatic sync is turned
    on (accounts.save_and_turn_on_sync): from then on this laptop is set up, with no way back to step 1."""

    title = "EduSoft"

    def __init__(self, steps):
        super().__init__(steps)
        self.add_line(EDUSOFT_HINT)
        self.add_field("Student ID", "student_id")
        self.add_field("Password", "password", secret=True)
        self.add_answer()
        self.back_button, self.next_button = self.add_bar(("← Back", lambda: steps.show(SitePage)),
                                                          ("Next →", self.save))

    def save(self):
        address, key = self.steps.accepted
        student_id = self.steps.values["student_id"].get().strip()
        password = self.steps.values["password"].get()
        protect(password)
        self.busy(self.back_button, self.next_button)
        self.app.run(lambda: accounts.save_and_turn_on_sync(load_state(), address, key, student_id, password,
                                                             self.app.tools), self.saved)

    def saved(self, results):
        if not self.current():
            return
        if isinstance(results, Result):  # something unexpected went wrong
            results = {"edusoft": results}
        if not results["edusoft"].ok:
            self.answer.set(mark(results["edusoft"]))
            self.free(self.back_button, self.next_button)
            return
        self.steps.accepted = None
        for secret in ("key", "password"):
            self.steps.values[secret].set("")
        self.steps.results.update(results)
        self.steps.show(self.steps.after(EdusoftPage))


class DonePage(Page):
    """The last page: what is on, then the Desktop icon question. Either answer shows Accounts and opens
    School-Life-Assistant (the website as an app); closing the window instead adds no icon."""

    title = "All set"

    def __init__(self, steps):
        super().__init__(steps)
        self.lines = self.summary()
        for text in self.lines:
            self.add_line(text)
        ttk.Label(self.frame, text=DESKTOP_QUESTION, font=("Segoe UI", 10, "bold")).grid(
            row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(12, 0))
        self.add_answer()
        self.no_button, self.yes_button = self.add_bar(("No, thanks", self.no), ("Yes, add the icon", self.yes))

    def header(self):
        return self.title

    def summary(self):
        """All set (or why automatic sync isn't on), a line per account, then any note a step left."""
        results, state = self.steps.results, load_state()
        sync = results["sync"]
        lines = [ALL_SET] if sync.ok else [mark(sync), SYNC_REPAIR]
        lines.append(f"✓ EduSoft: {state.student_id}")
        lines.append(f"✓ Blackboard: {state.blackboard_username}" if state.blackboard_username
                     else f"– Blackboard: {SKIPPED}")
        lines.append(f"✓ Outlook: {state.outlook_account}" if state.outlook_account else f"– Outlook: {SKIPPED}")
        return lines + [note for result in results.values() if result.ok for note in result.notes]

    def yes(self):
        self.busy(self.no_button, self.yes_button)
        self.app.run(lambda: accounts.add_desktop_icon(self.app.tools), lambda result: self.finish(mark(result)))

    def no(self):
        self.finish("")

    def finish(self, notice):
        self.app.show(notice=notice)
        self.app.tools.open_site(load_state().server_url)


STEPS = (SitePage, EdusoftPage)
