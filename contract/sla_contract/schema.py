"""The data the laptop agent sends to the web app after a sync.

Both sides import this file, so they always agree on the format. Unknown
fields are rejected: only what is listed here can leave the laptop.

The Java website reads the same format with
web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java: change both
together. contract/samples/ holds example uploads that both test suites check.
"""

from datetime import date, time
from typing import Annotated, Generic, Literal, TypeVar, get_args

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = 1

ErrorCode = Literal[
    "bad_credentials",  # EduSoft rejected the student ID or password
    "session_expired",  # EduSoft logged us out mid-sync, and one re-login did not help
    "network",  # EduSoft could not be reached, timed out, or was under maintenance
    "edusoft_changed",  # a page no longer looks the way the parser expects
    "source_changed",  # Blackboard's answers are in an unexpected format
    "extra_verification",  # EduSoft asked for a CAPTCHA, one-time code or Microsoft sign-in
    "outlook_not_set_up",  # classic Outlook is missing, has no account, or the chosen account is gone
    "outlook_blocked",  # Outlook refused the read or didn't answer in time (e.g. a security prompt)
    "unknown",
]
Trigger = Literal["scheduled", "manual", "import"]

Code = Annotated[str, Field(min_length=1, max_length=20)]
Name = Annotated[str, Field(min_length=1, max_length=255)]
Room = Annotated[str, Field(max_length=50)]
Message = Annotated[str, Field(min_length=1, max_length=500)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ClassMeeting(_Strict):
    start_at: AwareDatetime
    end_at: AwareDatetime
    room: Room | None = None

    @model_validator(mode="after")
    def _ends_after_it_starts(self):
        if self.end_at <= self.start_at:
            raise ValueError("end_at must be after start_at")
        return self


class Course(_Strict):
    course_code: Code
    course_name: Name
    group: Annotated[str, Field(max_length=20)] | None = None
    credits: Annotated[float, Field(ge=0, le=50)] | None = None
    lecturer: Annotated[str, Field(max_length=255)] | None = None
    meetings: Annotated[list[ClassMeeting], Field(max_length=200)] = []


class Timetable(_Strict):
    term_code: Code
    term_name: Annotated[str, Field(max_length=100)] | None = None
    courses: Annotated[list[Course], Field(max_length=40)]


class Exam(_Strict):
    course_code: Code
    course_name: Name
    exam_type: Literal["midterm", "final", "other"]
    start_at: AwareDatetime
    duration_min: Annotated[int, Field(ge=1, le=600)] | None = None
    room: Room | None = None
    notes: Annotated[str, Field(max_length=500)] | None = None


class Exams(_Strict):
    term_code: Code
    exams: Annotated[list[Exam], Field(max_length=60)]


class TuitionItem(_Strict):
    description: Name
    amount: int  # VND


class Tuition(_Strict):
    term_code: Code
    amount_due: Annotated[int, Field(ge=0)]  # VND
    amount_paid: Annotated[int, Field(ge=0)]  # VND
    balance: int  # VND; negative means overpaid
    due_date: date | None = None
    status_text: Annotated[str, Field(max_length=255)] | None = None
    items: Annotated[list[TuitionItem], Field(max_length=60)] = []


BillStatus = Literal["unpaid", "paid", "paying", "partly_paid"]


class TuitionBill(_Strict):
    """One IUPay bill. Amounts are VND; due_date and paid_on are Vietnam dates."""

    bill_no: Annotated[str, Field(min_length=1, max_length=40)]
    term_code: Code
    term_name: Name | None = None
    description: Annotated[str, Field(min_length=1, max_length=1000)]
    fee_type: Name | None = None
    amount: Annotated[int, Field(ge=0)]
    discount: Annotated[int, Field(ge=0)] = 0
    fee: Annotated[int, Field(ge=0)] = 0  # IUPay's transaction fee
    status: BillStatus
    due_date: date | None = None  # bills not paid yet
    paid_on: date | None = None  # paid bills
    channel: Annotated[str, Field(max_length=100)] | None = None  # paid bills: how they were paid


class Iupay(_Strict):
    """Every bill IUPay lists for the student, paid or not."""

    bills: Annotated[list[TuitionBill], Field(max_length=500)] = []

    @model_validator(mode="after")
    def _each_bill_once(self):
        numbers = [bill.bill_no for bill in self.bills]
        if len(set(numbers)) != len(numbers):
            raise ValueError("bill_no must differ")
        return self


BbId = Annotated[str, Field(min_length=1, max_length=64)]
BbUrl = Annotated[str, Field(max_length=500, pattern=r"^https://blackboard\.hcmiu\.edu\.vn/")]


class BbAnnouncement(_Strict):
    bb_id: BbId
    title: Name
    text: Annotated[str, Field(max_length=5000)] = ""
    posted_at: AwareDatetime | None = None
    url: BbUrl


class BbAssignment(_Strict):
    bb_id: BbId
    name: Name
    due_at: AwareDatetime | None = None
    points_possible: Annotated[float, Field(ge=0)] | None = None
    score: float | None = None
    grade_text: Annotated[str, Field(max_length=50)] | None = None
    status: Literal["not_graded", "needs_grading", "graded", "exempt"]
    feedback: Annotated[str, Field(max_length=1000)] | None = None
    url: BbUrl


class BbMaterial(_Strict):
    bb_id: BbId
    title: Name
    kind: Literal["file", "folder", "link", "document", "other"]
    path: Annotated[str, Field(max_length=500)] = ""
    created_at: AwareDatetime | None = None
    url: BbUrl


class BbCourse(_Strict):
    bb_id: BbId
    course_code: Code | None = None
    name: Name
    url: BbUrl
    announcements: Annotated[list[BbAnnouncement], Field(max_length=300)] = []
    assignments: Annotated[list[BbAssignment], Field(max_length=300)] = []
    materials: Annotated[list[BbMaterial], Field(max_length=1000)] = []


class Blackboard(_Strict):
    courses: Annotated[list[BbCourse], Field(max_length=40)]


MailCategory = Literal["class", "event", "training_points", "school_task", "money", "requests_account",
                       "system_notice", "promotion"]
MAIL_CATEGORIES = get_args(MailCategory)


class MailClassChange(_Strict):
    """A class change a lecturer's email announces. day and times are Vietnam time."""

    course_code: Code
    kind: Literal["online", "cancelled", "makeup"]
    day: date
    start: time | None = None  # make-up classes only
    end: time | None = None
    room: Room | None = None


class MailSession(_Strict):
    """One time an event or school task takes place, as the laptop found it in the email. Vietnam time."""

    day: date
    start: time
    end: time | None = None

    @model_validator(mode="after")
    def _end_after_start(self):
        if self.end is not None and self.end <= self.start:
            raise ValueError("end must be after start")
        return self


class MailItem(_Strict):
    """What the website may know about one email: never its text."""

    key: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]  # SHA-256 of the internet message ID
    entry_id: Annotated[str, Field(pattern=r"^[0-9A-F]{2,512}$")]  # Outlook's ID: opens it on the laptop
    thread_id: Annotated[str, Field(max_length=64)] | None = None
    received_at: AwareDatetime
    sender_name: Annotated[str, Field(max_length=255)] = ""
    sender_address: Annotated[str, Field(max_length=255)] = ""
    subject: Annotated[str, Field(max_length=500)] = ""
    categories: Annotated[list[MailCategory], Field(max_length=2)] = []
    from_lecturer: bool = False
    dates: Annotated[list[date], Field(max_length=30)] = []
    sessions: Annotated[list[MailSession], Field(max_length=10)] = []  # found in any email; shown for events
    register_by: date | None = None  # the registration deadline, found in any email (Vietnam date)
    sorted: bool = True  # False: the sorting rules failed on this email
    blackboard_title: Annotated[str, Field(max_length=255)] | None = None
    class_changes: Annotated[list[MailClassChange], Field(max_length=10)] = []

    @model_validator(mode="after")
    def _categories_differ(self):
        if len(set(self.categories)) != len(self.categories):
            raise ValueError("categories must differ")
        return self


