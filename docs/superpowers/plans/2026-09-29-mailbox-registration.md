# Check-in Times and Registration Deadlines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A check-in time joins its event's session instead of becoming a second one, and an event whose registration deadline has passed counts as Past unless the student joined it.

**Architecture:** The agent's session finder merges check-in times into the day's next session and a new pure `register_by_in` reads the registration deadline; each email's `register_by` travels in the upload (Python and Java) and is stored on `school_mail`. `Mailbox` takes the keys of the emails the student joined, makes a closed, unjoined event Past, and the row shows "Register by …" or "Registration closed".

**Tech Stack:** Python 3.12, pydantic, pytest; Java 17, Spring Boot 4.1.1, Thymeleaf, Spring Data JPA, Flyway, JUnit 5 + MockMvc, H2.

**Spec:** the "Addendum (2026-09-29): check-in times and registration deadlines" at the end of `docs/superpowers/specs/2026-09-28-mailbox-events-design.md` (approved 2026-09-29). Section numbers below (A.1 …) are the addendum's.

**Starting point:** the branch `outlook-mailbox` at `88f374a`. Tasks 1–6 of `docs/superpowers/plans/2026-09-29-mailbox-events.md` are done; its Task 7 (checkpoint) stopped at Step 4 when the student found the two wrong results this plan fixes. After this plan, that plan resumes at Task 7 Step 2 (a new sync, then the student's check), then Tasks 8 and 9.

**Tried first:** both tasks were built and run in a scratch worktree from `88f374a`; the code blocks are taken from it, and applying this plan to a clean copy of `88f374a` gives the same files (checked). With both, 437 Python and 488 Java tests pass.

## Global Constraints

- **Email text never leaves the laptop:** `register_by` is a date only (A.2). The upload format refuses unknown fields.
- The Global Constraints of `docs/superpowers/plans/2026-09-29-mailbox-events.md` apply unchanged (main working tree, Git Bash from the repository root, no new dependencies, Java 17, migration names, commit trailer, backslashes written with the editor).

## Review Focus

1. **A check-in joining the wrong session**, e.g. a morning and an afternoon session with the check-in before the afternoon one. Pinned by `test_a_check_in_joins_the_earliest_session_after_it_that_day` (Task 1).
2. **A deadline that isn't about registering** (a submission deadline, a survey's) closing an event. Pinned by `test_not_a_registration_deadline` (Task 1).
3. **A joined event disappearing into Past** after its registration closed. Pinned by `MailboxTest.anEventWhoseRegistrationClosedIsPastUnlessJoined` and `MailboxPageTest.anEventWhoseRegistrationClosedGoesToPastUnlessJoined` (Task 2).
4. **An off-by-one on the deadline day:** registration is open through its day. Pinned by `MailboxTest.registrationIsOpenThroughItsDeadline` (Task 2).
5. **A reminder that extends the deadline** being ignored. Pinned by `test_each_way_of_writing_a_registration_deadline` (two deadlines, the later wins, Task 1) and `MailboxTest.aThreadKeepsItsLatestDeadline` (Task 2).

## Decisions made while trying it out

- **`SchoolMail` gets `setRegisterBy`** instead of one more constructor argument, so the many tests that build mail rows stay as they are.
- **`Mailbox.build` keeps its four-argument form** (it means "joined nothing") next to the new one with the joined keys, so `MailSessionsTest` stays as it is.
- **The migration is `V20260929_1_5__mail_register_by.sql`**, dated the day it was written.
- **Task 8 of the mailbox-events plan pins `register_by` too:** its executor adds `"register_by": item.register_by and item.register_by.isoformat()` after `"sessions"` in `sample()` (the tool) and in `test_mail_samples.py`, as a ledgered ruling, so the samples of the student's Inbox pin the deadlines as well.

## File Structure

```
agent/sla_agent/class_changes.py        CHECK_IN, REGISTER; check-ins merged in sessions_in; register_by_in (Task 1)
agent/sla_agent/mail_rules.py           register_by_of; sort_email sets register_by (Task 1)
contract/sla_contract/schema.py         MailItem.register_by (Task 1); contract/samples/finish-outlook.json (R1)
web/src/main/resources/db/migration/V20260929_1_5__mail_register_by.sql   (Task 1)
web/src/main/java/.../school/sync/SyncContract.java, Ingest.java; school/model/SchoolMail.java   (Task 1)
web/src/main/java/.../school/mail/Mailbox.java; school/model/SchoolMailJoinedRepository.java;
    school/pages/MailboxController.java; templates/school/mailbox.html; static/css/style.css   (Task 2)
tests: agent/tests/test_sessions.py, test_mail_rules.py, contract/tests/test_contract.py (R1);
    web: SyncContractTest, IngestTest, SchoolTablesTest (R1); MailboxTest, MailboxPageTest (R2)
```

