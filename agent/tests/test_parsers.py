"""Readers tested against anonymized copies of real EduSoft pages (agent/tests/fixtures).

Expected values were worked out by hand from the pages: week 1 starts Monday
31/08/2026, and IU's periods are 1: 08:00-08:50 ... 4: 10:35-11:25,
7: 13:15-14:05 ... 9: 14:55-15:45.
"""

from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from sla_agent.errors import ParseError
from sla_agent.parsers.exams import parse_exams
from sla_agent.parsers.timetable import parse_timetable

FIXTURES = Path(__file__).parent / "fixtures"
VN = timezone(timedelta(hours=7))


def page(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def vn(year, month, day, hour, minute):
    return datetime(year, month, day, hour, minute, tzinfo=VN)


# ---- Timetable -----------------------------------------------------------------


@pytest.fixture(scope="module")
def timetable():
    return parse_timetable({"weekly": page("timetable-weekly.html"), "semester": page("timetable-semester.html")})


def course(timetable, code):
    [found] = [c for c in timetable.courses if c.course_code == code]
    return found


def test_reads_the_semester_and_every_course(timetable):
    assert (timetable.term_code, timetable.term_name) == ("20261", "Học kỳ 1 - Năm học 2026-2027")
    assert sorted(c.course_code for c in timetable.courses) == [
        "EN011IU", "IT007WE", "IT013IU", "IT090IU", "IT093IU", "IT120IU", "MA026IU", "PH012IU",
    ]


def test_course_details_come_from_the_semester_view_and_lecturer_names_from_the_weekly_view(timetable):
    web = course(timetable, "IT093IU")

    assert (web.course_name, web.group, web.credits, web.lecturer) == (
        "Web Application Development", "02", 4.0, "Lecturer 14",
    )


def test_every_schedule_line_becomes_dated_classes(timetable):
    meetings = sorted(course(timetable, "IT093IU").meetings, key=lambda m: m.start_at)

    # Tuesdays, periods 1-3, weeks 2-9 and 11-17 (15) + Saturdays, periods 1-4, weeks 5-9 and 11-17 (12)
    assert len(meetings) == 27
    assert (meetings[0].start_at, meetings[0].end_at, meetings[0].room) == (
        vn(2026, 9, 8, 8, 0), vn(2026, 9, 8, 10, 30), "R110",
    )
    saturday_lab = [m for m in meetings if m.start_at == vn(2026, 10, 3, 8, 0)]
    assert [(m.end_at, m.room) for m in saturday_lab] == [(vn(2026, 10, 3, 11, 25), "R113")]
    assert (meetings[-1].start_at, meetings[-1].room) == (vn(2026, 12, 26, 8, 0), "R113")


def test_weeks_marked_with_a_dash_have_no_class(timetable):
    starts = {m.start_at.date() for m in course(timetable, "IT093IU").meetings}

    assert datetime(2026, 11, 3).date() not in starts  # Tuesday of week 10 (midterm break)
    assert datetime(2026, 9, 1).date() not in starts  # Tuesday of week 1


def test_a_course_split_between_a_room_and_online(timetable):
    meetings = sorted(course(timetable, "IT007WE").meetings, key=lambda m: m.start_at)

    rooms = [m.room for m in meetings]
    assert (rooms.count("R104"), rooms.count("ONLINE1")) == (5, 10)
    assert (meetings[0].start_at, meetings[0].end_at) == (vn(2026, 9, 12, 13, 15), vn(2026, 9, 12, 15, 45))
    assert min(m.start_at for m in meetings if m.room == "ONLINE1") == vn(2026, 10, 17, 13, 15)


def test_lecturer_is_left_empty_without_the_weekly_view():
    timetable = parse_timetable({"semester": page("timetable-semester.html")})

    assert {c.lecturer for c in timetable.courses} == {None}


@pytest.mark.parametrize(
    "old, new",
    [
        ("grid-roll2", "grid-renamed"),
        ("padding-top:5px'>Hai</div>", "padding-top:5px'>Someday</div>"),
        ("ddrivetiptuan('-23456789-1234567')", "ddrivetiptuan('-23456789-1234568')"),
        # Skills' weekday column loses its second line while the other columns keep two.
        ("<td align='center'>Bảy</td></tr></table><div style='height:22px;vertical-align:middle;padding-top:5px'>Bảy</div>",
         "<td align='center'>Bảy</td></tr></table>"),
    ],
    ids=["table-missing", "unknown-weekday", "week-digit-mismatch", "line-counts-differ"],
)
def test_a_page_that_does_not_look_right_raises_parse_error(old, new):
    semester = page("timetable-semester.html")
    assert old in semester, "the test's pattern must exist in the fixture"

    with pytest.raises(ParseError):
        parse_timetable({"semester": semester.replace(old, new, 1)})


# ---- Exams ---------------------------------------------------------------------


def test_final_and_midterm_exams_of_the_requested_semester():
    exams = parse_exams({"term": "20252", "final": page("exams-final.html"), "midterm": page("exams-midterm.html")})

    assert exams.term_code == "20252"
    assert len(exams.exams) == 8
    [logic] = [e for e in exams.exams if e.course_code == "IT067IU"]
    assert (logic.course_name, logic.exam_type, logic.start_at, logic.duration_min, logic.room, logic.notes) == (
        "Digital Logic Design", "final", vn(2026, 6, 8, 8, 0), None, "R103", "Thi tập trung",
    )
    [database_midterm] = [e for e in exams.exams if e.course_code == "IT079IU" and e.exam_type == "midterm"]
    assert (database_midterm.start_at, database_midterm.room) == (vn(2026, 3, 11, 15, 15), "R100")


def test_exams_of_another_semester_are_ignored():
    exams = parse_exams({"term": "20261", "final": page("exams-final.html"), "midterm": page("exams-midterm.html")})

    assert (exams.term_code, exams.exams) == ("20261", [])


def test_no_published_exams_is_an_empty_list():
    exams = parse_exams({"term": "20253", "final": page("exams-final-none.html"), "midterm": None})

    assert (exams.term_code, exams.exams) == ("20253", [])


@pytest.mark.parametrize(
    "part, old, new",
    [
        ("final", 'name="ctl00$ContentPlaceHolder1$ctl00$dropNHHK"', 'name="renamed"'),
        ("midterm", 'id="ContentPlaceHolder1_ctl00_lblTitle"', 'id="renamed"'),
        ("final", ">Ngày Thi<", ">Ngay<"),
        ("final", "08/06/2026", "8 June"),
    ],
    ids=["final-term-dropdown-missing", "midterm-title-missing", "date-column-missing", "bad-date"],
)
def test_an_exam_page_that_does_not_look_right_raises_parse_error(part, old, new):
    pages = {"term": "20252", "final": page("exams-final.html"), "midterm": page("exams-midterm.html")}
    assert old in pages[part], "the test's pattern must exist in the fixture"
    pages[part] = pages[part].replace(old, new, 1)

    with pytest.raises(ParseError):
        parse_exams(pages)
