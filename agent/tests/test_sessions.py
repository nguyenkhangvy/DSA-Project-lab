"""The times an event takes place, found in an email's subject and text on the laptop (spec
2026-09-28-mailbox-events-design.md, section 3.2). Only days and times ever leave the laptop."""

import unicodedata
from datetime import date, time

import pytest

from sla_agent.class_changes import MAX_SESSIONS, Session, sessions_in

ARRIVED = date(2026, 9, 28)  # Mon, in Vietnam


def found(text):
    return [(s.day.strftime("%d/%m"), s.start.strftime("%H:%M"), s.end and s.end.strftime("%H:%M"))
            for s in sessions_in(text, ARRIVED)]


@pytest.mark.parametrize("written, start", [
    ("13:00", "13:00"), ("13.00", "13:00"), ("13h", "13:00"), ("13h00", "13:00"), ("13h30", "13:30"),
    ("13g", "13:00"), ("13g00", "13:00"), ("8g30", "08:30"), ("1:00 PM", "13:00"), ("1 PM", "13:00"),
    ("1:30 pm", "13:30"), ("9 AM", "09:00"), ("12 PM", "12:00"), ("12:30 AM", "00:30"),
])
def test_each_way_of_writing_a_time(written, start):
    assert found(f"Workshop ngày 01/10/2026 lúc {written}") == [("01/10", start, None)]


@pytest.mark.parametrize("written", [
    "13:00 - 16:30", "13h00 – 16h30", "13:00—16:30", "13g00 đến 16g30", "1:00 PM to 4:30 PM", "13:00 until 16:30",
    "1:00 – 4:30 PM", "từ 13h đến 16h30",
])
def test_a_start_and_an_end(written):
    assert found(f"Thời gian: {written}, ngày 01/10/2026") == [("01/10", "13:00", "16:30")]


@pytest.mark.parametrize("text", [
    "Phòng 301, ngày 01/10, 150 chỗ",
    "Giải nhất 15.000.000 VNĐ, ngày 01/10",
    "Hotline 028.3724.4270, ngày 01/10",
    "Tuần 4: từ 28/9 đến 05/10/2026",
    "Thời gian: 14h",
])
def test_no_session_without_both_a_time_and_a_day(text):
    assert found(text) == []


def test_a_time_takes_the_day_from_the_sentence_above():
    assert found("Ngày: 01/10/2026\nThời gian: 13h00 – 16h00\nĐịa điểm: Hội trường A2") == [("01/10", "13:00", "16:00")]


def test_the_subject_can_give_the_day():
    assert found("[THƯ MỜI] Workshop ngày 01/10\nThời gian: 13h30 – 16h30") == [("01/10", "13:30", "16:30")]


def test_several_days_with_one_time_give_one_session_each():
    assert found("ngày 29/09 và 01/10, 13:00–14:00") == [("29/09", "13:00", "14:00"), ("01/10", "13:00", "14:00")]


def test_several_times_on_one_day_give_one_session_each():
    assert found("Ca 1: 8:00–10:00; Ca 2: 13:00–15:00 ngày 30/9") == [
        ("30/09", "08:00", "10:00"), ("30/09", "13:00", "15:00")]


def test_as_many_days_as_times_pair_in_order():
    assert found("29/9 lúc 8h, 30/9 lúc 14h") == [("29/09", "08:00", None), ("30/09", "14:00", None)]


def test_a_deadline_is_not_a_session_and_gives_no_day_to_the_next_sentence():
    assert found("Hạn đăng ký: 23h59 ngày 25/9\nThời gian: 14h ngày 30/9") == [("30/09", "14:00", None)]
    assert found("Hạn chót: 30/9\nThời gian: 14h") == []
    assert found("Deadline: 30/9 at 5 PM") == []


def test_bare_han_and_truoc_are_not_deadline_words():
    assert found("Số lượng có hạn, có mặt trước 15 phút: 14h00 ngày 30/9") == [("30/09", "14:00", None)]


def test_days_before_the_email_arrived_are_dropped():
    assert found("ngày 20/9 lúc 14h và ngày 30/9 lúc 14h") == [("30/09", "14:00", None)]


def test_a_repeated_day_and_start_is_kept_once_with_the_end_found():
    assert found("Workshop 30/9 lúc 14h\nThời gian: 14h00 – 16h00 ngày 30/9") == [("30/09", "14:00", "16:00")]


def test_an_end_that_is_not_after_the_start_is_dropped():
    assert found("22h00 - 01h00 ngày 30/9") == [("30/09", "22:00", None)]


def test_times_and_dates_in_links_are_ignored():
    assert found("Đăng ký: https://example.com/event-30-9-14h00") == []


def test_at_most_ten_sessions_in_time_order():
    days = ", ".join(f"{d}/10" for d in range(12, 0, -1))

    sessions = sessions_in(f"Các buổi: {days}, lúc 18h", ARRIVED)

    assert len(sessions) == MAX_SESSIONS
    assert sessions[0] == Session(date(2026, 10, 1), time(18, 0))
    assert sessions == sorted(sessions)


def test_english_invitations():
    assert found("Time: 9 AM to 11:30 AM, October 3, 2026") == [("03/10", "09:00", "11:30")]
    assert found("Thời gian: 8:00 a.m. - 10:00 a.m. ngày 3/10") == [("03/10", "08:00", "10:00")]


def test_vietnamese_typed_with_separate_accent_marks_reads_the_same():
    text = unicodedata.normalize("NFD", "Hạn đăng ký: 23h59 ngày 25/9\nThời gian: 14h ngày 30/9")

    assert found(text) == [("30/09", "14:00", None)]


def test_nothing():
    assert sessions_in("", ARRIVED) == []
    assert sessions_in(None, ARRIVED) == []
