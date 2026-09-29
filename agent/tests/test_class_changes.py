"""The agent's class-change reader. contract/samples/class-changes/sentences.json is shared with the website's
Java reader (ClassChangesTest), so both read every announcement the same way."""

import json
import unicodedata
from datetime import date, datetime, time
from pathlib import Path

import pytest

from sla_agent.class_changes import Announced, dates_in, read_announcement

SENTENCES = Path(__file__).resolve().parents[2] / "contract" / "samples" / "class-changes" / "sentences.json"
CASES = json.loads(SENTENCES.read_text(encoding="utf-8"))["cases"]
POSTED = datetime(2026, 9, 20, 2, 0)  # Sun 20/09 09:00 in Vietnam


def expected(change):
    def clock(value):
        return time.fromisoformat(value) if value else None

    return Announced(change["kind"], date.fromisoformat(change["day"]), clock(change.get("start")),
                     clock(change.get("end")), change.get("room"))


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_every_shared_example(case):
    found = read_announcement(case["title"], case["text"], datetime.fromisoformat(case["posted_at"]))

    # A title and a text that say the same thing give the same change twice; the file lists it once.
    assert list(dict.fromkeys(found)) == [expected(change) for change in case["changes"]]


def test_vietnamese_typed_with_separate_accent_marks_reads_the_same():
    title = unicodedata.normalize("NFD", "Lớp học trực tuyến ngày 24/9")

    assert read_announcement(title, "", POSTED) == [Announced("online", date(2026, 9, 24))]


def test_dates_in_a_whole_text_from_the_day_it_arrived():
    text = ("Thời gian: 14:00 - 16:30, ngày 29/09/2026. Hạn đăng ký: 22/9. Tuần 4: từ 28/9 đến 05/10/2026.\n"
            "Link: https://iujobhub.com/ws-12-10-2026 ngày 18 tháng 10")

    assert dates_in(text, date(2026, 9, 24)) == [
        date(2026, 9, 28), date(2026, 9, 29), date(2026, 10, 5), date(2026, 10, 18)]


def test_an_emails_dotted_dates_are_dates():
    assert dates_in("Hạn cuối: 05.10.2026. Giải thưởng 15.000.000 VNĐ, hotline 028.3724.4270", date(2026, 9, 24)) == [
        date(2026, 10, 5)]


def test_announcements_keep_the_date_formats_the_java_twin_reads():
    # Blackboard announcements are read by ClassChanges.java too (sentences.json keeps the two in step), so a
    # dotted date stays unread there.
    assert read_announcement("Make-up class on 03.10.2026 at 8:00", "", POSTED) == []


def test_dates_in_nothing():
    assert dates_in("", date(2026, 9, 24)) == []
    assert dates_in(None, date(2026, 9, 24)) == []
