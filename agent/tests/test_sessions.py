"""The times an event takes place, found in an email's subject and text on the laptop (spec
2026-09-28-mailbox-events-design.md, section 3.2). Only days and times ever leave the laptop."""

import unicodedata
from datetime import date, time

import pytest

from sla_agent.class_changes import MAX_SESSIONS, Session, register_by_in, sessions_in

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


def test_a_heading_line_can_give_the_day():
    assert found("Workshop ngày 01/10\nThời gian: 13h30 – 16h30") == [("01/10", "13:30", "16:30")]


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


def test_a_dotted_date_is_a_date_not_a_time():
    assert found("Hội thảo ngày 01.10.2026, 14h00 - 16h00") == [("01/10", "14:00", "16:00")]
    assert found("Thông báo ngày 01.10.2026") == []


def test_the_edges_of_a_day_are_not_event_times():
    # STAR AWARD: a contest round open from the start of one day to the end of another.
    assert found("Tuần 3: Từ 00g00 ngày 21/9 đến 23g59 ngày 27/9/2026;\n"
                 "Tuần 4: Từ 00g00 ngày 28/9 đến 23g59 ngày 05/10/2026.") == []
    assert found("Ngày 30/9: cổng mở lúc 0:00, đóng lúc 23:59") == []
    assert found("Đêm nhạc ngày 30/9, 19h00 - 23h59") == [("30/09", "19:00", "23:59")]


@pytest.mark.parametrize("text", [
    "Thời gian: 14h00 ngày 30/9, kéo dài 2h",
    "Workshop 14h ngày 30/9 (2h)",
    "Ngày 30/9 lúc 14h. Thời lượng: 1h30",
    "Ngày 30/9 lúc 14h, mỗi buổi trong vòng 2h",
    "Talk on 30/9 at 14h, lasting 2h",
])
def test_a_length_is_not_a_time(text):
    assert found(text) == [("30/09", "14:00", None)]


def test_small_hours_are_still_times_with_am_or_minutes():
    assert found("Chạy bộ ngày 30/9 lúc 5 AM") == [("30/09", "05:00", None)]
    assert found("Chạy bộ ngày 30/9 lúc 5h30") == [("30/09", "05:30", None)]


@pytest.mark.parametrize("written, start", [
    ("2h chiều", "14:00"), ("2h30 chiều", "14:30"), ("7h tối", "19:00"), ("8 giờ sáng", None), ("8h sáng", "08:00"),
    ("5h sáng", "05:00"), ("1h trưa", "13:00"), ("11h trưa", "11:00"), ("12h trưa", "12:00"), ("14h chiều", "14:00"),
])
def test_a_time_of_day_word_after_the_hour(written, start):
    assert found(f"Ngày 30/9 lúc {written}") == ([("30/09", start, None)] if start else [])


def test_a_range_ending_in_the_afternoon():
    assert found("Ngày 30/9, từ 1h - 3h chiều") == [("30/09", "13:00", "15:00")]


def test_due_to_is_not_a_deadline():
    assert found("Due to the rain, the talk moves to 14h ngày 30/9") == [("30/09", "14:00", None)]
    assert found("Report due 30/9 at 5 PM") == []


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


# ---- check-in times (addendum A.1) --------------------------------------------------------------------------------

BEAN_TO_BOLD = """Thông tin chi tiết chương trình:
⏰Thời gian chương trình: 14:00 - 16:30, ngày 29/09/2026.
⏰Thời gian check in:  13:00 - 13:45, ngày 29/09/2026.
📍 Địa điểm: Phòng A2.104, Trường Đại học Quốc tế (IU)."""


def test_a_check_in_time_joins_its_event():
    assert found(BEAN_TO_BOLD) == [("29/09", "13:00", "16:30")]


@pytest.mark.parametrize("check_in", ["Check-in: 7h30", "CHECKIN lúc 7h30", "Sinh viên có mặt lúc 7h30 để điểm danh"])
def test_every_way_of_writing_check_in(check_in):
    assert found(f"Ngày 03/10/2026. {check_in}. Chương trình: 8h00 - 11h00") == [("03/10", "07:30", "11:00")]


def test_a_check_in_joins_the_earliest_session_after_it_that_day():
    text = "Ngày 03/10: sáng 8h00 - 10h00, chiều 13h30 - 15h00. Check in: 13h00 - 13h20, ngày 03/10"

    assert found(text) == [("03/10", "08:00", "10:00"), ("03/10", "13:00", "15:00")]


@pytest.mark.parametrize("text", [
    "Ngày 29/9: check-in 13h00, chương trình 14h00 - 16h30",
    "Ngày 29/9: chương trình 14h00 - 16h30; điểm danh lúc 13h00",
])
def test_a_check_in_in_the_same_sentence_as_the_programme_joins_it(text):
    assert found(text) == [("29/09", "13:00", "16:30")]


def test_a_check_in_without_a_later_session_stays_on_its_own():
    assert found("Check in: 13:00 - 13:45, ngày 29/09/2026") == [("29/09", "13:00", "13:45")]
    assert found("Chương trình 9h00 ngày 29/09. Check in 17h00 ngày 29/09") == [
        ("29/09", "09:00", None), ("29/09", "17:00", None)]


# ---- the registration deadline (addendum A.2) ---------------------------------------------------------------------

CLOSING = """Link đăng ký: https://iuoss.com/BM-HTSV-2026
Thông tin đăng ký Lễ Bế mạc HTSV như sau:
 Thời gian: 9g45 ngày 30/9/2026 (Thứ Tư)
 Thời hạn đăng ký: đến hết ngày 22/9/2026 hoặc cho đến khi đủ số lượng."""


def test_the_closing_ceremony_closes_registration_on_22_9_and_takes_place_on_30_9():
    assert register_by_in(CLOSING, date(2026, 9, 20)) == date(2026, 9, 22)
    assert sessions_in(CLOSING, date(2026, 9, 20)) == [Session(date(2026, 9, 30), time(9, 45))]


@pytest.mark.parametrize("text, deadline", [
    ("Thời hạn đăng ký: đến hết ngày 22/9/2026", date(2026, 9, 22)),
    ("Hạn đăng ký: 23h59 ngày 25/9", date(2026, 9, 25)),
    ("Đăng ký trước ngày 25/9. Thời gian: 14h ngày 30/9", date(2026, 9, 25)),
    ("REGISTRATION DEADLINE: September 25, 2026", date(2026, 9, 25)),
    ("Hạn chót đăng ký: 24/9. Gia hạn: hạn đăng ký đến 27/9", date(2026, 9, 27)),
    ("Hạn đăng ký: 20/9", date(2026, 9, 20)),
    ("Hạn đăng ký: 25.09.2026", date(2026, 9, 25)),
])
def test_each_way_of_writing_a_registration_deadline(text, deadline):
    assert register_by_in(text, ARRIVED) == deadline


@pytest.mark.parametrize("text", [
    "Hạn nộp bài: 30/9",
    "Hạn chót khảo sát: 30/9",
    "Link đăng ký: https://example.com/dang-ky-30-9 . Thời gian: 14h ngày 30/9",
    "Đăng ký tham gia workshop ngày 30/9",
    "",
    None,
])
def test_not_a_registration_deadline(text):
    assert register_by_in(text, ARRIVED) is None
