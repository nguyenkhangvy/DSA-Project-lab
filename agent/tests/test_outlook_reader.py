"""Reading the Inbox through classic Outlook, with a fake Outlook (no Windows needed)."""

import hashlib
import logging
import re
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from agent.tests.outlook_fakes import (
    E_ABORT,
    ComError,
    FakeAccount,
    FakeMail,
    FakeMeeting,
    FakeOutlook,
    at,
)
from sla_agent import outlook_reader
from sla_agent.errors import EmailNotFound, OutlookBlocked, OutlookNotSetUp
from sla_agent.mail_rules import Context
from sla_agent.outlook_reader import (
    accounts,
    entry_id_from_link,
    open_email,
    read_outlook,
    semester_start,
)

ME = "ititiu99001@student.hcmiu.edu.vn"
SINCE = date(2026, 8, 1)
CONTEXT = Context(courses=(("IT093IU", "Web Application Development", "P.Q.Hùng"),))


def read(outlook, address=ME, since=SINCE, sleeps=None, **options):
    return read_outlook(address, since, CONTEXT, open_outlook=lambda: outlook,
                        sleep=(sleeps.append if sleeps is not None else lambda seconds: None), **options)


@pytest.mark.parametrize("term, expected", [
    ("20261", date(2026, 8, 1)),
    ("20262", date(2027, 1, 1)),
    ("20263", date(2027, 6, 1)),
    (None, date(2026, 6, 30)),
    ("2026", date(2026, 6, 30)),
])
def test_semester_start(term, expected):
    assert semester_start(term, date(2026, 9, 28)) == expected


def test_reads_the_chosen_accounts_inbox_emails_from_the_semester_start():
    mine = FakeAccount(ME, [
        FakeMail("Workshop ngày 29/09/2026", at(24), "Tích lũy điểm rèn luyện.", "oss@hcmiu.edu.vn", "P.CTSV [OSS]",
                 entry_id="00A1"),
        FakeMeeting("Meeting", at(23), entry_id="00A2"),
        FakeMail("First day in Vietnam", datetime(2026, 7, 31, 17, 30, tzinfo=timezone.utc), entry_id="00A3"),
        FakeMail("Last day of July in Vietnam", datetime(2026, 7, 31, 16, 59, tzinfo=timezone.utc), entry_id="00A4"),
    ])
    other = FakeAccount("me@gmail.com", [FakeMail("Private", at(25), entry_id="00B1")])

    result = read(FakeOutlook(other, mine))

    assert (result.since, result.connected) == (SINCE, True)
    assert [e.subject for e in result.emails] == ["Workshop ngày 29/09/2026", "First day in Vietnam"]
    workshop = result.emails[0]
    assert workshop.categories == ["event", "training_points"]
    assert workshop.dates == [date(2026, 9, 29)]
    assert workshop.received_at == at(24)
    assert workshop.key == hashlib.sha256(b"<m@example.com>").hexdigest()
    assert (workshop.entry_id, workshop.thread_id, workshop.sender_address) == ("00A1", "T1", "oss@hcmiu.edu.vn")


def test_the_text_never_leaves_the_laptop():
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hello", at(24), "UNIQUE-TEXT-9d2e for the laptop only")]))

    assert "UNIQUE-TEXT-9d2e" not in read(outlook).model_dump_json()


def test_an_exchange_sender_gets_their_real_address():
    mail = FakeMail("Re: Slide", at(24), "", name="Vo Minh Khoa", exchange_address="vmkhoa@hcmiu.edu.vn")

    [email] = read(FakeOutlook(FakeAccount(ME, [mail]))).emails

    assert (email.sender_address, email.from_lecturer) == ("vmkhoa@hcmiu.edu.vn", True)


def test_without_a_message_id_the_key_comes_from_outlooks_id():
    [email] = read(FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24), message_id=None, entry_id="00C7")]))).emails

    assert email.key == hashlib.sha256(b"00C7").hexdigest()


def test_without_a_delivery_time_it_reads_outlooks_local_time():
    mail = FakeMail("Hi", at(24))
    mail.PropertyAccessor.values.pop(outlook_reader.PR_DELIVERY_TIME)

    [email] = read(FakeOutlook(FakeAccount(ME, [mail]))).emails

    assert email.received_at == datetime(2026, 9, 24, 8, 0).astimezone(timezone.utc)