Test counts: today (`88f374a`) 415 Python and 481 Java tests pass. After Task 1: 437 Python, 482 Java; after Task 2: 488 Java.

---

### Task 1: Check-ins and the registration deadline, from the laptop to the database

**Files:**
- Create: `web/src/main/resources/db/migration/V20260929_1_5__mail_register_by.sql`
- Modify: `agent/sla_agent/class_changes.py`, `agent/sla_agent/mail_rules.py`, `contract/sla_contract/schema.py`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, `Ingest.java`, `school/model/SchoolMail.java`
- Test: `agent/tests/test_sessions.py`, `agent/tests/test_mail_rules.py`, `contract/samples/finish-outlook.json`, `contract/tests/test_contract.py`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`, `IngestTest.java`, `school/model/SchoolTablesTest.java`

**Interfaces:**
- Consumes: `sessions_in`, `DEADLINE`, `fold`, `_dates`, `SENTENCE_END`, `URL` (`class_changes.py`); `sessions_of` and `sort_email` (`mail_rules.py`); `MailItem` (both sides); `Ingest.saveOutlook`.
- Produces: `class_changes.CHECK_IN`, `REGISTER`, `register_by_in(text, from_day) -> date | None`; `mail_rules.register_by_of(email, arrived)`; `MailItem.register_by` (Python, `date | None`) and `MailItem.registerBy()` (Java, `LocalDate`, may be null); `SchoolMail.getRegisterBy()`, `setRegisterBy(LocalDate)`; column `school_mail.register_by`.

- [ ] **Step 1: Write the failing tests**

`agent/tests/test_mail_rules.py`: one change.

Replace

```python
    assert "Bí mật" not in caplog.text and "12345" not in caplog.text


def test_the_text_never_leaves_in_the_result():
    item = sort_email(email("Workshop", "UNIQUE-TEXT-7f3a only the laptop may read this"), CONTEXT)

```

with

```python
    assert "Bí mật" not in caplog.text and "12345" not in caplog.text


def test_the_registration_deadline_is_found_in_every_email():
    item = sort_email(email("Họp lớp", "Hạn đăng ký: 23h59 ngày 25/9/2026."), CONTEXT)

    assert (item.categories, item.register_by) == ([], date(2026, 9, 25))


def test_an_email_the_deadline_reader_fails_on_keeps_its_sorting_and_logs_no_text(monkeypatch, caplog):
    def broken(text, from_day):
        raise ValueError(text)

    monkeypatch.setattr(mail_rules, "register_by_in", broken)

    with caplog.at_level(logging.WARNING):
        item = sort_email(email("Workshop ngày 30/9", "Bí mật riêng tư 12345. Hạn đăng ký 25/9."), CONTEXT)

    assert (item.sorted, item.register_by, item.categories) == (True, None, ["event"])
    assert "Bí mật" not in caplog.text and "12345" not in caplog.text


def test_the_text_never_leaves_in_the_result():
    item = sort_email(email("Workshop", "UNIQUE-TEXT-7f3a only the laptop may read this"), CONTEXT)

```

`agent/tests/test_sessions.py`: 2 changes.

Change 1: replace

```python

import pytest

from sla_agent.class_changes import MAX_SESSIONS, Session, sessions_in

ARRIVED = date(2026, 9, 28)  # Mon, in Vietnam

```

with

```python

import pytest

from sla_agent.class_changes import MAX_SESSIONS, Session, register_by_in, sessions_in

ARRIVED = date(2026, 9, 28)  # Mon, in Vietnam

```

Change 2: replace

```python
    assert sessions_in("", ARRIVED) == []
    assert sessions_in(None, ARRIVED) == []

```

with

```python
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

