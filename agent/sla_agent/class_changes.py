"""Class changes announced by lecturers: online, cancelled and make-up classes, and the dates in a text.
Pure functions.

A sentence that mentions a class, a change word and a date makes a change; each date takes the
nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
docs/superpowers/specs/2026-09-26-class-changes-and-to-submit-design.md, section 2.

The website reads Blackboard announcements with its Java twin
(web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java); the agent reads lecturers'
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it."""

import re
import unicodedata
from datetime import date, time, timedelta
from typing import NamedTuple

VIETNAM_OFFSET = timedelta(hours=7)

CHANGE_WORDS = re.compile(
    r"(?P<makeup>\bmake[\s-]?up\b|\bbù\b)"
    r"|(?P<cancelled>\bcancel(?:s|ed|led|ing|ling|lations?)?\b|\bno class(?:es)?\b|\bnghỉ\b|\bhủy\b|\bhuỷ\b)"
    r"|(?P<online>\bonline\b|\btrực tuyến\b)",
    re.IGNORECASE,
)
CLASS_WORDS = re.compile(
    r"\b(?:class(?:es)?|lectures?|sessions?|lessons?|lớp|buổi|tiết|(?:học|dạy) (?:online|trực tuyến|bù)|nghỉ học)\b",
    re.IGNORECASE,
)

MONTH_NAMES = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6, "july": 7, "august": 8,
    "september": 9, "october": 10, "november": 11, "december": 12,
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7, "aug": 8, "sep": 9, "sept": 9,
    "oct": 10, "nov": 11, "dec": 12,
}
MONTH = "|".join(sorted(MONTH_NAMES, key=len, reverse=True))
ORDINAL = r"(?:st|nd|rd|th)?"
YEAR = r"(?:,?\s+(?P<year>\d{4}))?"
DATE_FORMATS = [
    re.compile(rf"\b(?P<month>{MONTH})\b\.?\s+(?P<day>\d{{1,2}}){ORDINAL}(?!\d){YEAR}", re.IGNORECASE),
    re.compile(rf"(?<!\d)(?P<day>\d{{1,2}}){ORDINAL}\s+(?:of\s+)?(?P<month>{MONTH})\b\.?{YEAR}", re.IGNORECASE),
    re.compile(r"(?<![\w/.:])(?P<day>\d{1,2})/(?P<month>\d{1,2})(?:/(?P<year>\d{4}))?(?![\d/])"),
    re.compile(r"(?<![\w/.:-])(?P<day>\d{1,2})-(?P<month>\d{1,2})-(?P<year>\d{4})(?![\d-])"),
    re.compile(r"ngày\s+(?P<day>\d{1,2})\s+tháng\s+(?P<month>\d{1,2})(?:\s+năm\s+(?P<year>\d{4}))?", re.IGNORECASE),
]
TIME = re.compile(
    r"(?<![\w/.:])(?P<hour>\d{1,2})(?:[:hg](?P<minute>\d{2})?|(?=\s*[ap]\.?m\b))(?:\s*(?P<ampm>[ap])\.?m\.?)?(?!\w)",
    re.IGNORECASE,
)
RANGE_JOIN = re.compile(r"\s*(?:-|–|to|đến)\s*", re.IGNORECASE)
ROOM = re.compile(r"(?<![\w.])(?:[A-Z]{1,2}\d\.\d{3}|R\d{3})(?!\w|\.\w)")
URL = re.compile(r"https?://\S+")
ABBREVIATIONS = ("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
                 "dr", "mr", "ms", "mrs")
SENTENCE_END = re.compile(
    r"(?<=[.!?])" + "".join(rf"(?<!\b{a}\.)" for a in ABBREVIATIONS) + r"(?<![ap]\.m\.)\s+|\n+",
    re.IGNORECASE,
)


class Announced(NamedTuple):
    kind: str  # "online" / "cancelled" / "makeup"
    day: date  # in Vietnam
    start: time | None = None  # make-up only
    end: time | None = None
    room: str | None = None


