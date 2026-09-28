"""A stand-in for classic Outlook's COM objects, with only what the agent uses. Collections are plain lists;
properties that fail raise ComError with Outlook's error code, as pywin32's com_error does."""

from datetime import datetime, timedelta, timezone

from sla_agent.outlook_reader import PR_DELIVERY_TIME, PR_MESSAGE_ID, PR_SENDER_SMTP

NOT_FOUND = -2147221233  # MAPI_E_NOT_FOUND: the property isn't there
E_ABORT = -2147467260  # what Outlook answers when its security prompt is refused


class ComError(Exception):
    def __init__(self, hresult):
        super().__init__(f"COM error {hresult}")
        self.hresult = hresult


class FakeProperties:
    def __init__(self, values):
        self.values = values

    def GetProperty(self, tag):
        value = self.values.get(tag)
        if isinstance(value, Exception):
            raise value
        if value is None:
            raise ComError(NOT_FOUND)
        return value


class FakeMail:
    """One email. `received` is aware UTC; Outlook's ReceivedTime is the same moment as Vietnam wall time,
    wrongly labelled UTC, as pywin32 gives it."""

    Class = 43

    def __init__(self, subject, received, body="", address="someone@example.com", name="Someone",
                 message_id="<m@example.com>", entry_id="00AB", thread="T1", exchange_address=None, broken=None):
        self.Subject, self.SenderName, self.EntryID, self.ConversationID = subject, name, entry_id, thread
        self.SenderEmailType = "EX" if exchange_address else "SMTP"
        self.SenderEmailAddress = "/O=EXCHANGELABS/OU=EXCHANGE/CN=RECIPIENTS/CN=X" if exchange_address else address
        self.ReceivedTime = (received + timedelta(hours=7)).replace(tzinfo=timezone.utc)
        self.PropertyAccessor = FakeProperties({PR_DELIVERY_TIME: received, PR_MESSAGE_ID: message_id,
                                                PR_SENDER_SMTP: exchange_address})
        self._body, self.broken, self.displayed = body, broken, 0

    @property
    def Body(self):
        if self.broken:
            raise self.broken
        return self._body

    def Display(self):
        self.displayed += 1


class FakeMeeting(FakeMail):
    Class = 53  # olMeetingRequest


class FakeItems:
    def __init__(self, items):
        self.items, self.position = list(items), 0

    def Sort(self, field, descending):
        assert (field, descending) == ("[ReceivedTime]", True)
        self.items.sort(key=lambda item: item.ReceivedTime, reverse=True)

    def GetFirst(self):
        self.position = 0
        return self.GetNext()

    def GetNext(self):
        if self.position >= len(self.items):
            return None
        self.position += 1
        return self.items[self.position - 1]


class FakeFolder:
    def __init__(self, items):
        self.Items = FakeItems(items)


class FakeStore:
    def __init__(self, inbox):
        self.inbox = inbox

    def GetDefaultFolder(self, number):
        assert number == 6  # olFolderInbox
        return self.inbox


class FakeAccount:
    def __init__(self, address, mails=()):
        self.SmtpAddress = address
        self.DeliveryStore = FakeStore(FakeFolder(mails))


class FakeNamespace:
    def __init__(self, accounts, modes=(700,)):
        self.Accounts = list(accounts)
        self.modes = list(modes)

    @property
    def ExchangeConnectionMode(self):
        return self.modes.pop(0) if len(self.modes) > 1 else self.modes[0]

    def GetItemFromID(self, entry_id):
        for account in self.Accounts:
            for item in account.DeliveryStore.inbox.Items.items:
                if item.EntryID == entry_id:
                    return item
        raise ComError(NOT_FOUND)


class FakeOutlook:
    def __init__(self, *accounts, modes=(700,)):
        self.namespace = FakeNamespace(accounts, modes)

    def GetNamespace(self, name):
        assert name == "MAPI"
        return self.namespace


def at(day, hour=1, minute=0):
    """An aware UTC time on a day of September 2026 (hour 1 UTC is 08:00 in Vietnam)."""
    return datetime(2026, 9, day, hour, minute, tzinfo=timezone.utc)
