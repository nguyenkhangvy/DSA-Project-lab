"""Sorting one email on the laptop: who sent it, its categories, its dates, and the class changes it
announces. Pure functions. The rules are in docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md,
section 5.

The email's text is only read here, in memory. What leaves this module is a MailItem, which has no
field for text."""

import logging
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sla_contract.schema import MailClassChange, MailItem, MailSession

from sla_agent.class_changes import dates_in, fold, read_announcement, register_by_in, sessions_in

log = logging.getLogger(__name__)

VIETNAM_OFFSET = timedelta(hours=7)
BLACKBOARD_SENDER = "bb@hcmiu.edu.vn"
IU_DOMAIN = "hcmiu.edu.vn"
STUDENT_DOMAIN = "student.hcmiu.edu.vn"
MICROSOFT_DOMAINS = ("microsoft.com", "sharepointonline.com")
IU_ADDRESS = re.compile(r"([\w.+-]+)@hcmiu\.edu\.vn", re.IGNORECASE)

# The order categories are kept in when more than two match (spec 5.3).
ORDER = ("class", "school_task", "money", "event", "training_points", "requests_account", "system_notice",
         "promotion")
MAX_CATEGORIES = 2
MAX_DATES = 30
MAX_CLASS_CHANGES = 10

# Words are written as they are read; they are compared without accents or letter case.
SCHOOL_TASK_WORDS = ("khảo sát", "survey", "tạm trú", "cư trú", "sinh hoạt công dân", "bảo hiểm y tế", "BHYT",
                     "bắt buộc")
MONEY_WORDS = ("học bổng", "scholarship", "hóa đơn", "invoice", "học phí", "tuition", "thanh toán", "payment",
               "lệ phí")
EVENT_WORDS = ("thư mời", "workshop", "talkshow", "chuyên đề", "hội thảo", "seminar", "webinar", "cuộc thi",
               "contest", "casting", "hội thao", "khai mạc", "bế mạc", "ngày hội", "tuần lễ", "đăng ký tham gia",
               "đăng ký tham dự")
ACCOUNT_WORDS = ("password", "mật khẩu")
PROMOTION_WORDS = ("ưu đãi", "khuyến mãi", "giảm giá", "voucher", "discount")
TRAINING_POINTS = "điểm rèn luyện"
TEAMS_ADDED_WORDS = ("được thêm", "đã thêm", "added you")


@dataclass(frozen=True)
class Email:
    """One email as Outlook gives it to the agent. `text` stays on the laptop."""

    key: str
    entry_id: str
    thread_id: str | None
    received_at: datetime  # aware
    sender_name: str
    sender_address: str
    subject: str
    text: str


@dataclass(frozen=True)
class Context:
    """What the laptop knows about this semester, from the last EduSoft and Blackboard reads."""

    courses: tuple = ()  # (course code, course name, lecturer as EduSoft writes it, e.g. "P.Q.Hùng")
    bb_courses: tuple = ()  # (Blackboard course name, course code)


def _phrase(words):
    """A pattern matching any of `words` as whole words, on folded text."""
    parts = [r"\s+".join(re.escape(part) for part in fold(word).split()) for word in words]
    return re.compile(r"\b(?:" + "|".join(parts) + r")\b")


SCHOOL_TASK = _phrase(SCHOOL_TASK_WORDS)
MONEY = _phrase(MONEY_WORDS)
EVENT = _phrase(EVENT_WORDS)
ACCOUNT = _phrase(ACCOUNT_WORDS)
PROMOTION = _phrase(PROMOTION_WORDS)
TRAINING = _phrase([TRAINING_POINTS])
TEAMS_ADDED = _phrase(TEAMS_ADDED_WORDS)


def _domain(address):
    return address.rpartition("@")[2].lower()


def _local(address):
    return address.rpartition("@")[0].lower()


def _is_iu_staff_address(address):
    domain = _domain(address)
    return "@" in address and domain != STUDENT_DOMAIN and (domain == IU_DOMAIN or domain.endswith("." + IU_DOMAIN))


def name_handles(name):
    """The address beginnings an IU person's own name gives, in both word orders: "Vo Minh Khoa" ->
    vmkhoa, vominhkhoa, kmvo, khoaminhvo. Needs at least two words."""
    words = re.findall(r"[a-z]+", fold(name))
    if len(words) < 2:
        return set()
    handles = set()
    for order in (words, words[::-1]):
        handles.add("".join(word[0] for word in order[:-1]) + order[-1])
        handles.add("".join(order))
    return handles


def lecturer_handle(lecturer):
    """EduSoft's short lecturer name as an address beginning: "P.Q.Hùng" -> "pqhung"."""
    return re.sub(r"[^a-z]", "", fold(lecturer))


def is_microsoft_notice(email):
    """Teams "added you to a group" notices and anything Microsoft sends by itself."""
    domain = _domain(email.sender_address)
    if any(domain == d or domain.endswith("." + d) for d in MICROSOFT_DOMAINS):
        return True
    subject = fold(email.subject)
    return "microsoft teams" in subject and bool(TEAMS_ADDED.search(subject))