```

`contract/samples/finish-outlook.json`: one change.

Replace

```json
          "sessions": [
            {"day": "2026-09-25", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-02", "start": "08:00:00"}
          ]
        },
        {
          "key": "8a8799a54ac207a586b7fc42e9fd68adb4e6c2ff5f8b6e0712d68df3acc157ad",
```

with

```json
          "sessions": [
            {"day": "2026-09-25", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-02", "start": "08:00:00"}
          ],
          "register_by": "2026-09-23"
        },
        {
          "key": "8a8799a54ac207a586b7fc42e9fd68adb4e6c2ff5f8b6e0712d68df3acc157ad",
```

`contract/tests/test_contract.py`: 2 changes.

Change 1: replace

```python
    assert [(s.day.isoformat(), s.start.isoformat(), s.end and s.end.isoformat()) for s in second.sessions] == [
        ("2026-09-25", "13:30:00", "16:30:00"), ("2026-10-02", "08:00:00", None)]
    assert first.sessions == []


@pytest.mark.parametrize(
```

with

```python
    assert [(s.day.isoformat(), s.start.isoformat(), s.end and s.end.isoformat()) for s in second.sessions] == [
        ("2026-09-25", "13:30:00", "16:30:00"), ("2026-10-02", "08:00:00", None)]
    assert first.sessions == []
    assert (second.register_by.isoformat(), first.register_by) == ("2026-09-23", None)


@pytest.mark.parametrize(
```

Change 2: replace

```python
        lambda p: p["emails"][1]["sessions"][0].update(end="13:30:00"),
        lambda p: p["emails"][1]["sessions"][0].update(start="25:00:00"),
        lambda p: p["emails"][1]["sessions"][1].pop("start"),
    ],
    ids=["text", "html", "three-categories", "repeated-category", "unknown-category", "lower-case-entry-id",
         "script-entry-id", "bad-key", "naive-time", "unknown-change", "too-many-dates", "too-many-emails",
         "loses-points", "eleven-sessions", "end-not-after-start", "bad-session-time", "session-without-start"],
)
def test_bad_outlook_data_is_rejected(change):
    data = outlook_payload()
```

with

```python
        lambda p: p["emails"][1]["sessions"][0].update(end="13:30:00"),
        lambda p: p["emails"][1]["sessions"][0].update(start="25:00:00"),
        lambda p: p["emails"][1]["sessions"][1].pop("start"),
        lambda p: p["emails"][1].update(register_by="22/9/2026"),
    ],
    ids=["text", "html", "three-categories", "repeated-category", "unknown-category", "lower-case-entry-id",
         "script-entry-id", "bad-key", "naive-time", "unknown-change", "too-many-dates", "too-many-emails",
         "loses-points", "eleven-sessions", "end-not-after-start", "bad-session-time", "session-without-start",
         "bad-register-by"],
)
def test_bad_outlook_data_is_rejected(change):
    data = outlook_payload()
```

`web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`: 2 changes.

Change 1: replace

```java
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 10, 2), java.time.LocalTime.of(8, 0), null));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 9, 29), java.time.LocalTime.of(13, 30),
                java.time.LocalTime.of(16, 30)));
        db.persist(mail);
        SchoolMail empty = new SchoolMail(userId, "b".repeat(64), "00AC", "T1", SEPT_28, "", "", "", List.of(), false,
                List.of(), false, null);
```

with

```java
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 10, 2), java.time.LocalTime.of(8, 0), null));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 9, 29), java.time.LocalTime.of(13, 30),
                java.time.LocalTime.of(16, 30)));
        mail.setRegisterBy(LocalDate.of(2026, 9, 25));
        db.persist(mail);
        SchoolMail empty = new SchoolMail(userId, "b".repeat(64), "00AC", "T1", SEPT_28, "", "", "", List.of(), false,
                List.of(), false, null);
```

Change 2: replace

```java
        SchoolMail again = reloaded(mail, mail.getId());

        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        assertThat(again.getSessions()).extracting(s -> s.getDay() + " " + s.getStart() + "-" + s.getEnd())
```

with

```java
        SchoolMail again = reloaded(mail, mail.getId());

        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getRegisterBy()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        assertThat(again.getSessions()).extracting(s -> s.getDay() + " " + s.getStart() + "-" + s.getEnd())
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`: one change.

Replace

```java
        assertThat(workshop.getCategories()).containsExactly("event", "training_points");
        assertThat(workshop.getDates()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(List.of(workshop.isFromLecturer(), workshop.isSorted())).containsExactly(false, true);
        assertThat(mailSessions.findOfUser(userId))
                .extracting(s -> s.getMail().getMailKey() + " " + s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly(workshop.getMailKey() + " 2026-09-25 13:30-16:30",
```

with

```java
        assertThat(workshop.getCategories()).containsExactly("event", "training_points");
        assertThat(workshop.getDates()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(List.of(workshop.isFromLecturer(), workshop.isSorted())).containsExactly(false, true);
        assertThat(workshop.getRegisterBy()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(saved.get(0).getRegisterBy()).isNull();
        assertThat(mailSessions.findOfUser(userId))
                .extracting(s -> s.getMail().getMailKey() + " " + s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly(workshop.getMailKey() + " 2026-09-25 13:30-16:30",
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`: 2 changes.

Change 1: replace

```java
        assertThat(emails.get(1).sessions()).extracting(s -> s.day() + " " + s.start() + "-" + s.end())
                .containsExactly("2026-09-25 13:30-16:30", "2026-10-02 08:00-null");
        assertThat(emails.get(0).sessions()).isEmpty();
    }

    static Stream<Arguments> badOutlookData() {
```

with

```java
        assertThat(emails.get(1).sessions()).extracting(s -> s.day() + " " + s.start() + "-" + s.end())
                .containsExactly("2026-09-25 13:30-16:30", "2026-10-02 08:00-null");
        assertThat(emails.get(0).sessions()).isEmpty();
        assertThat(emails.get(1).registerBy()).isEqualTo(java.time.LocalDate.of(2026, 9, 23));
        assertThat(emails.get(0).registerBy()).isNull();
    }

    static Stream<Arguments> badOutlookData() {
```

Change 2: replace

```java
                Arguments.of("bad-session-time", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1, "sessions", 0)
                        .put("start", "25:00:00")),
                Arguments.of("session-without-start", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1,
                        "sessions", 1).remove("start")));
    }

    @ParameterizedTest(name = "{0}")
```

with

```java
                Arguments.of("bad-session-time", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1, "sessions", 0)
                        .put("start", "25:00:00")),
                Arguments.of("session-without-start", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1,
                        "sessions", 1).remove("start")),
                Arguments.of("bad-register-by", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1)
                        .put("register_by", "22/9/2026")));
    }

    @ParameterizedTest(name = "{0}")
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest contract agent -q`
Expected: FAIL while collecting: `ImportError: cannot import name 'register_by_in' from 'sla_agent.class_changes'`.

Run: `(cd web && ./mvnw -B test -Dtest='SyncContractTest,IngestTest,SchoolTablesTest')`
Expected: compilation fails: `cannot find symbol` for `method getRegisterBy`, `method registerBy` and `method setRegisterBy`.

- [ ] **Step 3: The finder, the format and the column**

`web/src/main/resources/db/migration/V20260929_1_5__mail_register_by.sql` (new):

```sql
-- Mailbox: each email's registration deadline, as the laptop found it
-- (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, addendum A.2 and A.3).
ALTER TABLE school_mail ADD COLUMN register_by DATE NULL;
```

`agent/sla_agent/class_changes.py`: 5 changes.

Change 1: replace

```python
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it.