def _date(match, posted_day):
    month = match.group("month")
    month = MONTH_NAMES[month.lower()] if month.isalpha() else int(month)
    day, year = int(match.group("day")), match.group("year")
    try:
        if year:
            return date(int(year), month, day)
        candidates = [date(posted_day.year + k, month, day) for k in (-1, 0, 1)]
    except ValueError:
        return None
    return min(candidates, key=lambda d: abs(d - posted_day))


def _dates(sentence, posted_day):
    """[(start, end, date)] in `sentence`, from the posting day on, in the order they appear; start and end
    are the date's character positions."""
    found, taken = [], []
    for pattern in DATE_FORMATS:
        for match in pattern.finditer(sentence):
            if any(match.start() < end and start < match.end() for start, end in taken):
                continue
            taken.append(match.span())
            day = _date(match, posted_day)
            if day is not None and day >= posted_day:
                found.append((match.start(), match.end(), day))
    return sorted(found)


def _time(match, ampm=None):
    """The time `match` names, read with `ampm` ("a" / "p") when given, else with its own AM/PM."""
    hour, minute = int(match.group("hour")), int(match.group("minute") or 0)
    ampm = (ampm or match.group("ampm") or "").lower()
    if ampm == "p" and hour < 12:
        hour += 12
    if ampm == "a" and hour == 12:
        hour = 0
    return time(hour, minute) if hour < 24 and minute < 60 else None


def _times(sentence):
    """(start, end) of the first time in `sentence`; end only for a range like 8:00-9:40. In a range, a start
    without AM/PM takes the end's when that keeps it before the end: "1:15 to 3:45 PM" is 13:15-15:45."""
    matches = list(TIME.finditer(sentence))
    if not matches:
        return None, None
    start, end = _time(matches[0]), None
    if len(matches) > 1 and RANGE_JOIN.fullmatch(sentence[matches[0].end():matches[1].start()]):
        end = _time(matches[1])
        if start and end and not matches[0].group("ampm") and matches[1].group("ampm"):
            shifted = _time(matches[0], ampm=matches[1].group("ampm"))
            if shifted and shifted < end:
                start = shifted
    return start, end


def read_announcement(title, text, posted_at):
    """The class changes one announcement makes. posted_at: naive UTC."""
    posted_day = (posted_at + VIETNAM_OFFSET).date()
    found = []
    for sentence in [title or ""] + SENTENCE_END.split(text or ""):
        sentence = URL.sub(" ", unicodedata.normalize("NFC", sentence))
        words = [(m.start(), m.lastgroup) for m in CHANGE_WORDS.finditer(sentence)]
        if not words or not CLASS_WORDS.search(sentence):
            continue
        # Online words decide only when there is no cancel or make-up word: "make-up class online on 3/10"
        # is an online make-up class.
        deciding = [word for word in words if word[1] != "online"] or words
        dates = _dates(sentence, posted_day)
        for i, (date_start, date_end, day) in enumerate(dates):
            kind = min(deciding, key=lambda word: abs(word[0] - date_start))[1]
            if kind != "makeup":
                found.append(Announced(kind, day))
                continue
            # A make-up's time and room come from the text after its date (up to the next date), or else
            # from the text before it (back to the previous date).
            after = sentence[date_end:dates[i + 1][0] if i + 1 < len(dates) else len(sentence)]
            before = sentence[dates[i - 1][1] if i > 0 else 0:date_start]
            start, end = _times(after if TIME.search(after) else before)
            room = ROOM.search(after) or ROOM.search(before)
            online = any(k == "online" for _, k in words)
            found.append(Announced("makeup", day, start, end, "Online" if online else room and room.group(0)))
    return found


def dates_in(text, from_day):
    """Every date in `text` from `from_day` on, sorted, each once. Dates inside links are ignored; a date
    without a year takes the year that puts it closest to `from_day`."""
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    return sorted({day for _, _, day in _dates(text, from_day)})