def blackboard_lecturer(email):
    """For a Blackboard announcement, the lecturer's address beginning from the sender name
    ("Đặng Văn Long - dvlong@hcmiu.edu.vn" -> "dvlong"); else None. Receipts named "bb@hcmiu.edu.vn" have none."""
    if email.sender_address.lower() != BLACKBOARD_SENDER:
        return None
    for local in IU_ADDRESS.findall(email.sender_name or ""):
        if local.lower() != "bb":
            return local.lower()
    return None


def sender_handle(email):
    """The address beginning of the person behind the email: the lecturer in a Blackboard announcement, else
    the sender, when the address is an IU staff address; else None."""
    lecturer = blackboard_lecturer(email)
    if lecturer:
        return lecturer
    return _local(email.sender_address) if _is_iu_staff_address(email.sender_address) else None


def is_from_lecturer(email, context):
    """Spec 5.1: a Blackboard announcement, a timetable lecturer, or an IU person writing from their own address."""
    if is_microsoft_notice(email):
        return False
    if blackboard_lecturer(email):
        return True
    handle = sender_handle(email)
    if not handle:
        return False
    if any(lecturer and handle == lecturer_handle(lecturer) for _, _, lecturer in context.courses):
        return True
    return handle in name_handles(email.sender_name)


def categories(email, from_lecturer):
    """Spec 5.2 and 5.3: at most two categories, in ORDER."""
    subject = fold(email.subject)
    both = subject + "\n" + fold(email.text)
    found = set()
    if from_lecturer or email.sender_address.lower() == BLACKBOARD_SENDER:
        found.add("class")
    if SCHOOL_TASK.search(subject):
        found.add("school_task")
    if MONEY.search(subject):
        found.add("money")
    if EVENT.search(subject):
        found.add("event")
    if TRAINING.search(both):
        found.add("training_points")
    if subject.startswith("[ticket:") or ACCOUNT.search(subject):
        found.add("requests_account")
    if is_microsoft_notice(email):
        found.update(("system_notice", "class") if TEAMS_ADDED.search(subject) else ("system_notice",))
    if PROMOTION.search(both):
        found.add("promotion")
    return [name for name in ORDER if name in found][:MAX_CATEGORIES]


def course_of(email, context):
    """Spec 5.5: the one course a lecturer's email is about, or None."""
    for name, code in context.bb_courses:
        if code and email.subject.startswith(name + ": "):
            return code
    subject_and_text = email.subject + "\n" + email.text
    folded = fold(subject_and_text)
    named = {code for code, name, _ in context.courses
             if re.search(rf"\b{re.escape(code)}\b", subject_and_text, re.IGNORECASE)
             or (name and re.search(r"\b" + re.escape(fold(name)) + r"\b", folded))}
    if len(named) == 1:
        return named.pop()
    handle = sender_handle(email)
    taught = {code for code, _, lecturer in context.courses if lecturer and handle == lecturer_handle(lecturer)}
    return taught.pop() if len(taught) == 1 else None


def blackboard_title(email):
    """The announcement's own title in a Blackboard email: the subject after its first ": "."""
    if not blackboard_lecturer(email) or ": " not in email.subject:
        return None
    return email.subject.split(": ", 1)[1].strip()[:255] or None


def class_changes(email, code):
    posted = email.received_at.astimezone(timezone.utc).replace(tzinfo=None)
    found = dict.fromkeys(read_announcement(email.subject, email.text, posted))
    return [MailClassChange(course_code=code, kind=a.kind, day=a.day, start=a.start, end=a.end, room=a.room)
            for a in found][:MAX_CLASS_CHANGES]


def sessions_of(email, arrived):
    """The times the email's event or school task takes place (mailbox-events 3.2), found in every email so they
    are ready when the student moves one to Event. Only the text is read: a time in the subject is often a summary
    that repeats (or rounds) the text's. [] when the finder fails on it."""
    try:
        return [MailSession(day=s.day, start=s.start, end=s.end) for s in sessions_in(email.text, arrived)]
    except Exception as error:  # the message could quote the email
        log.warning("Couldn't read the times in an email (%s); it is uploaded without them",
                    error.__class__.__name__)
        return []


def register_by_of(email, arrived):
    """The email's registration deadline (mailbox-events addendum A.2), found in every email; None when there is
    none or the reader fails on it."""
    try:
        return register_by_in(email.subject + "\n" + email.text, arrived)
    except Exception as error:  # the message could quote the email
        log.warning("Couldn't read the registration deadline in an email (%s); it is uploaded without one",
                    error.__class__.__name__)
        return None


def sort_email(email, context):
    """The MailItem for one email. If the rules fail on it, it is uploaded unsorted (sorted=False)."""
    known = dict(key=email.key, entry_id=email.entry_id, thread_id=email.thread_id, received_at=email.received_at,
                 sender_name=(email.sender_name or "")[:255], sender_address=(email.sender_address or "").lower()[:255],
                 subject=(email.subject or "")[:500])
    try:
        lecturer = is_from_lecturer(email, context)
        arrived = (email.received_at.astimezone(timezone.utc) + VIETNAM_OFFSET).date()
        code = course_of(email, context) if lecturer else None
        return MailItem(
            **known,
            categories=categories(email, lecturer),
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            sessions=sessions_of(email, arrived),
            register_by=register_by_of(email, arrived),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
    except Exception as error:  # one email must never stop the others; the message could quote the email
        log.warning("Couldn't sort an email (%s); it is uploaded unsorted", error.__class__.__name__)
        return MailItem(**known, sorted=False)