Sessions follow docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 3.2."""

import re
import unicodedata
```

with

```python
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it.

Sessions follow docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 3.2, and its addendum of
2026-09-29 (check-in times, the registration deadline)."""

import re
import unicodedata
```

Change 2: replace

```python


DEADLINE = re.compile(r"\b(?:" + "|".join(r"\s+".join(fold(w).split()) for w in DEADLINE_WORDS) + r")\b")


class Announced(NamedTuple):
```

with

```python


DEADLINE = re.compile(r"\b(?:" + "|".join(r"\s+".join(fold(w).split()) for w in DEADLINE_WORDS) + r")\b")
# On folded text: "check in", "check-in", "checkin", "điểm danh" mark a check-in time; "đăng ký", "register",
# "registration", "sign up" make a deadline sentence a registration deadline (addendum A.1 and A.2).
CHECK_IN = re.compile(r"\b(?:check\s*-?\s*in|diem\s+danh)\b")
REGISTER = re.compile(r"\b(?:dang\s+ky|register|registration|sign\s*-?\s*up)\b")


class Announced(NamedTuple):
```

Change 3: replace

```python
def sessions_in(text, from_day):
    """The times an event takes place (mailbox-events 3.2): each sentence's times go with its dates, or with the
    dates of the nearest sentence above that has some. Several dates and one time, or one date and several
    times, give one session each; equal numbers pair in order. Sentences about a deadline are skipped. Sessions
    before `from_day` are dropped; a repeated day and start is kept once, with the first end found; at most
    MAX_SESSIONS, in time order."""
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    above, found = [], []
    for sentence in SENTENCE_END.split(text):
        if DEADLINE.search(fold(sentence)):
            continue
        dates = _dates(sentence, from_day, keep_past=True)
        days = [day for _, _, day in dates]
```

with

```python
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
```

Change 4: replace

```python
        if not times or not above:
            continue
        pairs = zip(above, times) if len(above) == len(times) else [(d, t) for d in above for t in times]
        found.extend(Session(day, start, end) for day, (start, end) in pairs)
    kept = {}
    for session in found:
        if session.day < from_day:
```

with

```python
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
```

Change 5: replace

```python
            kept[key] = session if key not in kept else kept[key]._replace(end=session.end)
    return sorted(kept.values())[:MAX_SESSIONS]

```

with

```python
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

```

`agent/sla_agent/mail_rules.py`: 3 changes.

Change 1: replace

```python

from sla_contract.schema import MailClassChange, MailItem, MailSession

from sla_agent.class_changes import dates_in, fold, read_announcement, sessions_in

log = logging.getLogger(__name__)

```

with

```python

from sla_contract.schema import MailClassChange, MailItem, MailSession

from sla_agent.class_changes import dates_in, fold, read_announcement, register_by_in, sessions_in

log = logging.getLogger(__name__)

```

Change 2: replace

```python
        return []


