"""Class changes announced by lecturers: online, cancelled and make-up classes; the dates in a text; and the
times an event takes place (sessions). Pure functions.

A sentence that mentions a class, a change word and a date makes a change; each date takes the
nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
docs/superpowers/specs/2026-09-26-class-changes-and-to-submit-design.md, section 2.

The website reads Blackboard announcements with its Java twin
(web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java); the agent reads lecturers'
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it.

Sessions follow docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 3.2, and its addendum of
2026-09-29 (check-in times, the registration deadline)."""

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


# Sessions (mailbox-events 3.2): a time is a start alone, or a start and an end joined by one of these. "01.10"
# followed by ".2026" is a date, not a time.
SESSION_TIME = re.compile(
    r"(?<![\w/.:,])(?P<hour>\d{1,2})(?:(?:[:.](?=\d{2})|[hg])(?P<minute>\d{2})?|(?=\s*[ap]\.?m\b))"
    r"(?:\s*(?P<ampm>[ap])\.?m\.?)?(?!\w|\.\d)",
    re.IGNORECASE,
)
SESSION_JOIN = re.compile(r"\s*(?:-|–|—|to|until|đến)\s*", re.IGNORECASE)
# Words of a deadline, compared without accents or letter case. Bare "hạn" and "trước" don't count: "Số lượng
# có hạn" and "có mặt trước 15 phút" sit next to real event times. "due" counts unless "to" follows: "due to the
# rain" is not a deadline.
DEADLINE_WORDS = ("hạn chót", "hạn đăng ký", "hạn nộp", "thời hạn", "trước ngày", "đăng ký trước", "deadline")
# A length, not a time: right after one of these words (compared the same way), or a bare hour under
# EARLIEST_BARE_HOUR without minutes or AM/PM, like "(2h)": events don't start in the small hours.
LENGTH_WORDS = ("thời lượng", "kéo dài", "trong vòng", "duration", "lasting", "lasts")
EARLIEST_BARE_HOUR = 6
MAX_SESSIONS = 10


