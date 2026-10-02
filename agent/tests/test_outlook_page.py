"""The Outlook page (outlook_page.py), built for real but hidden, with fakes behind it (accounts_fakes.Fakes): what it
shows for each answer the registry gives, and that only an Outlook someone signed in to is asked for its accounts."""

import tkinter as tk

import pytest

from agent.tests.accounts_fakes import ME, Fakes
from sla_agent import outlook_page, window_parts
from sla_agent.errors import OutlookBlocked
from sla_agent.outlook_page import OutlookPage, first_choice
from sla_agent.outlook_reader import MISSING, NOT_SIGNED_IN, SIGNED_IN

GMAIL = "me@gmail.com"


class Host:
    """What the page needs from the window: the tools, and how to run a check (at once, unless a test holds it)."""

    def __init__(self, fakes, run=window_parts.run_at_once):
        self.tools, self.run = fakes.tools(), run


@pytest.fixture
def fakes():
    return Fakes()


def show(root, fakes, skippable=True, current="", run=window_parts.run_at_once):
    """The page in `root`, its account variable, and the account it held each time it said it changed."""
    changes = []
    variable = tk.StringVar(root, current)
    page = OutlookPage(Host(fakes, run), root, variable, skippable, changed=lambda: changes.append(variable.get()))
    return page, variable, changes


def test_without_classic_outlook_it_shows_how_to_install_it_and_never_asks_outlook(root, fakes):
    fakes.outlook = MISSING

    page, variable, _ = show(root, fakes)

    assert page.state == MISSING
    assert page.lines == [outlook_page.MISSING_INTRO, *outlook_page.MISSING_STEPS, outlook_page.LATER]
    assert list(page.buttons) == ["Install Office", "Other way", "Check again"]
    assert not page.ready and variable.get() == ""
    assert fakes.looks == []


def test_accounts_has_no_skip_so_its_install_guide_doesnt_offer_one(root, fakes):
    fakes.outlook = MISSING

    page, _, _ = show(root, fakes, skippable=False)

    assert outlook_page.LATER not in page.lines


def test_install_office_and_other_way_open_microsofts_pages(root, fakes, monkeypatch):
    opened = []
    monkeypatch.setattr(outlook_page.webbrowser, "open", opened.append)
    fakes.outlook = MISSING
    page, _, _ = show(root, fakes)

    page.buttons["Install Office"].invoke()
    page.buttons["Other way"].invoke()

    assert opened == [outlook_page.INSTALL_OFFICE, outlook_page.OTHER_WAY]


def test_installed_but_nobody_signed_in_shows_how_to_sign_in_and_open_outlook_starts_it(root, fakes):
    fakes.outlook = NOT_SIGNED_IN
    page, _, _ = show(root, fakes)

    assert page.lines == list(outlook_page.SIGN_IN_STEPS)
    assert list(page.buttons) == ["Open Outlook", "Check again"]
    assert fakes.looks == []

    page.buttons["Open Outlook"].invoke()

    assert "start outlook" in fakes.done


def test_open_outlook_that_fails_says_where_to_find_it(root, fakes):
    fakes.outlook = NOT_SIGNED_IN
    fakes.fail = {"start outlook": FileNotFoundError("outlook.exe")}
    page, _, _ = show(root, fakes)

    page.buttons["Open Outlook"].invoke()

    assert page.note.get() == "Couldn't open Outlook (classic) (outlook.exe). Open it from the Start menu."


def test_signed_in_it_lists_the_accounts_with_the_iu_one_chosen(root, fakes):
    fakes.found = [GMAIL, ME]

    page, variable, _ = show(root, fakes)

    assert page.state == SIGNED_IN
    assert page.lines == [outlook_page.CHOOSE]
    assert tuple(page.box.cget("values")) == (GMAIL, ME)
    assert variable.get() == ME and page.ready
    assert fakes.looks == ["start"]


def test_the_account_chosen_before_stays_chosen(root, fakes):
    fakes.found = [GMAIL, ME]

    _, variable, _ = show(root, fakes, current=GMAIL)

    assert variable.get() == GMAIL


def test_without_an_iu_account_the_first_is_chosen():
    assert first_choice([GMAIL, "me@outlook.com"]) == GMAIL
    assert first_choice([GMAIL, ME.upper()]) == ME.upper()


@pytest.mark.parametrize("found, reason", [
    ([], "Classic Outlook has no account yet."),
    (OutlookBlocked("Outlook didn't answer within 3 minutes (a security prompt may be waiting)."),
     "Outlook didn't answer within 3 minutes (a security prompt may be waiting)."),
], ids=["no-account", "no-answer"])
def test_signed_in_without_an_account_it_shows_the_sign_in_guide_with_the_reason(root, fakes, found, reason):
    fakes.found = found

    page, variable, _ = show(root, fakes)

    assert page.state == NOT_SIGNED_IN
    assert page.lines == [reason, *outlook_page.SIGN_IN_STEPS]
    assert variable.get() == "" and not page.ready


def test_check_again_follows_the_student_from_install_to_sign_in_to_choosing(root, fakes):
    fakes.outlook = MISSING
    page, variable, changes = show(root, fakes)

    fakes.outlook = NOT_SIGNED_IN
    page.buttons["Check again"].invoke()
    assert page.state == NOT_SIGNED_IN

    fakes.outlook = SIGNED_IN
    page.buttons["Check again"].invoke()
    assert page.state == SIGNED_IN and variable.get() == ME
    assert changes[-1] == ME


def test_while_looking_it_isnt_ready_and_an_answer_for_a_closed_page_is_dropped(root, fakes):
    held = []
    page, variable, _ = show(root, fakes, run=lambda work, done: held.append((work, done)))

    assert page.lines == [outlook_page.LOOKING] and not page.ready
    page.frame.destroy()  # the student left the page
    work, done = held.pop()
    done(window_parts.attempt(work))

    assert variable.get() == ""


def test_something_unexpected_while_looking_shows_the_sign_in_guide_with_what_happened(root, fakes):
    fakes.outlook = RuntimeError("a bug")

    page, _, _ = show(root, fakes)

    assert page.state == NOT_SIGNED_IN
    assert page.lines[0] == "Something went wrong (RuntimeError). Nothing was saved."