def sort_email(email, context):
    """The MailItem for one email. If the rules fail on it, it is uploaded unsorted (sorted=False)."""
    known = dict(key=email.key, entry_id=email.entry_id, thread_id=email.thread_id, received_at=email.received_at,
```

with

```python
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
```

Change 3: replace

```python
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            sessions=sessions_of(email, arrived),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

with

```python
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            sessions=sessions_of(email, arrived),
            register_by=register_by_of(email, arrived),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

`contract/sla_contract/schema.py`: one change.

Replace

```python
    from_lecturer: bool = False
    dates: Annotated[list[date], Field(max_length=30)] = []
    sessions: Annotated[list[MailSession], Field(max_length=10)] = []  # found in any email; shown for events
    sorted: bool = True  # False: the sorting rules failed on this email
    blackboard_title: Annotated[str, Field(max_length=255)] | None = None
    class_changes: Annotated[list[MailClassChange], Field(max_length=10)] = []
```

with

```python
    from_lecturer: bool = False
    dates: Annotated[list[date], Field(max_length=30)] = []
    sessions: Annotated[list[MailSession], Field(max_length=10)] = []  # found in any email; shown for events
    register_by: date | None = None  # the registration deadline, found in any email (Vietnam date)
    sorted: bool = True  # False: the sorting rules failed on this email
    blackboard_title: Annotated[str, Field(max_length=255)] | None = None
    class_changes: Annotated[list[MailClassChange], Field(max_length=10)] = []
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMail.java`: 2 changes.

Change 1: replace

```java

    @Column(name = "blackboard_title", length = 255)
    private String blackboardTitle;

    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolMailChange> changes = new ArrayList<>();
```

with

```java

    @Column(name = "blackboard_title", length = 255)
    private String blackboardTitle;

    @Column(name = "register_by")
    private LocalDate registerBy; // the registration deadline (Vietnam date), or null

    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolMailChange> changes = new ArrayList<>();
```

Change 2: replace

```java
        return blackboardTitle;
    }

    public List<SchoolMailChange> getChanges() {
        return changes;
    }