class Outlook(_Strict):
    since: date
    connected: bool  # False: Outlook was offline, so the newest mail may be missing
    emails: Annotated[list[MailItem], Field(max_length=2000)]


T = TypeVar("T")


class SectionOk(_Strict, Generic[T]):
    status: Literal["ok"]
    data: T


class SectionFailed(_Strict):
    status: Literal["failed"]
    error_code: ErrorCode
    error_message: Message


TimetableResult = Annotated[SectionOk[Timetable] | SectionFailed, Field(discriminator="status")]
ExamsResult = Annotated[SectionOk[Exams] | SectionFailed, Field(discriminator="status")]
TuitionResult = Annotated[SectionOk[Tuition] | SectionFailed, Field(discriminator="status")]
IupayResult = Annotated[SectionOk[Iupay] | SectionFailed, Field(discriminator="status")]
BlackboardResult = Annotated[SectionOk[Blackboard] | SectionFailed, Field(discriminator="status")]
OutlookResult = Annotated[SectionOk[Outlook] | SectionFailed, Field(discriminator="status")]

EDUSOFT_SECTIONS = ("timetable", "exams")
SECTION_NAMES = ("timetable", "exams", "iupay", "blackboard", "outlook")


class FinishRun(_Strict):
    """Result of one sync: either a whole-run error, or a result per part."""

    schema_version: Literal[1] = SCHEMA_VERSION
    error_code: ErrorCode | None = None
    error_message: Message | None = None
    timetable: TimetableResult | None = None
    exams: ExamsResult | None = None
    tuition: TuitionResult | None = None  # sent by agents from before IUPay: accepted, never counted or saved
    iupay: IupayResult | None = None
    blackboard: BlackboardResult | None = None
    outlook: OutlookResult | None = None

    @model_validator(mode="after")
    def _error_or_sections(self):
        if self.error_code is not None:
            if self.error_message is None:
                raise ValueError("error_message is required with error_code")
            if self.sections() or self.tuition is not None:
                raise ValueError("a whole-run error cannot carry section results")
        elif not self.sections():
            raise ValueError("send either error_code or at least one section")
        return self

    def sections(self):
        """The parts that were sent, by name."""
        return {name: getattr(self, name) for name in SECTION_NAMES if getattr(self, name) is not None}

    def overall_status(self):
        if self.error_code is not None:
            return "failed"
        statuses = {result.status for result in self.sections().values()}
        if statuses == {"ok"}:
            return "success"
        if statuses == {"failed"}:
            return "failed"
        return "partial"


class StartRun(_Strict):
    trigger: Trigger


class CheckResult(BaseModel):
    due: bool
    reason: str
    interval_hours: int


class StartResult(BaseModel):
    run_id: int


class FinishResult(BaseModel):
    status: Literal["success", "partial", "failed"]