def fold(text):
    """Lower case without accents: "Hóa Đơn" -> "hoa don"."""
    text = unicodedata.normalize("NFD", (text or "").replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def _words(words):
    """A pattern for any of `words`, on folded text."""
    return "|".join(r"\s+".join(fold(w).split()) for w in words)


DEADLINE = re.compile(r"\b(?:" + _words(DEADLINE_WORDS) + r"|due(?!\s+to\b))\b")
# On folded text, right after a time: the time of day that says morning or afternoon ("2h chiều" is 14:00).
TIME_OF_DAY = re.compile(r"\s*(sang|chieu|toi|trua)\b")
LENGTH_BEFORE = re.compile(r"\b(?:" + _words(LENGTH_WORDS) + r")\s*:?\s*(?:khoang|about|around)?\s*$")
# On folded text: "check in", "check-in", "checkin", "điểm danh" mark a check-in time; "đăng ký", "register",
# "registration", "sign up" make a deadline sentence a registration deadline (addendum A.1 and A.2).
CHECK_IN = re.compile(r"\b(?:check\s*-?\s*in|diem\s+danh)\b")
REGISTER = re.compile(r"\b(?:dang\s+ky|register|registration|sign\s*-?\s*up)\b")


class Announced(NamedTuple):
    kind: str  # "online" / "cancelled" / "makeup"
    day: date  # in Vietnam
    start: time | None = None  # make-up only
    end: time | None = None
    room: str | None = None


class Session(NamedTuple):
    day: date  # in Vietnam
    start: time
    end: time | None = None


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


def _dates(sentence, posted_day, keep_past=False):
    """[(start, end, date)] in `sentence`, from the posting day on (or all of them with keep_past), in the order
    they appear; start and end are the date's character positions."""
    found, taken = [], []
    for pattern in DATE_FORMATS:
        for match in pattern.finditer(sentence):
            if any(match.start() < end and start < match.end() for start, end in taken):
                continue
            taken.append(match.span())
            day = _date(match, posted_day)
            if day is not None and (keep_past or day >= posted_day):
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


def _ampm(sentence, match):
    """"a" / "p" for this time: its own AM/PM, else a time of day right after it ("2h chiều", "7h tối", "8h sáng";
    "trưa" is noon, so only 1h–3h trưa are afternoon), else None."""
    if match.group("ampm"):
        return match.group("ampm").lower()
    word = TIME_OF_DAY.match(fold(sentence[match.end():]))
    if not word:
        return None
    if word.group(1) == "trua":
        return "p" if int(match.group("hour")) <= 3 else None
    return "a" if word.group(1) == "sang" else "p"


def _is_length(sentence, match, ampm):
    """Whether this time in `sentence` is a length ("kéo dài 2h", "(2h)") rather than when something starts."""
    if LENGTH_BEFORE.search(fold(sentence[:match.start()])):
        return True
    return match.group("minute") is None and ampm is None and int(match.group("hour")) < EARLIEST_BARE_HOUR


def _session_times(sentence, taken):
    """[(start, end)] of every time in `sentence` outside the spans in `taken` (its dates), lengths left out, in
    order. A start and an end joined by "-", "đến", "to" … make one range; the end is dropped when it isn't after
    the start. In a range, a start without AM/PM (or a time of day) takes the end's when that keeps it before the
    end: "1:00 – 2:30 PM", "1h - 3h chiều"."""
    matches = [m for m in SESSION_TIME.finditer(sentence)
               if not any(m.start() < end and start < m.end() for start, end in taken)]
    found, i = [], 0
    while i < len(matches):
        first, end = matches[i], None
        first_ampm = _ampm(sentence, first)
        start = _time(first, first_ampm)
        i += 1
        if i < len(matches) and SESSION_JOIN.fullmatch(sentence[first.end():matches[i].start()]):
            second = matches[i]
            second_ampm = _ampm(sentence, second)
            end = _time(second, second_ampm)
            i += 1
            if start and end and not first_ampm and second_ampm:
                shifted = _time(first, ampm=second_ampm)
                if shifted and shifted < end:
                    start, first_ampm = shifted, second_ampm
        if start is not None and not _is_length(sentence, first, first_ampm):
            found.append((start, end if end is not None and end > start else None))
    return found


def sessions_in(text, from_day):
    """The times an event takes place (mailbox-events 3.2): each sentence's times go with its dates, or with the
    dates of the nearest sentence above that has some. Several dates and one time, or one date and several
    times, give one session each; equal numbers pair in order. Sentences about a deadline are skipped. A check-in
    time joins the earliest other session of its day that starts at or after it (addendum A.1). Sessions before
    `from_day` are dropped; a repeated day and start is kept once, with the first end found; at most
    MAX_SESSIONS, in time order."""
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    above, found, check_ins = [], [], []
    for sentence in SENTENCE_END.split(text):
        folded = fold(sentence)
        if DEADLINE.search(folded):
            continue
        dates = _dates(sentence, from_day, keep_past=True)
        days = [day for _, _, day in dates]
        times = _session_times(sentence, [(start, end) for start, end, _ in dates])
        if days:
            above = days
        if not times or not above:
            continue
        pairs = zip(above, times) if len(above) == len(times) else [(d, t) for d in above for t in times]
        target = check_ins if CHECK_IN.search(folded) else found
        target.extend(Session(day, start, end) for day, (start, end) in pairs)
    for check_in in check_ins:
        later = [i for i, s in enumerate(found) if s.day == check_in.day and s.start >= check_in.start]
        if later:
            first = min(later, key=lambda i: found[i].start)
            found[first] = found[first]._replace(start=check_in.start)
        else:
            found.append(check_in)
    kept = {}
    for session in found:
        if session.day < from_day:
            continue
        key = (session.day, session.start)
        if key not in kept or (kept[key].end is None and session.end is not None):
            kept[key] = session if key not in kept else kept[key]._replace(end=session.end)
    return sorted(kept.values())[:MAX_SESSIONS]


def register_by_in(text, from_day):
    """The registration deadline (mailbox-events addendum A.2): the latest date in the sentences that have a deadline
    word and a registering word, or None. Dates in links are ignored; a date without a year takes the year closest
    to `from_day`."""
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    days = [day for sentence in SENTENCE_END.split(text)
            if DEADLINE.search(fold(sentence)) and REGISTER.search(fold(sentence))
            for _, _, day in _dates(sentence, from_day, keep_past=True)]
    return max(days, default=None)