```

with

```java
        return blackboardTitle;
    }

    public LocalDate getRegisterBy() {
        return registerBy;
    }

    public void setRegisterBy(LocalDate registerBy) {
        this.registerBy = registerBy;
    }

    public List<SchoolMailChange> getChanges() {
        return changes;
    }
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`: one change.

Replace

```java
            SchoolMail mail = new SchoolMail(userId, item.key(), item.entryId(), item.threadId(),
                    toUtc(item.receivedAt()), item.senderName(), item.senderAddress(), item.subject(), item.categories(),
                    item.fromLecturer(), item.dates(), item.sorted(), item.blackboardTitle());
            for (MailClassChange c : item.classChanges()) {
                mail.getChanges().add(new SchoolMailChange(mail, c.courseCode(), c.kind(), c.day(), c.start(), c.end(),
                        c.room()));
```

with

```java
            SchoolMail mail = new SchoolMail(userId, item.key(), item.entryId(), item.threadId(),
                    toUtc(item.receivedAt()), item.senderName(), item.senderAddress(), item.subject(), item.categories(),
                    item.fromLecturer(), item.dates(), item.sorted(), item.blackboardTitle());
            mail.setRegisterBy(item.registerBy());
            for (MailClassChange c : item.classChanges()) {
                mail.getChanges().add(new SchoolMailChange(mail, c.courseCode(), c.kind(), c.day(), c.start(), c.end(),
                        c.room()));
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`: one change.

Replace

```java
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            @Size(max = 10) List<@Valid MailSession> sessions,
            Boolean sorted,
            @Chars(max = 255) String blackboardTitle,
            @Size(max = 10) List<@Valid MailClassChange> classChanges) {
```

with

```java
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            @Size(max = 10) List<@Valid MailSession> sessions,
            LocalDate registerBy,
            Boolean sorted,
            @Chars(max = 255) String blackboardTitle,
            @Size(max = 10) List<@Valid MailClassChange> classChanges) {
```

- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (437 tests). Run: `(cd web && ./mvnw -B test)` → `Tests run: 482, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add agent contract web/src
git commit -m "feat(agent): a check-in time joins its event, and each email's registration deadline reaches the site

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Closed registration is Past unless joined, and the row says until when

**Files:**
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`, `school/model/SchoolMailJoinedRepository.java`, `school/pages/MailboxController.java`, `web/src/main/resources/templates/school/mailbox.html`, `web/src/main/resources/static/css/style.css`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`, `school/pages/MailboxPageTest.java`

**Interfaces:**
- Consumes: `SchoolMail.getRegisterBy()`, `setRegisterBy` (Task 1); `SchoolMailJoined` (existing).
- Produces: `Mailbox.Card` gains `registerBy` (`LocalDate`, the latest of its emails, may be null) and `closed` (the deadline is before today) as its last two components; `Mailbox.build(mails, choices, sessions, joinedKeys, now)` next to the four-argument form (= no joined keys); `SchoolMailJoinedRepository.mailKeysOf(userId) -> Set<String>`; the row's `span.tag.tag-register` ("Register by Tue 22/09") and "Registration closed" tag.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`: 4 changes.

Change 1: replace

```java
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

```

with

```java
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

```

Change 2: replace

```java
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
```

with

```java
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        LocalDate registerBy;
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
```

Change 3: replace

```java
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
        }

        SchoolMail row() {
            return new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender", sender,
                    subject, categories, lecturer, dates, sorted, null);
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, sessions, NOW_IN_VIETNAM);
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
```

with

```java
            return this;
        }

        Mail registerBy(int daysFromToday) {
            this.registerBy = TODAY.plusDays(daysFromToday);
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
        }

        SchoolMail row() {
            SchoolMail row = new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender",
                    sender, subject, categories, lecturer, dates, sorted, null);
            row.setRegisterBy(registerBy);
            return row;
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            Set<String> joinedKeys, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, sessions, joinedKeys, NOW_IN_VIETNAM);
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions, Mail... mails) {
        return build(choices, sessions, Set.of(), mails);
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
```

Change 4: replace

```java
    }

    @Test
    void everyCategoryHasAShortLabel() {
        assertThat(Mailbox.LABELS.keySet()).containsExactlyElementsOf(Mailbox.CATEGORIES.keySet());
        assertThat(Mailbox.LABELS.get("training_points")).isEqualTo("★ Points");
```

with

```java
    }

    @Test
    void anEventWhoseRegistrationClosedIsPastUnlessJoined() {
        Map<String, List<Session>> sessions = Map.of("closing", List.of(session(2, "09:45", null)));
        Mail closing = new Mail("closing", 1, "event", "training_points").on(2).registerBy(-6);

        View notJoined = build(Map.of(), sessions, closing);
        View joined = build(Map.of(), sessions, Set.of("closing"), closing);

        assertThat(keys(box(notJoined, "events").past())).containsExactly("closing");
        assertThat(keys(box(joined, "events").cards())).containsExactly("closing");
        assertThat(List.of(notJoined.card("closing").closed(), notJoined.card("closing").registerBy()))
                .containsExactly(true, TODAY.minusDays(6));
    }

    @Test
    void registrationIsOpenThroughItsDeadline() {
        Card card = build(new Mail("talk", 1, "event").on(3).registerBy(0)).card("talk");

        assertThat(List.of(card.closed(), card.past())).containsExactly(false, false);
    }

    @Test
    void aThreadKeepsItsLatestDeadline() {
        Card card = build(new Mail("reminder", 1, "event").thread("T").on(4),
                new Mail("first", 30, "event").thread("T").on(4).registerBy(-1),
                new Mail("extended", 10, "event").thread("T").registerBy(2)).card("reminder");

        assertThat(List.of(card.registerBy(), card.closed(), card.past())).containsExactly(TODAY.plusDays(2), false,
                false);
    }

    @Test
    void onlyEventsAndSchoolTasksCloseWithTheirRegistration() {
        View view = build(new Mail("invoice", 1, "money").registerBy(-3));

        assertThat(keys(box(view, "money").cards())).containsExactly("invoice");
    }

    @Test
    void everyCategoryHasAShortLabel() {
        assertThat(Mailbox.LABELS.keySet()).containsExactlyElementsOf(Mailbox.CATEGORIES.keySet());
        assertThat(Mailbox.LABELS.get("training_points")).isEqualTo("★ Points");
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java`: 2 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;

```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;

```

Change 2: replace

```java
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    @Test
    void anUnsortedEmailMovedByTheStudentIsNoLongerMarkedNotSorted() throws Exception {
        inbox(an);
```

with

```java
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    /** An event on `day` whose registration closes on `registerBy`. */
    void event(AppUser who, String key, String subject, LocalDate day, LocalDate registerBy) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), null,
                NOW.minusHours(20), "P.CTSV [OSS]", "oss@hcmiu.edu.vn", subject, List.of("event", "training_points"),
                false, List.of(day), true, null);
        mail.setRegisterBy(registerBy);
        db.persist(mail);
        db.flush();
    }

    @Test
    void theRowSaysUntilWhenRegistrationIsOpen() throws Exception {
        inbox(an);
        event(an, "e".repeat(64), "Lễ Bế mạc HTSV", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 29));

        assertThat(box(page(), "events")).contains("<span class=\"tag tag-register\">Register by Tue 29/09</span>");
    }

    @Test
    void anEventWhoseRegistrationClosedGoesToPastUnlessJoined() throws Exception {
        inbox(an);
        String closing = "e".repeat(64);
        event(an, closing, "Lễ Bế mạc HTSV", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 22));

        String events = box(page(), "events");
        assertThat(events.substring(0, events.indexOf("<details"))).doesNotContain("Bế mạc");
        assertThat(events.substring(events.indexOf("<details"))).contains("Lễ Bế mạc HTSV")
                .contains(">Registration closed</span>");

        db.persist(new SchoolMailJoined(an.id(), closing, LocalDate.of(2026, 9, 30), java.time.LocalTime.of(9, 45),
                null, "Lễ Bế mạc HTSV", null, true, false, NOW));
        db.flush();
        events = box(page(), "events");
        assertThat(events.substring(0, events.indexOf("<details") < 0 ? events.length() : events.indexOf("<details")))
                .contains("Lễ Bế mạc HTSV").doesNotContain("Registration closed");
    }

    @Test
    void anUnsortedEmailMovedByTheStudentIsNoLongerMarkedNotSorted() throws Exception {
        inbox(an);
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest')`
Expected: compilation fails: `cannot find symbol` for `method closed` and `method registerBy`.

- [ ] **Step 3: The Past rule and the tags**

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`: 5 changes.

Change 1: replace

```java
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

```

with

```java
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

```

Change 2: replace

```java
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. sessions: an event or school task's sessions that
     * haven't ended (none for other cards). nextDate: the day of the first of them, or else the earliest date
     * from today on. past: every session has ended, or (without sessions) every date is over. suggested: the
     * student moved it to Event or School task and the rules gave it neither, so its sessions are only suggestions.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, List<Session> sessions, LocalDate nextDate, boolean past, boolean sorted,
            boolean opened, boolean done, int messages, int copies, boolean suggested) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
```

with

```java
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. sessions: an event or school task's sessions that
     * haven't ended (none for other cards). nextDate: the day of the first of them, or else the earliest date
     * from today on. past: every session has ended, or (without sessions) every date is over, or (an event or
     * school task) its registration closed and the student joined none of its sessions. suggested: the student
     * moved it to Event or School task and the rules gave it neither, so its sessions are only suggestions.
     * registerBy: the latest registration deadline of its emails, or null; closed: that day is before today.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, List<Session> sessions, LocalDate nextDate, boolean past, boolean sorted,
            boolean opened, boolean done, int messages, int copies, boolean suggested, LocalDate registerBy,
            boolean closed) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
