"""Reading the Inbox through classic Outlook on this laptop (Windows COM), and opening one email.

Read only: this module reads item properties and, to show an email to the student, calls Display().
It never sends, deletes, moves, copies or saves anything, and never marks mail as read; a test
checks its source for those names. It also never answers Outlook's security prompts: if Outlook
blocks the read, or doesn't answer within TIME_LIMIT, the Outlook part fails with a message.

Outlook closes by itself when the agent lets go of it and no Outlook window is open (checked
2026-09-28), so the agent never closes it and can't close a window the student opened.

pywin32 is imported only when Outlook is really used, so the tests run anywhere with fakes."""

import hashlib
import logging
import re
import threading
import time as clock
from datetime import date, datetime, time, timedelta, timezone

from sla_contract.schema import Outlook

from sla_agent import mail_rules
from sla_agent.errors import EmailNotFound, OutlookBlocked, OutlookNotSetUp
from sla_agent.mail_rules import Email

log = logging.getLogger(__name__)

OL_FOLDER_INBOX = 6
OL_MAIL = 43  # olMail: emails only, not meeting requests or delivery reports
CONNECTED_MODES = (500, 600, 700, 800)  # olCachedConnectedHeaders/Drizzle/Full, olOnline
PR_DELIVERY_TIME = "http://schemas.microsoft.com/mapi/proptag/0x0E060040"  # UTC; ReceivedTime is local
PR_SENDER_SMTP = "http://schemas.microsoft.com/mapi/proptag/0x5D01001F"
PR_MESSAGE_ID = "http://schemas.microsoft.com/mapi/proptag/0x1035001F"
REFUSED = (-2147467260, -2147024891, -2147418111)  # E_ABORT, E_ACCESSDENIED, RPC_E_CALL_REJECTED
TIME_LIMIT = timedelta(minutes=3)
QUICK_LIMIT = timedelta(seconds=5)  # the minute's mail check gives up after this
CONNECT_WAIT = timedelta(seconds=30)  # a hidden Outlook needs ~5 s to connect after it starts
MAX_EMAILS = 2000
VIETNAM_OFFSET = timedelta(hours=7)
LINK = re.compile(r"sla-mail:([0-9A-F]{2,512})/?")

NOT_SET_UP = "Classic Outlook isn't set up on this laptop."
BLOCKED = "Outlook didn't let the agent read your mail."
TOO_SLOW = "Outlook didn't answer within 3 minutes (a security prompt may be waiting)."


def semester_start(term_code, today):
    """The first day to read mail from (spec 4.2): EduSoft's term code YYYYS gives 1 August of YYYY for
    semester 1, 1 January of YYYY+1 for semester 2 and 1 June of YYYY+1 for semester 3; otherwise 90 days ago."""
    if term_code and re.fullmatch(r"\d{4}[123]", term_code):
        year, part = int(term_code[:4]), term_code[4]
        return {"1": date(year, 8, 1), "2": date(year + 1, 1, 1), "3": date(year + 1, 6, 1)}[part]
    return today - timedelta(days=90)


def open_outlook():
    """The running Outlook, or a new hidden one. Windows only."""
    try:
        import pythoncom
        import pywintypes
        import win32com.client
    except ImportError as error:
        raise OutlookNotSetUp(NOT_SET_UP + " (It works only on Windows with classic Outlook.)") from error
    pythoncom.CoInitialize()
    try:
        return win32com.client.GetActiveObject("Outlook.Application")
    except pywintypes.com_error:
        pass
    try:
        return win32com.client.Dispatch("Outlook.Application")
    except pywintypes.com_error as error:
        raise OutlookNotSetUp(NOT_SET_UP) from error


def running_outlook():
    """The Outlook that is already open, or None: the minute's mail check never starts one. Windows only."""
    try:
        import pythoncom
        import pywintypes
        import win32com.client
    except ImportError:
        return None
    pythoncom.CoInitialize()
    try:
        return win32com.client.GetActiveObject("Outlook.Application")
    except pywintypes.com_error:
        return None


def _refused(error):
    return getattr(error, "hresult", None) in REFUSED


def accounts(app):
    """The email addresses of Outlook's accounts."""
    return [account.SmtpAddress for account in app.GetNamespace("MAPI").Accounts if account.SmtpAddress]


def inbox_of(app, address):
    """(namespace, Inbox folder) of the account with this address."""
    namespace = app.GetNamespace("MAPI")
    for account in namespace.Accounts:
        if (account.SmtpAddress or "").lower() == address.lower():
            return namespace, account.DeliveryStore.GetDefaultFolder(OL_FOLDER_INBOX)
    raise OutlookNotSetUp(f"{NOT_SET_UP} Its account {address} is no longer in Outlook.")


def wait_until_connected(namespace, sleep=clock.sleep, wait=CONNECT_WAIT):
    """True once Outlook is connected to the mail server; False if it stays offline for `wait`."""
    waited = 0.0
    while namespace.ExchangeConnectionMode not in CONNECTED_MODES:
        if waited >= wait.total_seconds():
            return False
        sleep(2)
        waited += 2
    return True


def _plain(moment):
    return datetime(*moment.timetuple()[:6], moment.microsecond)