def test_it_waits_for_outlook_to_connect():
    sleeps = []

    result = read(FakeOutlook(FakeAccount(ME), modes=(400, 400, 700)), sleeps=sleeps)

    assert (result.connected, sleeps) == (True, [2, 2])


def test_an_offline_outlook_still_gives_what_it_has():
    sleeps = []
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24))]), modes=(400,))

    result = read(outlook, sleeps=sleeps)

    assert (result.connected, len(result.emails), sum(sleeps)) == (False, 1, 30)


def test_an_account_that_is_gone_means_outlook_is_not_set_up():
    with pytest.raises(OutlookNotSetUp, match="no longer in Outlook"):
        read(FakeOutlook(FakeAccount("someone@else.com")))


def test_no_outlook_means_not_set_up():
    def missing():
        raise OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")

    with pytest.raises(OutlookNotSetUp):
        read_outlook(ME, SINCE, CONTEXT, open_outlook=missing)


def test_outlook_refusing_the_read_blocks_it():
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24), broken=ComError(E_ABORT))]))

    with pytest.raises(OutlookBlocked, match="didn't let the agent"):
        read(outlook)


def test_any_other_com_error_from_outlook_blocks_it():
    class Broken:
        def GetNamespace(self, name):
            raise ComError(-2147023174)  # RPC server unavailable

    with pytest.raises(OutlookBlocked):
        read(Broken())


def test_one_unreadable_email_is_skipped(caplog):
    caplog.set_level(logging.WARNING)
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Broken", at(25), broken=ComError(-2147221233), entry_id="00D1"),
                                           FakeMail("Fine", at(24), entry_id="00D2")]))

    assert [e.subject for e in read(outlook).emails] == ["Fine"]
    assert "Skipped 1 emails" in caplog.text


def test_an_outlook_that_does_not_answer_is_given_up_after_the_time_limit():
    stuck = threading.Event()

    def never_answers():
        stuck.wait(5)
        return FakeOutlook(FakeAccount(ME))

    with pytest.raises(OutlookBlocked, match="within 3 minutes"):
        read_outlook(ME, SINCE, CONTEXT, open_outlook=never_answers, limit=timedelta(seconds=0.2))
    stuck.set()


def test_at_most_2000_emails_newest_first():
    mails = [FakeMail(f"Mail {i}", at(1) + timedelta(minutes=i), message_id=f"<{i}@x>", entry_id=f"{i:04X}")
             for i in range(2005)]

    result = read(FakeOutlook(FakeAccount(ME, mails)))

    assert (len(result.emails), result.emails[0].subject) == (2000, "Mail 2004")


def test_accounts():
    assert accounts(FakeOutlook(FakeAccount(ME), FakeAccount("me@gmail.com"))) == [ME, "me@gmail.com"]


@pytest.mark.parametrize("link, expected", [
    ("sla-mail:00AB12", "00AB12"),
    ("sla-mail:00AB12/", "00AB12"),
    ("sla-mail:00ab12", None),
    ("javascript:alert(1)", None),
    ("sla-mail:" + "A" * 513, None),
    ("", None),
])
def test_entry_id_from_link(link, expected):
    assert entry_id_from_link(link) == expected


def test_open_email_shows_it_in_outlook():
    mail = FakeMail("Hi", at(24), entry_id="00E1")

    open_email("00E1", open_outlook=lambda: FakeOutlook(FakeAccount(ME, [mail])))

    assert mail.displayed == 1


def test_open_email_that_is_gone():
    with pytest.raises(EmailNotFound):
        open_email("00E9", open_outlook=lambda: FakeOutlook(FakeAccount(ME)))


def test_the_reader_only_reads():
    source = Path(outlook_reader.__file__).read_text(encoding="utf-8")
    code = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
    code = re.sub(r'"""[\s\S]*?"""', "", code)

    assert not re.findall(r"\.(Send|Delete|Move|Copy|Save|SaveAs|UnRead|MarkAsRead)\b", code)