```

Change 3: replace

```java
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            LocalDateTime now) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
```

with

```java
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            Set<String> joinedKeys, LocalDateTime now) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
```

Change 4: replace

```java
        List<Session> all = eventLike(categories) ? sessionsOf(mails, sessions) : List.of();
        List<Session> ahead = all.stream().filter(s -> s.endAt().isAfter(now)).toList();
        LocalDate next = all.isEmpty() ? dates.ceiling(now.toLocalDate()) : ahead.isEmpty() ? null : ahead.get(0).day();
        boolean past = all.isEmpty() ? !dates.isEmpty() && next == null : ahead.isEmpty();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies(), eventLike(categories) && !eventLike(newest.getCategories()));
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

with

```java
        List<Session> all = eventLike(categories) ? sessionsOf(mails, sessions) : List.of();
        List<Session> ahead = all.stream().filter(s -> s.endAt().isAfter(now)).toList();
        LocalDate next = all.isEmpty() ? dates.ceiling(now.toLocalDate()) : ahead.isEmpty() ? null : ahead.get(0).day();
        LocalDate registerBy = mails.stream().map(SchoolMail::getRegisterBy).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean closed = registerBy != null && registerBy.isBefore(now.toLocalDate());
        boolean joined = mails.stream().anyMatch(m -> joinedKeys.contains(m.getMailKey()));
        boolean past = (all.isEmpty() ? !dates.isEmpty() && next == null : ahead.isEmpty())
                || (eventLike(categories) && closed && !joined);
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies(), eventLike(categories) && !eventLike(newest.getCategories()), registerBy,
                closed);
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

Change 5: replace

```java
        return "other";
    }

    /** The whole tab. mails: newest first; choices and sessions by mail key; now in Vietnam (wall clock). */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices,
            Map<String, List<Session>> sessions, LocalDateTime now) {
        Map<String, List<Card>> byBox = new LinkedHashMap<>();
        for (String box : List.of("lecturers", "tasks", "money", "events", "other")) {
            byBox.put(box, new ArrayList<>());
        }
        List<Card> done = new ArrayList<>();
        for (Group group : groups(mails)) {
            Card card = card(group, choices, sessions, now);
            (card.done() ? done : byBox.get(boxOf(card))).add(card);
        }
        done.sort(NEWEST_FIRST);
```

with

```java
        return "other";
    }

    /** The whole tab, for a student who joined no events. */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices,
            Map<String, List<Session>> sessions, LocalDateTime now) {
        return build(mails, choices, sessions, Set.of(), now);
    }

    /**
     * The whole tab. mails: newest first; choices and sessions by mail key; joinedKeys: the emails with a joined
     * session; now in Vietnam (wall clock).
     */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices,
            Map<String, List<Session>> sessions, Set<String> joinedKeys, LocalDateTime now) {
        Map<String, List<Card>> byBox = new LinkedHashMap<>();
        for (String box : List.of("lecturers", "tasks", "money", "events", "other")) {
            byBox.put(box, new ArrayList<>());
        }
        List<Card> done = new ArrayList<>();
        for (Group group : groups(mails)) {
            Card card = card(group, choices, sessions, joinedKeys, now);
            (card.done() ? done : byBox.get(boxOf(card))).add(card);
        }
        done.sort(NEWEST_FIRST);
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailJoinedRepository.java`: one change.

Replace

```java
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMailJoinedRepository extends JpaRepository<SchoolMailJoined, Integer> {

    /** A user's joined sessions on the days [from, to], in time order. */
    List<SchoolMailJoined> findByUserIdAndDayBetweenOrderByDayAscStartAsc(Integer userId, LocalDate from, LocalDate to);

    /** A user's joined sessions of these emails (one card's keys), in time order. */
    List<SchoolMailJoined> findByUserIdAndMailKeyInOrderByDayAscStartAsc(Integer userId, Collection<String> mailKeys);
}
```

with

```java
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailJoinedRepository extends JpaRepository<SchoolMailJoined, Integer> {

    /** A user's joined sessions on the days [from, to], in time order. */
    List<SchoolMailJoined> findByUserIdAndDayBetweenOrderByDayAscStartAsc(Integer userId, LocalDate from, LocalDate to);

    /** The emails a user joined a session of. */
    @Query("select distinct j.mailKey from SchoolMailJoined j where j.userId = :userId")
    Set<String> mailKeysOf(Integer userId);

    /** A user's joined sessions of these emails (one card's keys), in time order. */
    List<SchoolMailJoined> findByUserIdAndMailKeyInOrderByDayAscStartAsc(Integer userId, Collection<String> mailKeys);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java`: one change.

Replace

```java
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                nowInVietnam());
    }

    /** The user's card whose newest email has this key, else 404. */