def _received(item):
    """When the email arrived, as aware UTC. PR_MESSAGE_DELIVERY_TIME is UTC; pywin32 labels Outlook's
    ReceivedTime as UTC although it is local time, so that is only the fallback."""
    try:
        return _plain(item.PropertyAccessor.GetProperty(PR_DELIVERY_TIME)).replace(tzinfo=timezone.utc)
    except Exception as error:
        if _refused(error):
            raise
        return _plain(item.ReceivedTime).astimezone(timezone.utc)


def _sender_address(item):
    if item.SenderEmailType == "EX":  # an IU colleague stored in Exchange's own format
        try:
            return item.PropertyAccessor.GetProperty(PR_SENDER_SMTP)
        except Exception as error:
            if _refused(error):
                raise
            try:
                return item.Sender.GetExchangeUser().PrimarySmtpAddress
            except Exception:
                return ""
    return item.SenderEmailAddress or ""


def _key(item):
    """SHA-256 of the internet message ID (the same on every device), else of Outlook's ID."""
    try:
        message_id = item.PropertyAccessor.GetProperty(PR_MESSAGE_ID)
    except Exception as error:
        if _refused(error):
            raise
        message_id = ""
    return hashlib.sha256((message_id or item.EntryID).encode("utf-8")).hexdigest()


def _email(item):
    return Email(key=_key(item), entry_id=item.EntryID.upper(), thread_id=item.ConversationID or None,
                 received_at=_received(item), sender_name=item.SenderName or "",
                 sender_address=_sender_address(item), subject=item.Subject or "", text=item.Body or "")


def read_emails(inbox, since):
    """(emails, skipped): the Inbox's emails received from `since` (a Vietnam date) on, newest first.
    An email that can't be read is skipped; Outlook refusing access raises OutlookBlocked."""
    start = datetime.combine(since, time(), tzinfo=timezone.utc) - VIETNAM_OFFSET
    items = inbox.Items
    items.Sort("[ReceivedTime]", True)
    emails, skipped = [], 0
    item = items.GetFirst()
    while item is not None:
        try:
            if item.Class == OL_MAIL:
                email = _email(item)
                if email.received_at < start:
                    break
                emails.append(email)
        except Exception as error:
            if _refused(error):
                raise OutlookBlocked(BLOCKED) from error
            skipped += 1
        item = items.GetNext()
    return emails, skipped


def with_time_limit(work, limit=TIME_LIMIT):
    """work()'s result, or OutlookBlocked when it takes longer than `limit`. A stuck Outlook call can't be
    stopped, so it is left in a background thread that ends with the program."""
    result = {}

    def run():
        try:
            result["value"] = work()
        except BaseException as error:  # handed to the caller below
            result["error"] = error

    worker = threading.Thread(target=run, name="outlook-read", daemon=True)
    worker.start()
    worker.join(limit.total_seconds())
    if worker.is_alive():
        raise OutlookBlocked(TOO_SLOW)
    if "error" in result:
        raise result["error"]
    return result["value"]


def read_outlook(address, since, context, *, open_outlook=open_outlook, sleep=clock.sleep, limit=TIME_LIMIT):
    """The Outlook part of a sync: every Inbox email from `since` on, sorted on this laptop. No text leaves."""

    def work():
        try:
            namespace, inbox = inbox_of(open_outlook(), address)
            connected = wait_until_connected(namespace, sleep)
            emails, skipped = read_emails(inbox, since)
        except (OutlookNotSetUp, OutlookBlocked):
            raise
        except Exception as error:
            if hasattr(error, "hresult"):  # any other COM error: Outlook refused
                raise OutlookBlocked(BLOCKED) from error
            raise
        return connected, emails, skipped

    connected, emails, skipped = with_time_limit(work, limit)
    if skipped:
        log.warning("Skipped %d emails Outlook couldn't read", skipped)
    items = [mail_rules.sort_email(email, context) for email in emails[:MAX_EMAILS]]
    return Outlook(since=since, connected=connected, emails=items)


def newest_received(address, *, running=running_outlook, limit=QUICK_LIMIT):
    """When the newest email in this account's Inbox arrived (aware UTC), from an Outlook that is already open;
    None when Outlook isn't open, the Inbox has no email, or Outlook doesn't answer within `limit`. Reads no text."""

    def work():
        app = running()
        if app is None:
            return None
        _, inbox = inbox_of(app, address)
        items = inbox.Items
        items.Sort("[ReceivedTime]", True)
        item = items.GetFirst()
        while item is not None:
            if item.Class == OL_MAIL:
                return _received(item)
            item = items.GetNext()
        return None

    try:
        return with_time_limit(work, limit)
    except Exception as error:  # closed, busy, blocked or no longer set up: no check this minute
        log.debug("No mail check this minute (%s)", error.__class__.__name__)
        return None


def entry_id_from_link(link):
    """The Outlook ID in an "sla-mail:<hex>" link (one trailing / allowed), else None."""
    match = LINK.fullmatch((link or "").strip())
    return match.group(1) if match else None


def open_email(entry_id, open_outlook=open_outlook):
    """Shows one email in classic Outlook, for the student to read."""
    namespace = open_outlook().GetNamespace("MAPI")
    try:
        item = namespace.GetItemFromID(entry_id)
    except Exception as error:
        raise EmailNotFound("This email is no longer in your Outlook Inbox.") from error
    item.Display()
