"""What the School-Life-Assistant window's screens share (window.py, setup_steps.py, outlook_page.py): running a
check off Tk's thread, and the lines and fields every screen is made of.

Each check runs on a worker thread so the window never freezes, and its answer comes back through Tk's event loop,
since Tk may only be used from its own thread (tests pass `run=run_at_once`)."""

import logging
import queue
import threading
from tkinter import ttk

from sla_agent.accounts import Result

log = logging.getLogger(__name__)

CHECKING = "Checking…"
WRAP = 500  # pixels: longer lines wrap


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


def mark(result):
    return ("✓ " if result.ok else "✗ ") + "\n".join((result.message, *result.notes))


def heading(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 14, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 8))


def field(parent, label, variable, row, secret=False):
    """A label and its box; returns the box."""
    ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 8), pady=2)
    box = ttk.Entry(parent, textvariable=variable, show="•" if secret else "", width=40)
    box.grid(row=row, column=1, sticky="ew", pady=2)
    return box


def answer_line(parent, variable, row):
    ttk.Label(parent, textvariable=variable, wraplength=WRAP, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w")


def text_line(parent, text, row):
    """One line of a guide."""
    ttk.Label(parent, text=text, wraplength=WRAP, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 4))