```

with

```java
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                joined.mailKeysOf(userId), nowInVietnam());
    }

    /** The user's card whose newest email has this key, else 404. */
```

`web/src/main/resources/static/css/style.css`: one change.

Replace

```css
.mail-row.is-opened .mail-subject { color: var(--muted); }
.mail-extra { display: flex; gap: 4px; white-space: nowrap; }
.tag { padding: 0 6px; font-size: 0.75rem; color: var(--muted); border: 1px solid var(--border); border-radius: 999px; }
.mail-when { color: var(--muted); font-size: 0.9rem; white-space: nowrap; text-align: right;
  font-variant-numeric: tabular-nums; }
.mail-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; white-space: nowrap;
```

with

```css
.mail-row.is-opened .mail-subject { color: var(--muted); }
.mail-extra { display: flex; gap: 4px; white-space: nowrap; }
.tag { padding: 0 6px; font-size: 0.75rem; color: var(--muted); border: 1px solid var(--border); border-radius: 999px; }
.tag-register { color: #7a5200; border-color: #e0b64a; }
.mail-when { color: var(--muted); font-size: 0.9rem; white-space: nowrap; text-align: right;
  font-variant-numeric: tabular-nums; }
.mail-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; white-space: nowrap;
```

`web/src/main/resources/templates/school/mailbox.html`: one change.

Replace

```html
    <span class="tag" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag" th:if="${card.copies <= 1 and card.messages > 1}" th:text="|${card.messages} messages|">3
      messages</span>
  </span>
  <span class="mail-when" th:text="${showNext and card.nextDate != null
      ? 'Next: ' + @schoolFormat.dayLabel(card.nextDate) : @schoolFormat.when(card.receivedAt)}">Next: Tue 29/09</span>
```

with

```html
    <span class="tag" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag" th:if="${card.copies <= 1 and card.messages > 1}" th:text="|${card.messages} messages|">3
      messages</span>
    <span class="tag tag-register" th:if="${card.eventLike and card.registerBy != null and !card.closed and !card.past}"
          th:text="|Register by ${@schoolFormat.dayLabel(card.registerBy)}|">Register by Tue 22/09</span>
    <span class="tag" th:if="${card.eventLike and card.closed and card.past}">Registration closed</span>
  </span>
  <span class="mail-when" th:text="${showNext and card.nextDate != null
      ? 'Next: ' + @schoolFormat.dayLabel(card.nextDate) : @schoolFormat.when(card.receivedAt)}">Next: Tue 29/09</span>
```

- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest')` → `Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 488, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat(web): an event whose registration closed is Past unless joined; the row says until when to register

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
