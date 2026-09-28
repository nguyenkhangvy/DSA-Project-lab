# Compact Mailbox, auto-Done and Joinable Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mailbox shows one row per email with its category first and a bigger ✓ Done; opening an email marks it Done (a setting turns this off) unless it is an event or school task still ahead; "lose points" is gone; the laptop agent finds the times each event takes place, the site marks each time Conflict / No conflict against the timetable, and the sessions the student joins appear in the Timetable.

**Architecture:** The upload format gains `sessions` per email (Python and its Java twin) and loses `loses_points`; the agent's date reader gains a pure session finder, run on every email. The site stores sessions with the mail (replaced at each sync) and the student's joined sessions apart (kept), builds the rows with the pure `Mailbox`, marks conflicts with the pure `Conflicts` fed by `MailSessions` (the Timetable's own `Schedule` items plus the student's other joined events), records opening through a small `mailbox.js`, and adds joined sessions to `Schedule` as green `event` items. Three small migrations. The plan ends with the Outlook plan's paused checkpoint (a real sync, now with the student checking the new page), its anonymized Inbox samples (now pinning sessions) and the documentation.

**Tech Stack:** Python 3.12, pydantic, pywin32 312 (Windows only), pytest (agent and shared format); Java 17, Spring Boot 4.1.1 (Spring MVC, Thymeleaf, Spring Data JPA / Hibernate 7, Flyway), JUnit 5 + MockMvc, H2 for tests; Playwright + Edge for the browser check.

**Spec:** `docs/superpowers/specs/2026-09-28-mailbox-events-design.md` (approved 2026-09-29). It builds on `docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md`. Section numbers below (§3.2, §4.5 …) are the mailbox-events spec's; "Outlook §" points into the other one.

**Starting point:** the branch `outlook-mailbox` at `6363848`. Tasks 1–9 of `docs/superpowers/plans/2026-09-28-outlook-mailbox.md` are done and committed; its Task 10 (checkpoint) stopped at Step 5 when the student asked for this design; its Tasks 11 and 12 are replaced by Tasks 8 and 9 here. On 2026-09-28 the site on port 5000 ran on the student's database with migration `V20260928_1_1` applied, and one real sync had uploaded 56 emails.

**Tried first:** every change below was built and run in a scratch worktree from `6363848` before this plan was written; the code blocks are taken from it, and applying this plan's steps to a clean copy of `6363848` gives the same files (checked). With all of it, 481 Java tests pass on H2 and on a throwaway MySQL 8.4 server (migrations up to `v20260928.1.4`), and 418 Python tests pass before Task 8's samples. A browser check in Edge covered Mailbox, Join…, Overview and the Timetable at 1400×1000 and 390×844 with made-up mail uploaded through the real sync API: no console errors, no failed requests, no page wider than the screen, light background, black text.

## Global Constraints

- **Email text never leaves the laptop:** sessions are days and times only; the place of an event is typed by the student on the site, never read from the email (§1, §5). The upload format refuses unknown fields, `loses_points` included.
- **Read only:** nothing here changes the real mailbox. Opening, Done, Move to…, Join… and the setting only change the app.
- Every query is filtered by the logged-in user; another user's card is 404. Every form and the `opened` request post the CSRF token.
- `received_at` is stored in UTC. Sessions and joined sessions are Vietnam wall-clock days and times, as the email wrote them; `Schedule`'s UTC items are converted with `VietnamTime` before comparing.
- `spring.jpa.open-in-view=false`: a template may only read what its query loaded. Sessions reach `Mailbox` through `SchoolMailSessionRepository.findOfUser`, never through `SchoolMail.getSessions()` in a template.
- The site is always light: white and light-grey backgrounds, black text, no dark mode.
- Java 17, no new dependencies (Java or Python). Migrations are named `V<date>_<module>_<n>__<what>.sql` (School = 1).
- Real syncs with the saved EduSoft and Blackboard logins, and anything that reads the student's Outlook, run in the main session only, never a subagent. Never print `.env` values, the student ID, the device key, or any email text.
- Work on the branch `outlook-mailbox` **in the main working tree**, not a separate worktree: the checkpoints (Tasks 7 and 8) use the agent installed in `.venv`, which runs the main working tree's code.
- The project folder moved from `C:\IU SCHOOL` to `C:\IU_SCHOOL` on 2026-09-28. If `.venv/Scripts/python.exe -c "import sla_contract"` fails, run `.venv/Scripts/python.exe -m pip install --no-deps -e ./contract -e ./agent` before any Python test.
- Commands are for Git Bash, run from the repository root. `SCRATCH` is the session's scratchpad folder. Write files that contain backslashes with the editor, not with a shell here-document (the shell may drop one of two backslashes). Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The failure modes most likely to bite that the spec implies but a quick read of the tests might miss, and the tests that pin each:

1. **The session finder reading things that aren't event times.** Money ("15.000.000"), phone numbers, room numbers, a registration deadline, or a time with no day would become sessions and wrong Conflict marks. Pinned by `test_no_session_without_both_a_time_and_a_day`, `test_a_deadline_is_not_a_session_and_gives_no_day_to_the_next_sentence`, `test_bare_han_and_truoc_are_not_deadline_words` and `test_times_and_dates_in_links_are_ignored` (Task 2).
2. **Auto-Done hiding what the student still has to decide.** Opening an upcoming event to read it must not send it to Done, and a new reply must bring back a card that was opened. Pinned by `MailboxTest.openingMarksDoneUnlessItIsAnEventOrTaskStillAhead`, `MailboxTest.openedComesFromTheNewestEmailSoANewReplyIsUnread` and `MailboxPageTest.openingAnEventStillAheadOnlyMarksItOpened` (Task 3).
3. **Wrong Conflict marks.** A cancelled class must not count, an online one must; an exam without a length lasts 90 minutes; back-to-back is free; an event must not clash with itself; times compare in Vietnam time. Pinned by `MailSessionsTest` (all of it) and `ConflictsTest.backToBackIsNoConflict` (Task 4).
4. **Joined sessions lost.** A sync must not touch them even when their email is gone, and Save or Leave must not wipe sessions already over. Pinned by `IngestTest.joinedSessionsStayAfterASyncEvenWhenTheirEmailIsGone`, `JoinPageTest.leavingRemovesTheSessionsAheadButKeepsThoseOver` and `JoinPageTest.oneEmailHasAtMostTenJoinedSessions` (Task 4).
5. **Another user's events, and a page wider than a phone.** Pinned by `JoinPageTest.someoneElsesEventIs404`, `CalendarFeedTest.someoneElsesJoinedEventsAreNeverInMyCalendar` (Tasks 4 and 5) and the browser check's "no page wider than the screen" at 390×844 (Task 6).

## Decisions made while trying it out

- **Three migrations instead of one**, each with the task that needs it: `V20260928_1_2__mail_sessions.sql` (Task 1), `V20260928_1_3__mail_opened.sql` (Task 3), `V20260928_1_4__mail_joined.sql` (Task 4). The spec (§4.1) is updated in Task 9. Days are stored in `session_day`, because `day` is a reserved word.
- **A time goes with the dates of its sentence or line**, not only its line (the class-change reader's sentence splitter), so "Thời gian: 13h ngày 29/9. Hạn đăng ký: 25/9." keeps the session. The spec (§3.2) is updated in Task 9.
- **`13.00` needs two digits after the dot**, so money and phone numbers are never times.
- **A repeated day and start keeps an end if any copy has one**: "Workshop 30/9 lúc 14h" in the subject and "14h00 – 16h00 ngày 30/9" in the text give 14:00–16:00.
- **`fold` moved into `class_changes.py`** (the session finder needs it); `mail_rules` imports it, so `mail_rules.fold` still works.
- **`Mailbox.build` takes the sessions by mail key and "now" in Vietnam wall-clock time** (a session can end in the middle of the day), instead of today's date.
- **Save and Leave on Join… only touch joined sessions that haven't ended**; sessions already over stay in the Timetable. The spec (§4.6) is updated in Task 9.
- **Joined sessions are saved under the card's newest email key**, and a card finds them by any of its emails' keys.
- **"Not sorted" disappears once the student used Move to…** on the card.
- **On phones each row takes three short lines** (labels and date; sender and subject; actions): with Web ↗, Move…, Join… and ✓ Done on the second line the subject had no room, and the page was 402 px wide at 390. The spec (§4.2) is updated in Task 9.
- **The date and action columns have fixed widths** (130 px and 270 px), so the rows line up.
- **The browser check never opens Outlook**: it never clicks an `sla-mail:` link (on this laptop that starts the real agent) and routes Outlook on the web to nothing.
- **The Timetable gets a "Joined event" legend** and says "No classes, exams or events in this period." when empty.
- **The Outlook spec keeps its text and gains notes** where the mailbox-events design changed it (Task 9), and its §4.2 now says the 3-minute limit gives up a background thread (the Outlook plan's executor ruling of 2026-09-28).

## File Structure

```
contract/
  sla_contract/schema.py                      + MailSession, MailItem.sessions; − loses_points (Task 1)
  samples/finish-outlook.json                 + sessions (Task 1)
  samples/invalid/email-with-loses-points.json, email-with-eleven-sessions.json   (Task 1)
  tests/test_contract.py                      (Task 1)
agent/
  sla_agent/mail_rules.py                     − loses_points (Task 1); sessions_of, fold from class_changes (Task 2)
  sla_agent/class_changes.py                  + Session, sessions_in, fold, DEADLINE (Task 2)
  tools/anonymize_mail.py                     anonymized samples, pinning sessions (Task 8)
  tests/test_mail_rules.py (1, 2), test_sessions.py (2), test_anonymize_mail.py + test_mail_samples.py
        + fixtures/mail-samples.json (8)
web/src/main/
  resources/db/migration/V20260928_1_2__mail_sessions.sql (1), _1_3__mail_opened.sql (3), _1_4__mail_joined.sql (4)
  java/.../school/sync/SyncContract.java, Ingest.java            sessions in, loses_points out (Task 1)
  java/.../school/model/SchoolMail.java, SchoolMailSession*.java (1); SchoolMailChoice.java, SchoolMailSettings*.java (3);
        SchoolMailJoined*.java (4)
  java/.../school/mail/Mailbox.java           rows data: sessions, past from sessions, opened (1, 3, 4)
  java/.../school/mail/Conflicts.java, MailSessions.java                           (Task 4)
  java/.../school/pages/MailboxController.java  opened, settings (3); join, leave (4); JoinForm.java (4)
  java/.../school/schedule/Schedule.java      joined sessions as "event" items (Task 5)
  resources/templates/school/mailbox.html     rows (1, 3, 4); mailbox-join.html (4); fragments.html, timetable.html (5)
  resources/static/css/style.css (1, 3, 4, 5); static/js/mailbox.js (3); static/js/timetable.js (5)
web/src/test/java/.../
  school/sync/SyncContractTest.java, IngestTest.java (1, 4); school/model/SchoolTablesTest.java (1, 3);
  core/MigrationTest.java (1, 3, 4); school/SchoolTestData.java (1); school/mail/MailboxTest.java (1, 3);
  school/pages/MailboxPageTest.java (1, 3); school/mail/ConflictsTest.java, MailSessionsTest.java,
  school/pages/JoinPageTest.java (4); school/schedule/CalendarFeedTest.java, school/pages/SchoolPagesTest.java (5)
README.md, both specs                                                              (Task 9)
```

Test counts: today (`6363848`) 427 Java and 365 Python tests pass. After each task: Task 1 → 434 Java, 371 Python; Task 2 → 415 Python; Task 3 → 448 Java; Task 4 → 477 Java; Task 5 → 481 Java; Task 8 → 415 + 3 + (number of sample emails) + 1 Python.

---

### Task 1: Sessions in the upload format, and no more "lose points"

Each email in the `outlook` part gains `sessions` (up to 10: a Vietnam day, a start and an optional end after it) and loses `loses_points`, on both sides of the format (§3.3). The agent stops computing "loses points"; the site drops its column, stores each email's sessions in a new table replaced at every sync (§4.1), and no longer shows the lose-points tag. The agent still sends no sessions until Task 2.

**Files:**
- Create: `contract/samples/invalid/email-with-loses-points.json`, `contract/samples/invalid/email-with-eleven-sessions.json`, `web/src/main/resources/db/migration/V20260928_1_2__mail_sessions.sql`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSession.java`, `SchoolMailSessionRepository.java` (in `school/model/`)
- Modify: `contract/sla_contract/schema.py`, `agent/sla_agent/mail_rules.py`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, `Ingest.java`, `school/model/SchoolMail.java`, `school/mail/Mailbox.java`, `web/src/main/resources/templates/school/mailbox.html`, `web/src/main/resources/static/css/style.css`
- Test: `contract/samples/finish-outlook.json`, `contract/tests/test_contract.py`, `agent/tests/test_mail_rules.py`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`, `IngestTest.java`, `school/model/SchoolTablesTest.java`, `core/MigrationTest.java`, `school/SchoolTestData.java`, `school/mail/MailboxTest.java`, `school/pages/MailboxPageTest.java`

**Interfaces:**
- Consumes: `MailItem`, `Outlook` (Python and Java, Outlook plan Task 1); `SchoolMail`, `Ingest.saveOutlook` (Outlook plan Task 6); `Mailbox.Card` (Outlook plan Task 7).
- Produces:
  - Python: `MailSession(day, start, end=None)` (refuses an end not after the start); `MailItem.sessions` (max 10); `MailItem` has no `loses_points`.
  - Java: `SyncContract.MailSession(day, start, end)`; `MailItem.sessions()` (`List.of()` when missing), no `losesPoints()`.
  - `SchoolMailSession(mail, day, start, end)` with getters; `SchoolMail.getSessions()` (ordered by day and start); the `SchoolMail` constructor loses its `losesPoints` argument: `SchoolMail(userId, mailKey, entryId, threadId, receivedAt, senderName, senderAddress, subject, categories, fromLecturer, dates, sorted, blackboardTitle)`.
  - `SchoolMailSessionRepository.findOfUser(userId)` (with the email, in time order), `deleteAllOfUser(userId)`.
  - `Mailbox.Card` has no `losesPoints`.

- [ ] **Step 1: Write the failing tests**

`contract/samples/invalid/email-with-eleven-sessions.json` (new):

```json
{
  "outlook": {
    "status": "ok",
    "data": {
      "since": "2026-08-01",
      "connected": true,
      "emails": [
        {
          "key": "51e77916873ea9f29962ffb91e14c24907e439a41fc6bfdfad52010416af1631",
          "entry_id": "00000000A1B2C3D5",
          "received_at": "2026-09-24T03:30:00+00:00",
          "categories": ["event"],
          "sessions": [
            {"day": "2026-10-01", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-02", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-03", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-04", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-05", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-06", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-07", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-08", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-09", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-10", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-11", "start": "13:30:00", "end": "16:30:00"}
          ]
        }
      ]
    }
  }
}
```

`contract/samples/invalid/email-with-loses-points.json` (new):

```json
{
  "outlook": {
    "status": "ok",
    "data": {
      "since": "2026-08-01",
      "connected": true,
      "emails": [
        {
          "key": "51e77916873ea9f29962ffb91e14c24907e439a41fc6bfdfad52010416af1631",
          "entry_id": "00000000A1B2C3D5",
          "received_at": "2026-09-24T03:30:00+00:00",
          "categories": ["event", "training_points"],
          "loses_points": true
        }
      ]
    }
  }
}
```

`agent/tests/test_mail_rules.py`: one change.

Replace

```python
    assert categories(email("Thư mời workshop", "điểm rèn luyện"), True) == ["class", "event"]


def test_loses_points():
    assert sort_email(email("Workshop", "Sinh viên đã đăng ký mà vắng sẽ bị trừ 05 điểm rèn luyện."), CONTEXT) \
        .loses_points
    assert not sort_email(email("Workshop", "Được cộng điểm rèn luyện."), CONTEXT).loses_points


# ---- dates (5.4) -------------------------------------------------------------------


```

with

```python
    assert categories(email("Thư mời workshop", "điểm rèn luyện"), True) == ["class", "event"]


# ---- dates (5.4) -------------------------------------------------------------------


```

`contract/samples/finish-outlook.json`: one change.

Replace

```json
          "subject": "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
          "categories": ["event", "training_points"],
          "dates": ["2026-09-25", "2026-10-02"],
          "loses_points": true
        },
        {
          "key": "8a8799a54ac207a586b7fc42e9fd68adb4e6c2ff5f8b6e0712d68df3acc157ad",
```

with

```json
          "subject": "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
          "categories": ["event", "training_points"],
          "dates": ["2026-09-25", "2026-10-02"],
          "sessions": [
            {"day": "2026-09-25", "start": "13:30:00", "end": "16:30:00"},
            {"day": "2026-10-02", "start": "08:00:00"}
          ]
        },
        {
          "key": "8a8799a54ac207a586b7fc42e9fd68adb4e6c2ff5f8b6e0712d68df3acc157ad",
```

`contract/tests/test_contract.py`: 2 changes.

Change 1: replace

```python
    assert second.categories == ["event", "training_points"]
    assert (third.sorted, third.class_changes[0].start.isoformat()) == (False, "13:15:00")
    assert (second.from_lecturer, second.sorted, second.class_changes) == (False, True, [])


@pytest.mark.parametrize(
```

with

```python
    assert second.categories == ["event", "training_points"]
    assert (third.sorted, third.class_changes[0].start.isoformat()) == (False, "13:15:00")
    assert (second.from_lecturer, second.sorted, second.class_changes) == (False, True, [])
    assert [(s.day.isoformat(), s.start.isoformat(), s.end and s.end.isoformat()) for s in second.sessions] == [
        ("2026-09-25", "13:30:00", "16:30:00"), ("2026-10-02", "08:00:00", None)]
    assert first.sessions == []


@pytest.mark.parametrize(
```

Change 2: replace

```python
        lambda p: p["emails"][0]["class_changes"][0].update(kind="moved"),
        lambda p: p["emails"][0].update(dates=["2026-09-22"] * 31),
        lambda p: p.update(emails=p["emails"] * 667),
    ],
    ids=["text", "html", "three-categories", "repeated-category", "unknown-category", "lower-case-entry-id",
         "script-entry-id", "bad-key", "naive-time", "unknown-change", "too-many-dates", "too-many-emails"],
)
def test_bad_outlook_data_is_rejected(change):
    data = outlook_payload()
```

with

```python
        lambda p: p["emails"][0]["class_changes"][0].update(kind="moved"),
        lambda p: p["emails"][0].update(dates=["2026-09-22"] * 31),
        lambda p: p.update(emails=p["emails"] * 667),
        lambda p: p["emails"][1].update(loses_points=True),
        lambda p: p["emails"][1].update(sessions=p["emails"][1]["sessions"] * 5 + [p["emails"][1]["sessions"][0]]),
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

`web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`: one change.

Replace

```java
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status",
                "flyway_schema_history");
    }

```

with

```java
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status", "school_mail_sessions",
                "flyway_schema_history");
    }

```

`web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java`: one change.

Replace

```java
    public SchoolMail lecturerEmail(AppUser user, String key, LocalDateTime receivedAt, String blackboardTitle) {
        return new SchoolMail(user.id(), key, "00A1", null, receivedAt, "Tran Van An", "tvan@hcmiu.edu.vn",
                blackboardTitle != null ? "Course_S1: " + blackboardTitle : "Class notice", List.of("class"), true,
                List.of(), false, true, blackboardTitle);
    }

    public SchoolMail emailChange(SchoolMail mail, String code, String kind, LocalDate day, LocalTime start,
```

with

```java
    public SchoolMail lecturerEmail(AppUser user, String key, LocalDateTime receivedAt, String blackboardTitle) {
        return new SchoolMail(user.id(), key, "00A1", null, receivedAt, "Tran Van An", "tvan@hcmiu.edu.vn",
                blackboardTitle != null ? "Course_S1: " + blackboardTitle : "Class notice", List.of("class"), true,
                List.of(), true, blackboardTitle);
    }

    public SchoolMail emailChange(SchoolMail mail, String code, String kind, LocalDate day, LocalTime start,
```

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`: 4 changes.

Change 1: replace

```java
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        boolean losesPoints;
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
```

with

```java
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
```

Change 2: replace

```java
            return this;
        }

        Mail losesPoints() {
            this.losesPoints = true;
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
```

with

```java
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
```

Change 3: replace

```java

        SchoolMail row() {
            return new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender", sender,
                    subject, categories, lecturer, dates, losesPoints, sorted, null);
        }
    }

```

with

```java

        SchoolMail row() {
            return new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender", sender,
                    subject, categories, lecturer, dates, sorted, null);
        }
    }

```

Change 4: replace

```java
    }

    @Test
    void aCardsDatesAndWarningComeFromAllItsEmails() {
        Card card = build(
                new Mail("new", 1, "event").thread("T").on(4),
                new Mail("old", 5, "event").thread("T").on(2).losesPoints()).card("new");

        assertThat(card.dates()).containsExactly(TODAY.plusDays(2), TODAY.plusDays(4));
        assertThat(card.losesPoints()).isTrue();
    }

    @Test
```

with

```java
    }

    @Test
    void aCardsDatesComeFromAllItsEmails() {
        Card card = build(
                new Mail("new", 1, "event").thread("T").on(4),
                new Mail("old", 5, "event").thread("T").on(2)).card("new");

        assertThat(card.dates()).containsExactly(TODAY.plusDays(2), TODAY.plusDays(4));
    }

    @Test
```

`web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`: 2 changes.

Change 1: replace

```java
    void mailCategoriesAndDatesAreKeptAsCommaSeparatedText() {
        SchoolMail mail = new SchoolMail(userId, "a".repeat(64), "00AB", null, SEPT_28, "P.CTSV [OSS]",
                "oss@hcmiu.edu.vn", "Workshop", List.of("event", "training_points"), false,
                List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)), true, true, null);
        mail.getChanges().add(new SchoolMailChange(mail, "IT093IU", "makeup", LocalDate.of(2026, 10, 3),
                java.time.LocalTime.of(13, 15), null, "A2.401"));
        db.persist(mail);
        SchoolMail empty = new SchoolMail(userId, "b".repeat(64), "00AC", "T1", SEPT_28, "", "", "", List.of(), false,
                List.of(), false, false, null);
        db.persist(empty);

        SchoolMail again = reloaded(mail, mail.getId());
```

with

```java
    void mailCategoriesAndDatesAreKeptAsCommaSeparatedText() {
        SchoolMail mail = new SchoolMail(userId, "a".repeat(64), "00AB", null, SEPT_28, "P.CTSV [OSS]",
                "oss@hcmiu.edu.vn", "Workshop", List.of("event", "training_points"), false,
                List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)), true, null);
        mail.getChanges().add(new SchoolMailChange(mail, "IT093IU", "makeup", LocalDate.of(2026, 10, 3),
                java.time.LocalTime.of(13, 15), null, "A2.401"));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 10, 2), java.time.LocalTime.of(8, 0), null));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 9, 29), java.time.LocalTime.of(13, 30),
                java.time.LocalTime.of(16, 30)));
        db.persist(mail);
        SchoolMail empty = new SchoolMail(userId, "b".repeat(64), "00AC", "T1", SEPT_28, "", "", "", List.of(), false,
                List.of(), false, null);
        db.persist(empty);

        SchoolMail again = reloaded(mail, mail.getId());
```

Change 2: replace

```java
        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        SchoolMail emptyAgain = db.find(SchoolMail.class, empty.getId());
        assertThat(List.of(emptyAgain.getCategories(), emptyAgain.getDates())).containsExactly(List.of(), List.of());
    }
```

with

```java
        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        assertThat(again.getSessions()).extracting(s -> s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly("2026-09-29 13:30-16:30", "2026-10-02 08:00-null");
        SchoolMail emptyAgain = db.find(SchoolMail.class, empty.getId());
        assertThat(List.of(emptyAgain.getCategories(), emptyAgain.getDates())).containsExactly(List.of(), List.of());
    }
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java`: 2 changes.

Change 1: replace

```java
    }

    void mail(AppUser who, String key, String thread, int hoursAgo, String sender, String subject,
            List<String> categories, boolean lecturer, List<LocalDate> dates, boolean losesPoints, boolean sorted) {
        db.persist(new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), thread,
                NOW.minusHours(hoursAgo), sender, sender.toLowerCase().replace(' ', '.') + "@hcmiu.edu.vn", subject,
                categories, lecturer, dates, losesPoints, sorted, null));
        db.flush();
    }

    void inbox(AppUser who) {
        mail(who, TCL, null, 5, "P.CTSV [OSS]", "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                List.of("event", "training_points"), false, List.of(LocalDate.of(2026, 9, 30)), true, true);
        mail(who, LAB_QUESTION, "T1", 30, "Vo Minh Khoa", "Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), false, true);
        mail(who, LAB_REPLY, "T1", 2, "Vo Minh Khoa", "Re: Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), false, true);
        mail(who, INVOICE, null, 8, "M-Invoice", "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", List.of("money"),
                false, List.of(), false, false);
        db.persist(new SchoolMailStatus(who.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
        db.flush();
    }
```

with

```java
    }

    void mail(AppUser who, String key, String thread, int hoursAgo, String sender, String subject,
            List<String> categories, boolean lecturer, List<LocalDate> dates, boolean sorted) {
        db.persist(new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), thread,
                NOW.minusHours(hoursAgo), sender, sender.toLowerCase().replace(' ', '.') + "@hcmiu.edu.vn", subject,
                categories, lecturer, dates, sorted, null));
        db.flush();
    }

    void inbox(AppUser who) {
        mail(who, TCL, null, 5, "P.CTSV [OSS]", "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                List.of("event", "training_points"), false, List.of(LocalDate.of(2026, 9, 30)), true);
        mail(who, LAB_QUESTION, "T1", 30, "Vo Minh Khoa", "Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), true);
        mail(who, LAB_REPLY, "T1", 2, "Vo Minh Khoa", "Re: Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), true);
        mail(who, INVOICE, null, 8, "M-Invoice", "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", List.of("money"),
                false, List.of(), false);
        db.persist(new SchoolMailStatus(who.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
        db.flush();
    }
```

Change 2: replace

```java
                .doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("Couldn't sort this email automatically. Use Move to…");
        assertThat(box(html, "events")).contains("★ Training points").contains("⚠ lose points if absent")
                .contains("Next: Wed 30/09");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00");
    }
```

with

```java
                .doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("Couldn't sort this email automatically. Use Move to…");
        assertThat(box(html, "events")).contains("★ Training points").contains("Next: Wed 30/09")
                .doesNotContain("lose points");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00");
    }
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`: 5 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
```

Change 2: replace

```java

    @Autowired
    SchoolMailChangeRepository mailChanges;

    @Autowired
    SchoolMailChoiceRepository mailChoices;
```

with

```java

    @Autowired
    SchoolMailChangeRepository mailChanges;

    @Autowired
    SchoolMailSessionRepository mailSessions;

    @Autowired
    SchoolMailChoiceRepository mailChoices;
```

Change 3: replace

```java
        SchoolMail workshop = saved.get(1);
        assertThat(workshop.getCategories()).containsExactly("event", "training_points");
        assertThat(workshop.getDates()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(List.of(workshop.isLosesPoints(), workshop.isFromLecturer(), workshop.isSorted()))
                .containsExactly(true, false, true);
        assertThat(workshop.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 3, 30));
        assertThat(saved.get(0).isSorted()).isFalse();
        assertThat(saved.get(2).getBlackboardTitle()).isEqualTo("Online class on 22/9");
```

with

```java
        SchoolMail workshop = saved.get(1);
        assertThat(workshop.getCategories()).containsExactly("event", "training_points");
        assertThat(workshop.getDates()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(List.of(workshop.isFromLecturer(), workshop.isSorted())).containsExactly(false, true);
        assertThat(mailSessions.findOfUser(userId))
                .extracting(s -> s.getMail().getMailKey() + " " + s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly(workshop.getMailKey() + " 2026-09-25 13:30-16:30",
                        workshop.getMailKey() + " 2026-10-02 08:00-null");
        assertThat(workshop.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 3, 30));
        assertThat(saved.get(0).isSorted()).isFalse();
        assertThat(saved.get(2).getBlackboardTitle()).isEqualTo("Online class on 22/9");
```

Change 4: replace

```java
        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId)).extracting(SchoolMail::getMailKey)
                .containsExactly(KEY_1);
        assertThat(mailChanges.findOfUser(userId)).hasSize(1);
        assertThat(mailChoices.findByUserId(userId)).extracting(SchoolMailChoice::getMailKey).containsExactly(KEY_1);
    }

```

with

```java
        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId)).extracting(SchoolMail::getMailKey)
                .containsExactly(KEY_1);
        assertThat(mailChanges.findOfUser(userId)).hasSize(1);
        assertThat(mailSessions.findOfUser(userId)).isEmpty();
        assertThat(mailChoices.findByUserId(userId)).extracting(SchoolMailChoice::getMailKey).containsExactly(KEY_1);
    }

```

Change 5: replace

```java
        syncOutlook(userId, ok(outlookPayload()));

        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(other)).hasSize(3);
        assertThat(mailChoices.findByUserId(other)).hasSize(1);
    }
}
```

with

```java
        syncOutlook(userId, ok(outlookPayload()));

        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(other)).hasSize(3);
        assertThat(mailSessions.findOfUser(other)).hasSize(2);
        assertThat(mailChoices.findByUserId(other)).hasSize(1);
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`: 2 changes.

Change 1: replace

```java
        assertThat(emails.get(2).classChanges().get(0).start()).isEqualTo(LocalTime.of(13, 15));
        assertThat(List.of(emails.get(1).fromLecturer(), emails.get(1).sorted(), emails.get(1).classChanges()))
                .containsExactly(false, true, List.of());
    }

    static Stream<Arguments> badOutlookData() {
```

with

```java
        assertThat(emails.get(2).classChanges().get(0).start()).isEqualTo(LocalTime.of(13, 15));
        assertThat(List.of(emails.get(1).fromLecturer(), emails.get(1).sorted(), emails.get(1).classChanges()))
                .containsExactly(false, true, List.of());
        assertThat(emails.get(1).sessions()).extracting(s -> s.day() + " " + s.start() + "-" + s.end())
                .containsExactly("2026-09-25 13:30-16:30", "2026-10-02 08:00-null");
        assertThat(emails.get(0).sessions()).isEmpty();
    }

    static Stream<Arguments> badOutlookData() {
```

Change 2: replace

```java
                        emails.addAll(list(p, "emails"));
                    }
                    p.put("emails", emails);
                }));
    }

    @ParameterizedTest(name = "{0}")
```

with

```java
                        emails.addAll(list(p, "emails"));
                    }
                    p.put("emails", emails);
                }),
                Arguments.of("loses-points", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1)
                        .put("loses_points", true)),
                Arguments.of("eleven-sessions", (Consumer<Map<String, Object>>) p -> {
                    List<Object> sessions = new ArrayList<>();
                    for (int i = 0; i < 11; i++) {
                        sessions.add(list(p, "emails", 1, "sessions").get(0));
                    }
                    at(p, "emails", 1).put("sessions", sessions);
                }),
                Arguments.of("end-not-after-start", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1, "sessions", 0)
                        .put("end", "13:30:00")),
                Arguments.of("bad-session-time", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1, "sessions", 0)
                        .put("start", "25:00:00")),
                Arguments.of("session-without-start", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1,
                        "sessions", 1).remove("start")));
    }

    @ParameterizedTest(name = "{0}")
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest contract agent -q`
Expected: FAIL: `test_an_outlook_section_is_accepted_next_to_the_others` and `test_every_shared_sample_is_accepted[finish-outlook.json]` (`sessions` is an unknown field), and `test_every_shared_invalid_sample_is_refused[email-with-loses-points.json]` (`loses_points` is still allowed): `3 failed, 368 passed`.

Run: `(cd web && ./mvnw -B test -Dtest='SyncContractTest,IngestTest,SchoolTablesTest,MigrationTest,MailboxTest,MailboxPageTest')`
Expected: compilation fails: `cannot find symbol` for `class SchoolMailSession`, `method getSessions` and `method sessions`.

- [ ] **Step 3: The format, the agent, the table and saving**

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSession.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One time an email's event or school task takes place, as the laptop found it in the email. Day and times are
 * Vietnam time; end may be empty. Replaced with its email at every sync.
 */
@Entity
@Table(name = "school_mail_sessions")
public class SchoolMailSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_id", nullable = false)
    private SchoolMail mail;

    @Column(name = "session_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time", nullable = false)
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    protected SchoolMailSession() {
    }

    public SchoolMailSession(SchoolMail mail, LocalDate day, LocalTime start, LocalTime end) {
        this.mail = mail;
        this.day = day;
        this.start = start;
        this.end = end;
    }

    public Integer getId() {
        return id;
    }

    public SchoolMail getMail() {
        return mail;
    }

    public LocalDate getDay() {
        return day;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSessionRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailSessionRepository extends JpaRepository<SchoolMailSession, Integer> {

    /** A user's sessions from email, with their email, in time order. */
    @Query("select s from SchoolMailSession s join fetch s.mail m where m.userId = :userId order by s.day, s.start")
    List<SchoolMailSession> findOfUser(Integer userId);

    @Modifying
    @Query("delete from SchoolMailSession s where s.mail.id in (select m.id from SchoolMail m where m.userId = :userId)")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/resources/db/migration/V20260928_1_2__mail_sessions.sql` (new):

```sql
-- Mailbox, round two: the times an email's event or school task takes place, as the laptop found them,
-- and no more "lose points" (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 3.3 and 4.1).
ALTER TABLE school_mail DROP COLUMN loses_points;

CREATE TABLE school_mail_sessions (
    id INT NOT NULL AUTO_INCREMENT,
    mail_id INT NOT NULL,
    session_day DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_mail_sessions_mail FOREIGN KEY (mail_id) REFERENCES school_mail (id) ON DELETE CASCADE
);
```

`agent/sla_agent/mail_rules.py`: 3 changes.

Change 1: replace

```python
PROMOTION = _phrase(PROMOTION_WORDS)
TRAINING = _phrase([TRAINING_POINTS])
TEAMS_ADDED = _phrase(TEAMS_ADDED_WORDS)
LOSES_POINTS = re.compile(r"\btru\b.{0,30}?\bdiem\s+ren\s+luyen\b", re.DOTALL)


def _domain(address):
```

with

```python
PROMOTION = _phrase(PROMOTION_WORDS)
TRAINING = _phrase([TRAINING_POINTS])
TEAMS_ADDED = _phrase(TEAMS_ADDED_WORDS)


def _domain(address):
```

Change 2: replace

```python
    return [name for name in ORDER if name in found][:MAX_CATEGORIES]


def loses_points(email):
    """Spec 5.4: "trừ" followed within 30 characters by "điểm rèn luyện"."""
    return bool(LOSES_POINTS.search(fold(email.subject) + "\n" + fold(email.text)))


def course_of(email, context):
    """Spec 5.5: the one course a lecturer's email is about, or None."""
    for name, code in context.bb_courses:
```

with

```python
    return [name for name in ORDER if name in found][:MAX_CATEGORIES]


def course_of(email, context):
    """Spec 5.5: the one course a lecturer's email is about, or None."""
    for name, code in context.bb_courses:
```

Change 3: replace

```python
            categories=categories(email, lecturer),
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            loses_points=loses_points(email),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

with

```python
            categories=categories(email, lecturer),
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

`contract/sla_contract/schema.py`: 2 changes.

Change 1: replace

```python
    room: Room | None = None


class MailItem(_Strict):
    """What the website may know about one email: never its text."""

```

with

```python
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

```

Change 2: replace

```python
    categories: Annotated[list[MailCategory], Field(max_length=2)] = []
    from_lecturer: bool = False
    dates: Annotated[list[date], Field(max_length=30)] = []
    loses_points: bool = False
    sorted: bool = True  # False: the sorting rules failed on this email
    blackboard_title: Annotated[str, Field(max_length=255)] | None = None
    class_changes: Annotated[list[MailClassChange], Field(max_length=10)] = []
```

with

```python
    categories: Annotated[list[MailCategory], Field(max_length=2)] = []
    from_lecturer: bool = False
    dates: Annotated[list[date], Field(max_length=30)] = []
    sessions: Annotated[list[MailSession], Field(max_length=10)] = []  # found in any email; shown for events
    sorted: bool = True  # False: the sorting rules failed on this email
    blackboard_title: Annotated[str, Field(max_length=255)] | None = None
    class_changes: Annotated[list[MailClassChange], Field(max_length=10)] = []
```

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`: 2 changes.

Change 1: replace

```java
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, LocalDate nextDate, boolean losesPoints, boolean sorted, boolean done,
            int messages, int copies) {

        public boolean trainingPoints() {
```

with

```java
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, LocalDate nextDate, boolean sorted, boolean done,
            int messages, int copies) {

        public boolean trainingPoints() {
```

Change 2: replace

```java
                : newest.isFromLecturer();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), next, mails.stream().anyMatch(SchoolMail::isLosesPoints),
                newest.isSorted(), newestChoice != null && newestChoice.isDone(), mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

with

```java
                : newest.isFromLecturer();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), next, newest.isSorted(),
                newestChoice != null && newestChoice.isDone(), mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMail.java`: 6 changes.

Change 1: replace

```java
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
```

with

```java
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
```

Change 2: replace

```java
    @Column(nullable = false, length = 400)
    private List<LocalDate> dates;

    @Column(name = "loses_points", nullable = false)
    private boolean losesPoints;

    @Column(name = "is_sorted", nullable = false)
    private boolean sorted;

```

with

```java
    @Column(nullable = false, length = 400)
    private List<LocalDate> dates;

    @Column(name = "is_sorted", nullable = false)
    private boolean sorted;

```

Change 3: replace

```java
    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolMailChange> changes = new ArrayList<>();

    protected SchoolMail() {
    }

    public SchoolMail(Integer userId, String mailKey, String entryId, String threadId, LocalDateTime receivedAt,
            String senderName, String senderAddress, String subject, List<String> categories, boolean fromLecturer,
            List<LocalDate> dates, boolean losesPoints, boolean sorted, String blackboardTitle) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.entryId = entryId;
```

with

```java
    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolMailChange> changes = new ArrayList<>();

    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("day, start")
    private List<SchoolMailSession> sessions = new ArrayList<>();

    protected SchoolMail() {
    }

    public SchoolMail(Integer userId, String mailKey, String entryId, String threadId, LocalDateTime receivedAt,
            String senderName, String senderAddress, String subject, List<String> categories, boolean fromLecturer,
            List<LocalDate> dates, boolean sorted, String blackboardTitle) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.entryId = entryId;
```

Change 4: replace

```java
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.dates = dates;
        this.losesPoints = losesPoints;
        this.sorted = sorted;
        this.blackboardTitle = blackboardTitle;
    }
```

with

```java
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.dates = dates;
        this.sorted = sorted;
        this.blackboardTitle = blackboardTitle;
    }
```

Change 5: replace

```java
        return dates;
    }

    public boolean isLosesPoints() {
        return losesPoints;
    }

    public boolean isSorted() {
        return sorted;
    }
```

with

```java
        return dates;
    }

    public boolean isSorted() {
        return sorted;
    }
```

Change 6: replace

```java
    public List<SchoolMailChange> getChanges() {
        return changes;
    }
}

```

with

```java
    public List<SchoolMailChange> getChanges() {
        return changes;
    }

    public List<SchoolMailSession> getSessions() {
        return sessions;
    }
}

```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`: 7 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSession;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailClassChange;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailItem;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Outlook;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Section;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Timetable;
```

with

```java
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailClassChange;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailItem;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailSession;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Outlook;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Section;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Timetable;
```

Change 3: replace

```java
    private final SchoolBbMaterialRepository bbMaterials;
    private final SchoolMailRepository mails;
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailChoiceRepository mailChoices;
    private final SchoolMailStatusRepository mailStatus;

```

with

```java
    private final SchoolBbMaterialRepository bbMaterials;
    private final SchoolMailRepository mails;
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailSessionRepository mailSessions;
    private final SchoolMailChoiceRepository mailChoices;
    private final SchoolMailStatusRepository mailStatus;

```

Change 4: replace

```java
            SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolTuitionRepository tuition,
            SchoolBbCourseRepository bbCourses, SchoolBbAnnouncementRepository bbAnnouncements,
            SchoolBbAssignmentRepository bbAssignments, SchoolBbMaterialRepository bbMaterials,
            SchoolMailRepository mails, SchoolMailChangeRepository mailChanges, SchoolMailChoiceRepository mailChoices,
            SchoolMailStatusRepository mailStatus) {
        this.runs = runs;
        this.changes = changes;
```

with

```java
            SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolTuitionRepository tuition,
            SchoolBbCourseRepository bbCourses, SchoolBbAnnouncementRepository bbAnnouncements,
            SchoolBbAssignmentRepository bbAssignments, SchoolBbMaterialRepository bbMaterials,
            SchoolMailRepository mails, SchoolMailChangeRepository mailChanges,
            SchoolMailSessionRepository mailSessions, SchoolMailChoiceRepository mailChoices,
            SchoolMailStatusRepository mailStatus) {
        this.runs = runs;
        this.changes = changes;
```

Change 5: replace

```java
        this.bbMaterials = bbMaterials;
        this.mails = mails;
        this.mailChanges = mailChanges;
        this.mailChoices = mailChoices;
        this.mailStatus = mailStatus;
    }
```

with

```java
        this.bbMaterials = bbMaterials;
        this.mails = mails;
        this.mailChanges = mailChanges;
        this.mailSessions = mailSessions;
        this.mailChoices = mailChoices;
        this.mailStatus = mailStatus;
    }
```

Change 6: replace

```java
     */
    private List<Change> saveOutlook(Integer userId, Outlook data, LocalDateTime now) {
        mailChanges.deleteAllOfUser(userId);
        mails.deleteAllOfUser(userId);
        Set<String> keys = new LinkedHashSet<>();
        for (MailItem item : data.emails()) {
```

with

```java
     */
    private List<Change> saveOutlook(Integer userId, Outlook data, LocalDateTime now) {
        mailChanges.deleteAllOfUser(userId);
        mailSessions.deleteAllOfUser(userId);
        mails.deleteAllOfUser(userId);
        Set<String> keys = new LinkedHashSet<>();
        for (MailItem item : data.emails()) {
```

Change 7: replace

```java
            }
            SchoolMail mail = new SchoolMail(userId, item.key(), item.entryId(), item.threadId(),
                    toUtc(item.receivedAt()), item.senderName(), item.senderAddress(), item.subject(), item.categories(),
                    item.fromLecturer(), item.dates(), item.losesPoints(), item.sorted(), item.blackboardTitle());
            for (MailClassChange c : item.classChanges()) {
                mail.getChanges().add(new SchoolMailChange(mail, c.courseCode(), c.kind(), c.day(), c.start(), c.end(),
                        c.room()));
            }
            mails.save(mail);
        }
```

with

```java
            }
            SchoolMail mail = new SchoolMail(userId, item.key(), item.entryId(), item.threadId(),
                    toUtc(item.receivedAt()), item.senderName(), item.senderAddress(), item.subject(), item.categories(),
                    item.fromLecturer(), item.dates(), item.sorted(), item.blackboardTitle());
            for (MailClassChange c : item.classChanges()) {
                mail.getChanges().add(new SchoolMailChange(mail, c.courseCode(), c.kind(), c.day(), c.start(), c.end(),
                        c.room()));
            }
            for (MailSession s : item.sessions()) {
                mail.getSessions().add(new SchoolMailSession(mail, s.day(), s.start(), s.end()));
            }
            mails.save(mail);
        }
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`: 3 changes.

Change 1: replace

```java
            @Chars(max = 50) String room) {
    }

    /** What the website may know about one email: never its text. */
    public record MailItem(
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String key,
```

with

```java
            @Chars(max = 50) String room) {
    }

    /** One time an event or school task takes place, as the laptop found it in the email. Vietnam time. */
    public record MailSession(@NotNull LocalDate day, @NotNull LocalTime start, LocalTime end) {

        @AssertTrue(message = "end must be after start")
        boolean isEndAfterStart() {
            return end == null || start == null || end.isAfter(start);
        }
    }

    /** What the website may know about one email: never its text. */
    public record MailItem(
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String key,
```

Change 2: replace

```java
            @Size(max = 2) List<@NotNull @Pattern(regexp = MAIL_CATEGORIES) String> categories,
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            Boolean losesPoints,
            Boolean sorted,
            @Chars(max = 255) String blackboardTitle,
            @Size(max = 10) List<@Valid MailClassChange> classChanges) {
```

with

```java
            @Size(max = 2) List<@NotNull @Pattern(regexp = MAIL_CATEGORIES) String> categories,
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            @Size(max = 10) List<@Valid MailSession> sessions,
            Boolean sorted,
            @Chars(max = 255) String blackboardTitle,
            @Size(max = 10) List<@Valid MailClassChange> classChanges) {
```

Change 3: replace

```java
            categories = categories == null ? List.of() : categories;
            fromLecturer = fromLecturer != null && fromLecturer;
            dates = dates == null ? List.of() : dates;
            losesPoints = losesPoints != null && losesPoints;
            sorted = sorted == null || sorted;
            classChanges = classChanges == null ? List.of() : classChanges;
        }
```

with

```java
            categories = categories == null ? List.of() : categories;
            fromLecturer = fromLecturer != null && fromLecturer;
            dates = dates == null ? List.of() : dates;
            sessions = sessions == null ? List.of() : sessions;
            sorted = sorted == null || sorted;
            classChanges = classChanges == null ? List.of() : classChanges;
        }
```

`web/src/main/resources/static/css/style.css`: one change.

Replace

```css
.mail-tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 0 0 6px; }
.tag { padding: 1px 8px; font-size: 0.8rem; border: 1px solid var(--border); border-radius: 999px; background: var(--bg); }
.tag-training_points { background: #fff4d6; border-color: #e0b64a; }
.tag-warning { color: var(--error); background: var(--error-bg); border-color: var(--error-bg); }
.mail-unsorted { margin: 0 0 6px; color: #c77700; font-size: 0.9rem; }
.mail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; }
.mail-open { padding: 4px 12px; font-size: 0.9rem; text-decoration: none; }
```

with

```css
.mail-tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 0 0 6px; }
.tag { padding: 1px 8px; font-size: 0.8rem; border: 1px solid var(--border); border-radius: 999px; background: var(--bg); }
.tag-training_points { background: #fff4d6; border-color: #e0b64a; }
.mail-unsorted { margin: 0 0 6px; color: #c77700; font-size: 0.9rem; }
.mail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; }
.mail-open { padding: 4px 12px; font-size: 0.9rem; text-decoration: none; }
```

`web/src/main/resources/templates/school/mailbox.html`: one change.

Replace

```html
    <span class="tag tag-count" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag tag-count" th:if="${card.copies <= 1 and card.messages > 1}"
          th:text="|${card.messages} messages|">3 messages</span>
    <span class="tag tag-warning" th:if="${card.losesPoints}">⚠ lose points if absent</span>
  </p>
  <p class="mail-unsorted" th:unless="${card.sorted}">Couldn't sort this email automatically. Use Move to…</p>
  <div class="mail-actions">
```

with

```html
    <span class="tag tag-count" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag tag-count" th:if="${card.copies <= 1 and card.messages > 1}"
          th:text="|${card.messages} messages|">3 messages</span>
  </p>
  <p class="mail-unsorted" th:unless="${card.sorted}">Couldn't sort this email automatically. Use Move to…</p>
  <div class="mail-actions">
```

- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (371 tests). Run: `(cd web && ./mvnw -B test)` → `Tests run: 434, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add contract agent/sla_agent/mail_rules.py agent/tests/test_mail_rules.py web/src
git commit -m "feat(contract): each email's sessions in the upload, saved by the site; no more loses_points

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Finding the times an event takes place, on the laptop

`sessions_in(text, from_day)` finds every session in an email's subject and text (§3.2): each time goes with the dates of its sentence or line, or of the nearest one above; several dates or several times give one session each; sentences about a deadline are skipped; links are ignored. `sort_email` runs it on every email, whatever its categories, so the times are ready when the student moves an email to Event (§3.1); if it fails on one email, that email is uploaded without sessions and nothing it said is logged.

**Files:**
- Modify: `agent/sla_agent/class_changes.py`, `agent/sla_agent/mail_rules.py`
- Test: `agent/tests/test_sessions.py` (new), `agent/tests/test_mail_rules.py`

**Interfaces:**
- Consumes: `MailSession`, `MailItem.sessions` (Task 1); `_dates`, `_time`, `SENTENCE_END`, `URL` (the class-change reader).
- Produces: `class_changes.Session(day, start, end=None)` (a `NamedTuple`); `sessions_in(text, from_day) -> list[Session]`; `class_changes.fold(text)` (moved from `mail_rules`, still importable from there); `MAX_SESSIONS` (10); `mail_rules.sessions_of(email, arrived) -> list[MailSession]`.

- [ ] **Step 1: Write the failing tests**

`agent/tests/test_sessions.py` (new):

```python
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
```

`agent/tests/test_mail_rules.py`: one change.

Replace

```python
# ---- the whole email ------------------------------------------------------------------


def test_the_text_never_leaves_in_the_result():
    item = sort_email(email("Workshop", "UNIQUE-TEXT-7f3a only the laptop may read this"), CONTEXT)

```

with

```python
# ---- the whole email ------------------------------------------------------------------


def test_sessions_are_found_in_every_email_whatever_its_categories():
    item = sort_email(email("Họp lớp", "Họp lớp ngày 30/09/2026, 14h00 - 15h30."), CONTEXT)

    assert item.categories == []
    assert [(s.day, s.start, s.end) for s in item.sessions] == [(date(2026, 9, 30), time(14, 0), time(15, 30))]


def test_an_email_the_session_finder_fails_on_keeps_its_sorting_and_logs_no_text(monkeypatch, caplog):
    def broken(text, from_day):
        raise ValueError(text)

    monkeypatch.setattr(mail_rules, "sessions_in", broken)

    with caplog.at_level(logging.WARNING):
        item = sort_email(email("Workshop ngày 30/9", "Bí mật riêng tư 12345, 14h00."), CONTEXT)

    assert (item.sorted, item.sessions, item.categories) == (True, [], ["event"])
    assert "Bí mật" not in caplog.text and "12345" not in caplog.text


def test_the_text_never_leaves_in_the_result():
    item = sort_email(email("Workshop", "UNIQUE-TEXT-7f3a only the laptop may read this"), CONTEXT)

```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_sessions.py agent/tests/test_mail_rules.py -q`
Expected: FAIL while collecting: `ImportError: cannot import name 'MAX_SESSIONS' from 'sla_agent.class_changes'`.

- [ ] **Step 3: The session finder**

`agent/sla_agent/class_changes.py`: 6 changes.

Change 1: replace

```python
"""Class changes announced by lecturers: online, cancelled and make-up classes, and the dates in a text.
Pure functions.

A sentence that mentions a class, a change word and a date makes a change; each date takes the
nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
```

with

```python
"""Class changes announced by lecturers: online, cancelled and make-up classes; the dates in a text; and the
times an event takes place (sessions). Pure functions.

A sentence that mentions a class, a change word and a date makes a change; each date takes the
nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
```

Change 2: replace

```python
The website reads Blackboard announcements with its Java twin
(web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java); the agent reads lecturers'
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it."""

import re
import unicodedata
```

with

```python
The website reads Blackboard announcements with its Java twin
(web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java); the agent reads lecturers'
emails with this one. contract/samples/class-changes/sentences.json keeps the two in step: both test
suites check every example in it.

Sessions follow docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 3.2."""

import re
import unicodedata
```

Change 3: replace

```python
)


class Announced(NamedTuple):
    kind: str  # "online" / "cancelled" / "makeup"
    day: date  # in Vietnam
    start: time | None = None  # make-up only
    end: time | None = None
    room: str | None = None


def _date(match, posted_day):
```

with

```python
)


# Sessions (mailbox-events 3.2): a time is a start alone, or a start and an end joined by one of these.
SESSION_TIME = re.compile(
    r"(?<![\w/.:,])(?P<hour>\d{1,2})(?:(?:[:.](?=\d{2})|[hg])(?P<minute>\d{2})?|(?=\s*[ap]\.?m\b))"
    r"(?:\s*(?P<ampm>[ap])\.?m\.?)?(?!\w)",
    re.IGNORECASE,
)
SESSION_JOIN = re.compile(r"\s*(?:-|–|—|to|until|đến)\s*", re.IGNORECASE)
# Words of a deadline, compared without accents or letter case. Bare "hạn" and "trước" don't count: "Số lượng
# có hạn" and "có mặt trước 15 phút" sit next to real event times.
DEADLINE_WORDS = ("hạn chót", "hạn đăng ký", "hạn nộp", "thời hạn", "trước ngày", "đăng ký trước", "deadline", "due")
MAX_SESSIONS = 10


def fold(text):
    """Lower case without accents: "Hóa Đơn" -> "hoa don"."""
    text = unicodedata.normalize("NFD", (text or "").replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


DEADLINE = re.compile(r"\b(?:" + "|".join(r"\s+".join(fold(w).split()) for w in DEADLINE_WORDS) + r")\b")


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
```

Change 4: replace

```python
    return min(candidates, key=lambda d: abs(d - posted_day))


def _dates(sentence, posted_day):
    """[(start, end, date)] in `sentence`, from the posting day on, in the order they appear; start and end
    are the date's character positions."""
    found, taken = [], []
    for pattern in DATE_FORMATS:
        for match in pattern.finditer(sentence):
```

with

```python
    return min(candidates, key=lambda d: abs(d - posted_day))


def _dates(sentence, posted_day, keep_past=False):
    """[(start, end, date)] in `sentence`, from the posting day on (or all of them with keep_past), in the order
    they appear; start and end are the date's character positions."""
    found, taken = [], []
    for pattern in DATE_FORMATS:
        for match in pattern.finditer(sentence):
```

Change 5: replace

```python
                continue
            taken.append(match.span())
            day = _date(match, posted_day)
            if day is not None and day >= posted_day:
                found.append((match.start(), match.end(), day))
    return sorted(found)

```

with

```python
                continue
            taken.append(match.span())
            day = _date(match, posted_day)
            if day is not None and (keep_past or day >= posted_day):
                found.append((match.start(), match.end(), day))
    return sorted(found)

```

Change 6: replace

```python
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    return sorted({day for _, _, day in _dates(text, from_day)})

```

with

```python
    text = URL.sub(" ", unicodedata.normalize("NFC", text or ""))
    return sorted({day for _, _, day in _dates(text, from_day)})


def _session_times(sentence, taken):
    """[(start, end)] of every time in `sentence` outside the spans in `taken` (its dates), in order. A start and
    an end joined by "-", "đến", "to" … make one range; the end is dropped when it isn't after the start. In a
    range, a start without AM/PM takes the end's when that keeps it before the end: "1:00 – 2:30 PM"."""
    matches = [m for m in SESSION_TIME.finditer(sentence)
               if not any(m.start() < end and start < m.end() for start, end in taken)]
    found, i = [], 0
    while i < len(matches):
        first, start, end = matches[i], _time(matches[i]), None
        i += 1
        if i < len(matches) and SESSION_JOIN.fullmatch(sentence[first.end():matches[i].start()]):
            second = matches[i]
            end = _time(second)
            i += 1
            if start and end and not first.group("ampm") and second.group("ampm"):
                shifted = _time(first, ampm=second.group("ampm"))
                if shifted and shifted < end:
                    start = shifted
        if start is not None:
            found.append((start, end if end is not None and end > start else None))
    return found


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
        times = _session_times(sentence, [(start, end) for start, end, _ in dates])
        if days:
            above = days
        if not times or not above:
            continue
        pairs = zip(above, times) if len(above) == len(times) else [(d, t) for d in above for t in times]
        found.extend(Session(day, start, end) for day, (start, end) in pairs)
    kept = {}
    for session in found:
        if session.day < from_day:
            continue
        key = (session.day, session.start)
        if key not in kept or (kept[key].end is None and session.end is not None):
            kept[key] = session if key not in kept else kept[key]._replace(end=session.end)
    return sorted(kept.values())[:MAX_SESSIONS]

```

`agent/sla_agent/mail_rules.py`: 4 changes.

Change 1: replace

```python

import logging
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sla_contract.schema import MailClassChange, MailItem

from sla_agent.class_changes import dates_in, read_announcement

log = logging.getLogger(__name__)

```

with

```python

import logging
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sla_contract.schema import MailClassChange, MailItem, MailSession

from sla_agent.class_changes import dates_in, fold, read_announcement, sessions_in

log = logging.getLogger(__name__)

```

Change 2: replace

```python

    courses: tuple = ()  # (course code, course name, lecturer as EduSoft writes it, e.g. "P.Q.Hùng")
    bb_courses: tuple = ()  # (Blackboard course name, course code)


def fold(text):
    """Lower case without accents: "Hóa Đơn" -> "hoa don"."""
    text = unicodedata.normalize("NFD", (text or "").replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def _phrase(words):
```

with

```python

    courses: tuple = ()  # (course code, course name, lecturer as EduSoft writes it, e.g. "P.Q.Hùng")
    bb_courses: tuple = ()  # (Blackboard course name, course code)


def _phrase(words):
```

Change 3: replace

```python
            for a in found][:MAX_CLASS_CHANGES]


def sort_email(email, context):
    """The MailItem for one email. If the rules fail on it, it is uploaded unsorted (sorted=False)."""
    known = dict(key=email.key, entry_id=email.entry_id, thread_id=email.thread_id, received_at=email.received_at,
```

with

```python
            for a in found][:MAX_CLASS_CHANGES]


def sessions_of(email, arrived):
    """The times the email's event or school task takes place (mailbox-events 3.2), found in every email so they
    are ready when the student moves one to Event. [] when the finder fails on it."""
    try:
        return [MailSession(day=s.day, start=s.start, end=s.end)
                for s in sessions_in(email.subject + "\n" + email.text, arrived)]
    except Exception as error:  # the message could quote the email
        log.warning("Couldn't read the times in an email (%s); it is uploaded without them",
                    error.__class__.__name__)
        return []


def sort_email(email, context):
    """The MailItem for one email. If the rules fail on it, it is uploaded unsorted (sorted=False)."""
    known = dict(key=email.key, entry_id=email.entry_id, thread_id=email.thread_id, received_at=email.received_at,
```

Change 4: replace

```python
            categories=categories(email, lecturer),
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

with

```python
            categories=categories(email, lecturer),
            from_lecturer=lecturer,
            dates=dates_in(email.subject + "\n" + email.text, arrived)[:MAX_DATES],
            sessions=sessions_of(email, arrived),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
```

- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_sessions.py agent/tests/test_mail_rules.py -q` → PASS. Then `.venv/Scripts/python.exe -m pytest -q` → PASS (415 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/class_changes.py agent/sla_agent/mail_rules.py agent/tests/test_sessions.py agent/tests/test_mail_rules.py
git commit -m "feat(agent): find the times an event takes place in every email, on the laptop

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Mailbox as compact rows, a bigger Done, and auto-Done when opening

The Mailbox page becomes one row per card (§4.2): colored category labels first, sender, subject (the `sla-mail:` link), tags, date or "Next: …", then Web ↗, Move… and a real ✓ Done button. Box titles show counts and every card is shown. `Mailbox` now takes each email's sessions, so an event or school task is Past once its last session has ended (§4.4). Clicking a subject or Web ↗ runs `mailbox.js`, which posts `opened`: the card is marked opened, and Done too when auto-Done is on, unless it is an event or school task still ahead (§4.3). A tick box saves the setting.

**Files:**
- Create: `web/src/main/resources/db/migration/V20260928_1_3__mail_opened.sql`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSettings.java`, `SchoolMailSettingsRepository.java` (in `school/model/`), `web/src/main/resources/static/js/mailbox.js`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`, `school/model/SchoolMailChoice.java`, `school/pages/MailboxController.java`, `web/src/main/resources/templates/school/mailbox.html`, `web/src/main/resources/static/css/style.css`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`, `school/pages/MailboxPageTest.java`, `school/model/SchoolTablesTest.java`, `core/MigrationTest.java`

**Interfaces:**
- Consumes: `SchoolMailSessionRepository.findOfUser` (Task 1); `VietnamTime.of`, `SchoolFormat` (existing).
- Produces:
  - `Mailbox.Session(day, start, end)` with `startAt()`, `endAt()` (an hour after the start without an end), `Session.ORDER`; `Mailbox.LABELS` (short names); `Mailbox.SESSION_WITHOUT_END` (1 hour).
  - `Mailbox.Card(key, keys, entryId, senderName, subject, receivedAt, categories, fromLecturer, moved, dates, sessions, nextDate, past, sorted, opened, done, messages, copies)` with `trainingPoints()`, `eventLike()`, `doneWhenOpened()`; `sessions` are the sessions that haven't ended, only for event-like cards.
  - `Mailbox.Box(id, title, cards, past, pastTitle)` (no `more`); `Mailbox.build(mails, choices, sessionsByKey, nowInVietnam)`; `EVERYTHING_ELSE_SHOWN` is gone.
  - `SchoolMailChoice.isOpened()`, `open(now)`; `SchoolMailSettings(userId, autoDone)`; `SchoolMailSettingsRepository.autoDone(userId)` (true without a row).
  - `POST /school/mailbox/{key}/opened` → `{"done": true|false}`; `POST /school/mailbox/settings` (`autoDone`); rows are `<li class="mail-row …" id="mail-{key}">` with `a.mail-subject[data-opened]`, `.mail-labels .cat.cat-{category}`, `button.done-button`; the setting is `#auto-done`; the page's `.mailbox` carries `data-csrf-header` and `data-csrf`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`: one change.

Replace

```java
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status", "school_mail_sessions",
                "flyway_schema_history");
    }

```

with

```java
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status", "school_mail_sessions",
                "school_mail_settings",
                "flyway_schema_history");
    }

```

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`: 6 changes.

Change 1: replace

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
```

with

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
```

Change 2: replace

```java

import vn.edu.hcmiu.sla.school.mail.Mailbox.Box;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.View;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
```

with

```java

import vn.edu.hcmiu.sla.school.mail.Mailbox.Box;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.mail.Mailbox.View;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
```

Change 3: replace

```java
class MailboxTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 28); // Mon, in Vietnam
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0);

    /** An email; key and subject are the same short name, e.g. "tcl". Times are hours before NOW. */
    static final class Mail {
```

with

```java
class MailboxTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 28); // Mon, in Vietnam
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // UTC
    static final LocalDateTime NOW_IN_VIETNAM = LocalDateTime.of(2026, 9, 28, 8, 0);

    /** An email; key and subject are the same short name, e.g. "tcl". Times are hours before NOW. */
    static final class Mail {
```

Change 4: replace

```java
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, TODAY);
    }

    static View build(Mail... mails) {
```

with

```java
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, sessions, NOW_IN_VIETNAM);
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
        return build(choices, Map.of(), mails);
    }

    /** A session on the day `daysFromToday` from today, "13:30" to "16:30" (end may be null). */
    static Session session(int daysFromToday, String start, String end) {
        return new Session(TODAY.plusDays(daysFromToday), LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    static View build(Mail... mails) {
```

Change 5: replace

```java
    static SchoolMailChoice done(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.setDone(true, NOW);
        return choice;
    }

```

with

```java
    static SchoolMailChoice done(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.setDone(true, NOW);
        return choice;
    }

    static SchoolMailChoice opened(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.open(NOW);
        return choice;
    }

```

Change 6: replace

```java
    }

    @Test
    void everythingElseShowsTheNewest10() {
        Mail[] mails = new Mail[14];
        for (int i = 0; i < 14; i++) {
            mails[i] = new Mail("m" + i, i + 1);
        }

        Box other = box(build(mails), "other");

        assertThat(other.cards()).hasSize(10);
        assertThat(keys(other.more())).containsExactly("m10", "m11", "m12", "m13");
    }

    @Test
```

with

```java
    }

    @Test
    void everythingElseShowsEveryCard() {
        Mail[] mails = new Mail[14];
        for (int i = 0; i < 14; i++) {
            mails[i] = new Mail("m" + i, i + 1);
        }

        assertThat(box(build(mails), "other").cards()).hasSize(14);
    }

    @Test
    void anEventsSessionsDecideItsNextDate() {
        Card card = build(Map.of(), Map.of("talk", List.of(session(0, "06:00", "07:30"), session(1, "13:30", "16:30"))),
                new Mail("talk", 1, "event").on(0, 1, 5)).card("talk");

        assertThat(card.sessions()).containsExactly(session(1, "13:30", "16:30"));
        assertThat(List.of(card.nextDate(), card.past())).containsExactly(TODAY.plusDays(1), false);
    }

    @Test
    void anEventIsPastOnceEverySessionHasEndedEvenWithALaterDate() {
        View view = build(Map.of(), Map.of("talk", List.of(session(0, "06:00", "07:30"))),
                new Mail("talk", 1, "event").on(0, 5));

        assertThat(keys(box(view, "events").past())).containsExactly("talk");
        assertThat(view.card("talk").sessions()).isEmpty();
    }

    @Test
    void aSessionWithoutAnEndLastsAnHour() {
        Card going = build(Map.of(), Map.of("a", List.of(session(0, "07:30", null))), new Mail("a", 1, "event"))
                .card("a");
        Card over = build(Map.of(), Map.of("b", List.of(session(0, "06:30", null))), new Mail("b", 1, "event"))
                .card("b");

        assertThat(List.of(going.past(), over.past())).containsExactly(false, true);
        assertThat(going.sessions().get(0).endAt()).isEqualTo(TODAY.atTime(8, 30));
    }

    @Test
    void onlyEventsAndSchoolTasksUseTheirSessions() {
        Card invoice = build(Map.of(), Map.of("invoice", List.of(session(1, "09:00", null))),
                new Mail("invoice", 1, "money").on(3)).card("invoice");

        assertThat(invoice.sessions()).isEmpty();
        assertThat(invoice.nextDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void aThreadsSessionsAreKeptOnceWithAnEnd() {
        Card card = build(Map.of(), Map.of("new", List.of(session(1, "13:30", null)),
                        "old", List.of(session(1, "13:30", "16:30"), session(2, "08:00", null))),
                new Mail("new", 1, "event").thread("T"), new Mail("old", 5, "event").thread("T")).card("new");

        assertThat(card.sessions()).containsExactly(session(1, "13:30", "16:30"), session(2, "08:00", null));
    }

    @Test
    void openedComesFromTheNewestEmailSoANewReplyIsUnread() {
        Map<String, SchoolMailChoice> choices = Map.of("first", opened("first"));

        Card before = build(choices, new Mail("first", 30, "class").thread("T")).card("first");
        Card after = build(choices, new Mail("first", 30, "class").thread("T"),
                new Mail("reply", 1, "class").thread("T")).card("reply");

        assertThat(List.of(before.opened(), after.opened())).containsExactly(true, false);
    }

    @Test
    void openingMarksDoneUnlessItIsAnEventOrTaskStillAhead() {
        View view = build(Map.of(), Map.of("talk", List.of(session(2, "13:30", null))),
                new Mail("talk", 1, "event"), new Mail("survey", 2, "school_task").on(4),
                new Mail("over", 3, "event").on(-2), new Mail("undated", 4, "event"), new Mail("invoice", 5, "money").on(4));

        assertThat(List.of("talk", "survey", "over", "undated", "invoice")).map(k -> view.card(k).doneWhenOpened())
                .containsExactly(false, false, true, true, true);
    }

    @Test
    void everyCategoryHasAShortLabel() {
        assertThat(Mailbox.LABELS.keySet()).containsExactlyElementsOf(Mailbox.CATEGORIES.keySet());
        assertThat(Mailbox.LABELS.get("training_points")).isEqualTo("★ Points");
    }

    @Test
```

`web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`: 2 changes.

Change 1: replace

```java

    @Autowired
    UserRepository users;

    Integer userId;

```

with

```java

    @Autowired
    UserRepository users;

    @Autowired
    SchoolMailSettingsRepository settings;

    Integer userId;

```

Change 2: replace

```java
        assertThat(List.of(again.isDone(), again.isMoved())).containsExactly(true, false);
        assertThat(again.getCategories()).isNull();
    }
}

```

with

```java
        assertThat(List.of(again.isDone(), again.isMoved())).containsExactly(true, false);
        assertThat(again.getCategories()).isNull();
    }

    @Test
    void aChoiceRemembersOpeningAndTheSettingIsOnWithoutARow() {
        SchoolMailChoice choice = new SchoolMailChoice(userId, "a".repeat(64), SEPT_28);
        choice.open(SEPT_28);
        db.persist(choice);

        assertThat(List.of(reloaded(choice, choice.getId()).isOpened(), settings.autoDone(userId)))
                .containsExactly(true, true);
        settings.save(new SchoolMailSettings(userId, false));
        assertThat(settings.autoDone(userId)).isFalse();
    }
}

```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java`: 6 changes.

Change 1: replace

```java

        assertThat(html.indexOf("From lecturers")).isLessThan(html.indexOf("School tasks"));
        assertThat(box(html, "lecturers")).contains("Re: Slide bài tập bị thiếu số trang").contains("2 messages")
                .doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("Couldn't sort this email automatically. Use Move to…");
        assertThat(box(html, "events")).contains("★ Training points").contains("Next: Wed 30/09")
                .doesNotContain("lose points");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00");
    }

    @Test
```

with

```java

        assertThat(html.indexOf("From lecturers")).isLessThan(html.indexOf("School tasks"));
        assertThat(box(html, "lecturers")).contains("Re: Slide bài tập bị thiếu số trang").contains("2 messages")
                .contains("<span class=\"count\">(1)</span>").doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("title=\"Couldn't sort this email automatically. Use Move to…\">Not sorted</span>");
        assertThat(box(html, "events")).contains("<span class=\"cat cat-event\">Event</span>")
                .contains("<span class=\"cat cat-training_points\">★ Points</span>").contains("Next: Wed 30/09")
                .doesNotContain("lose points");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00").doesNotContain("Show all")
                .doesNotContain("Open in Outlook");
    }

    @Test
```

Change 2: replace

```java

        String card = box(page(), "events");

        assertThat(card).contains("href=\"sla-mail:00A1AAAA\"");
        assertThat(card).contains("href=\"https://outlook.office.com/mail/\" target=\"_blank\" rel=\"noopener noreferrer\"");
    }

    @Test
```

with

```java

        String card = box(page(), "events");

        assertThat(card).containsPattern("class=\"mail-subject\" href=\"sla-mail:00A1AAAA\"[^>]*"
                + "data-opened=\"/school/mailbox/" + TCL + "/opened\"");
        assertThat(card).contains("href=\"https://outlook.office.com/mail/\" target=\"_blank\" rel=\"noopener noreferrer\"");
        assertThat(card).contains("class=\"done-button\">✓ Done</button>");
    }

    @Test
```

Change 3: replace

```java
        assertThat(box(page(), "money")).contains("Xuất hóa đơn");
    }

    @Test
    void moveToPutsACardWhereTheStudentChoseForEveryEmailOfTheThread() throws Exception {
        inbox(an);
```

with

```java
        assertThat(box(page(), "money")).contains("Xuất hóa đơn");
    }

    String open(String key) throws Exception {
        return mvc.perform(post("/school/mailbox/" + key + "/opened").with(user(an)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void openingAnEmailMarksItDoneWhenAutoDoneIsOn() throws Exception {
        inbox(an);

        assertThat(open(INVOICE)).isEqualTo("{\"done\":true}");

        assertThat(box(page(), "money")).doesNotContain("Xuất hóa đơn");
        assertThat(box(page(), "done")).contains("Done (1)").contains("mail-row is-opened is-done");
    }

    @Test
    void openingAnEventStillAheadOnlyMarksItOpened() throws Exception {
        inbox(an);

        assertThat(open(TCL)).isEqualTo("{\"done\":false}");

        assertThat(box(page(), "events")).containsPattern("id=\"mail-" + TCL + "\"\\s+class=\"mail-row is-opened\"");
    }

    @Test
    void withAutoDoneOffOpeningOnlyMarksItOpened() throws Exception {
        inbox(an);
        mvc.perform(post("/school/mailbox/settings").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox"));

        assertThat(open(INVOICE)).isEqualTo("{\"done\":false}");

        String html = page();
        assertThat(box(html, "money")).contains("class=\"mail-row is-opened\"");
        assertThat(html).containsPattern("id=\"auto-done\" name=\"autoDone\" value=\"true\">");
    }

    @Test
    void autoDoneIsOnUntilTheStudentTurnsItOff() throws Exception {
        inbox(an);

        assertThat(page()).contains("id=\"auto-done\" name=\"autoDone\" value=\"true\" checked=\"checked\"");

        mvc.perform(post("/school/mailbox/settings").with(user(an)).with(csrf()).param("autoDone", "true"));
        assertThat(page()).contains("checked=\"checked\"");
    }

    @Test
    void moveToPutsACardWhereTheStudentChoseForEveryEmailOfTheThread() throws Exception {
        inbox(an);
```

Change 4: replace

```java
                        .param("category1", "school_task").param("category2", "").param("fromLecturer", "false"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + LAB_REPLY));

        assertThat(box(page(), "tasks")).contains("Re: Slide bài tập").contains("School task");
        assertThat(choices.findByUserId(an.id())).extracting(SchoolMailChoice::getMailKey)
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    @Test
```

with

```java
                        .param("category1", "school_task").param("category2", "").param("fromLecturer", "false"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + LAB_REPLY));

        assertThat(box(page(), "tasks")).contains("Re: Slide bài tập").contains(">Task</span>");
        assertThat(choices.findByUserId(an.id())).extracting(SchoolMailChoice::getMailKey)
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    @Test
    void anUnsortedEmailMovedByTheStudentIsNoLongerMarkedNotSorted() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + INVOICE + "/edit").with(user(an)).with(csrf())
                .param("category1", "money").param("category2", ""));

        assertThat(box(page(), "money")).contains("Xuất hóa đơn").doesNotContain("Not sorted");
    }

    @Test
```

Change 5: replace

```java

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an))).andExpect(status().isNotFound());
        assertThat(page()).doesNotContain("Workshop");
    }

```

with

```java

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an))).andExpect(status().isNotFound());
        mvc.perform(post("/school/mailbox/" + TCL + "/opened").with(user(an)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(page()).doesNotContain("Workshop");
    }

```

Change 6: replace

```java
        inbox(an);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an))).andExpect(status().isForbidden());
    }

    @Test
```

with

```java
        inbox(an);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/" + TCL + "/opened").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/settings").with(user(an))).andExpect(status().isForbidden());
    }

    @Test
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest,SchoolTablesTest,MigrationTest')`
Expected: compilation fails: `cannot find symbol` for `class SchoolMailSettings`, `variable LABELS` and the methods `sessions`, `opened`, `doneWhenOpened`, `open` and `isOpened`.

- [ ] **Step 3: The rows, opening, auto-Done and the setting**

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSettings.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The student's Mailbox setting: whether opening an email marks it Done. No row means it does. */
@Entity
@Table(name = "school_mail_settings")
public class SchoolMailSettings {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "auto_done", nullable = false)
    private boolean autoDone;

    protected SchoolMailSettings() {
    }

    public SchoolMailSettings(Integer userId, boolean autoDone) {
        this.userId = userId;
        this.autoDone = autoDone;
    }

    public Integer getUserId() {
        return userId;
    }

    public boolean isAutoDone() {
        return autoDone;
    }

    public void setAutoDone(boolean autoDone) {
        this.autoDone = autoDone;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailSettingsRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMailSettingsRepository extends JpaRepository<SchoolMailSettings, Integer> {

    /** Whether opening an email marks it Done for this user: on unless they turned it off. */
    default boolean autoDone(Integer userId) {
        return findById(userId).map(SchoolMailSettings::isAutoDone).orElse(true);
    }
}
```

`web/src/main/resources/db/migration/V20260928_1_3__mail_opened.sql` (new):

```sql
-- Mailbox, round two: the emails the student opened from Mailbox, and whether opening one marks it Done
-- (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.1 and 4.3).
ALTER TABLE school_mail_choices ADD COLUMN opened BOOLEAN NOT NULL DEFAULT FALSE;

-- No row means auto-Done is on.
CREATE TABLE school_mail_settings (
    user_id INT NOT NULL,
    auto_done BOOLEAN NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_mail_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

`web/src/main/resources/static/js/mailbox.js` (new):

```javascript
/*
 * Mailbox (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.3). Opening an email from its subject or
 * "Web ↗" tells the site, which marks the card opened (and Done when auto-Done is on); the link opens as usual,
 * without waiting. Changing the auto-Done tick box saves it at once.
 */
document.addEventListener("DOMContentLoaded", function () {
  var mailbox = document.querySelector(".mailbox");
  if (!mailbox) {
    return;
  }

  mailbox.querySelectorAll("a[data-opened]").forEach(function (link) {
    link.addEventListener("click", function () {
      var row = link.closest(".mail-row");
      var headers = {};
      headers[mailbox.dataset.csrfHeader] = mailbox.dataset.csrf;
      fetch(link.dataset.opened, { method: "POST", headers: headers, credentials: "same-origin", keepalive: true })
        .then(function (answer) {
          return answer.ok ? answer.json() : null;
        })
        .then(function (result) {
          if (result && row) {
            row.classList.add("is-opened");
            row.classList.toggle("is-done", result.done);
          }
        })
        .catch(function () {
          // Nothing is recorded; the student can still press Done.
        });
    });
  });

  var autoDone = document.getElementById("auto-done");
  if (autoDone) {
    autoDone.addEventListener("change", function () {
      autoDone.form.submit();
    });
  }
});
```

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`: 9 changes.

Change 1: replace

```java
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
```

with

```java
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
```

Change 2: replace

```java

/**
 * What the Mailbox tab shows: emails grouped into cards, cards in boxes, in the order of
 * docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.3. Pure functions.
 */
public final class Mailbox {

```

with

```java

/**
 * What the Mailbox tab shows: emails grouped into cards, cards in boxes, in the order of
 * docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.3, with the sessions, Past and opening
 * rules of 2026-09-28-mailbox-events-design.md, section 4. Pure functions.
 */
public final class Mailbox {

```

Change 3: replace

```java
            "class", "Class", "school_task", "School task", "money", "Money", "event", "Event",
            "training_points", "Training points", "requests_account", "Your requests & account",
            "system_notice", "System notice", "promotion", "Promotion");
    static final Duration SAME_EMAIL_WINDOW = Duration.ofDays(30);
    public static final int EVERYTHING_ELSE_SHOWN = 10;

    private static Map<String, String> orderedNames(String... pairs) {
        Map<String, String> names = new LinkedHashMap<>();
```

with

```java
            "class", "Class", "school_task", "School task", "money", "Money", "event", "Event",
            "training_points", "Training points", "requests_account", "Your requests & account",
            "system_notice", "System notice", "promotion", "Promotion");
    /** The short names on a card's labels. */
    public static final Map<String, String> LABELS = orderedNames(
            "class", "Class", "school_task", "Task", "money", "Money", "event", "Event",
            "training_points", "★ Points", "requests_account", "Requests", "system_notice", "System",
            "promotion", "Promo");
    static final Duration SAME_EMAIL_WINDOW = Duration.ofDays(30);
    /** How long a session without an end lasts. */
    public static final Duration SESSION_WITHOUT_END = Duration.ofHours(1);

    private static Map<String, String> orderedNames(String... pairs) {
        Map<String, String> names = new LinkedHashMap<>();
```

Change 4: replace

```java
        return Collections.unmodifiableMap(names);
    }

    /**
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. nextDate: its earliest date from today on.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, LocalDate nextDate, boolean sorted, boolean done,
            int messages, int copies) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
        }

        /** Every date is over (a card without dates is never past). */
        public boolean past() {
            return !dates.isEmpty() && nextDate == null;
        }

        LocalDate lastDate() {
```

with

```java
        return Collections.unmodifiableMap(names);
    }

    /** One time an event takes place, as the laptop found it. Vietnam time; end may be empty. */
    public record Session(LocalDate day, LocalTime start, LocalTime end) {

        static final Comparator<Session> ORDER = Comparator.comparing(Session::day).thenComparing(Session::start);

        public LocalDateTime startAt() {
            return day.atTime(start);
        }

        /** The end, or an hour after the start when the email gave none. */
        public LocalDateTime endAt() {
            return end != null ? day.atTime(end) : startAt().plus(SESSION_WITHOUT_END);
        }
    }

    /**
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. sessions: an event or school task's sessions that
     * haven't ended (none for other cards). nextDate: the day of the first of them, or else the earliest date
     * from today on. past: every session has ended, or (without sessions) every date is over.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, List<Session> sessions, LocalDate nextDate, boolean past, boolean sorted,
            boolean opened, boolean done, int messages, int copies) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
        }

        /** An event or school task: it has sessions and Join…, and stays in its box when opened while ahead. */
        public boolean eventLike() {
            return Mailbox.eventLike(categories);
        }

        /** With auto-Done on, opening it marks it Done: anything but an event or school task still ahead. */
        public boolean doneWhenOpened() {
            return !(eventLike() && nextDate != null);
        }

        LocalDate lastDate() {
```

Change 5: replace

```java
        }
    }

    /**
     * A box. cards: shown; more: in "Show all" (Everything else only); past: in its closed "Past" list
     * (Events and School tasks only).
     */
    public record Box(String id, String title, List<Card> cards, List<Card> more, List<Card> past,
            String pastTitle) {

        public boolean empty() {
            return cards.isEmpty() && more.isEmpty() && past.isEmpty();
        }
    }

```

with

```java
        }
    }

    /** A box. cards: shown, all of them; past: in its closed "Past" list (Events and School tasks only). */
    public record Box(String id, String title, List<Card> cards, List<Card> past, String pastTitle) {

        public boolean empty() {
            return cards.isEmpty() && past.isEmpty();
        }
    }

```

Change 6: replace

```java
    public record View(List<Box> boxes, List<Card> done) {

        public Card card(String key) {
            return boxes.stream().flatMap(b -> Stream.of(b.cards(), b.more(), b.past()))
                    .flatMap(List::stream).filter(c -> c.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> done.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null));
        }
    }

    /** Lower case, letters and digits only, one space between words: how "the same subject" is compared. */
```

with

```java
    public record View(List<Box> boxes, List<Card> done) {

        public Card card(String key) {
            return boxes.stream().flatMap(b -> Stream.of(b.cards(), b.past()))
                    .flatMap(List::stream).filter(c -> c.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> done.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null));
        }
    }

    static boolean eventLike(List<String> categories) {
        return categories.contains("event") || categories.contains("school_task");
    }

    /** Lower case, letters and digits only, one space between words: how "the same subject" is compared. */
```

Change 7: replace

```java
        return -1;
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, LocalDate today) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
                .filter(c -> c != null && c.isMoved()).findFirst().orElse(null);
        SchoolMailChoice newestChoice = choices.get(newest.getMailKey());
        TreeSet<LocalDate> dates = new TreeSet<>();
        mails.forEach(m -> dates.addAll(m.getDates()));
        LocalDate next = dates.ceiling(today);
        List<String> categories = moved != null && moved.getCategories() != null ? moved.getCategories()
                : newest.getCategories();
        boolean fromLecturer = moved != null && moved.getFromLecturer() != null ? moved.getFromLecturer()
                : newest.isFromLecturer();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), next, newest.isSorted(),
                newestChoice != null && newestChoice.isDone(), mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

with

```java
        return -1;
    }

    /** A card's emails' sessions, each day and start once (one with an end wins), in time order. */
    private static List<Session> sessionsOf(List<SchoolMail> mails, Map<String, List<Session>> sessions) {
        Map<List<Object>, Session> kept = new LinkedHashMap<>();
        for (SchoolMail mail : mails) {
            for (Session s : sessions.getOrDefault(mail.getMailKey(), List.of())) {
                kept.merge(List.of(s.day(), s.start()), s, (old, now) -> old.end() != null ? old : now);
            }
        }
        return kept.values().stream().sorted(Session.ORDER).toList();
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            LocalDateTime now) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
                .filter(c -> c != null && c.isMoved()).findFirst().orElse(null);
        SchoolMailChoice newestChoice = choices.get(newest.getMailKey());
        List<String> categories = moved != null && moved.getCategories() != null ? moved.getCategories()
                : newest.getCategories();
        boolean fromLecturer = moved != null && moved.getFromLecturer() != null ? moved.getFromLecturer()
                : newest.isFromLecturer();
        TreeSet<LocalDate> dates = new TreeSet<>();
        mails.forEach(m -> dates.addAll(m.getDates()));
        List<Session> all = eventLike(categories) ? sessionsOf(mails, sessions) : List.of();
        List<Session> ahead = all.stream().filter(s -> s.endAt().isAfter(now)).toList();
        LocalDate next = all.isEmpty() ? dates.ceiling(now.toLocalDate()) : ahead.isEmpty() ? null : ahead.get(0).day();
        boolean past = all.isEmpty() ? !dates.isEmpty() && next == null : ahead.isEmpty();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

Change 8: replace

```java
        return "other";
    }

    /** The whole tab. mails: newest first; choices by mail key; today in Vietnam. */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices, LocalDate today) {
        Map<String, List<Card>> byBox = new LinkedHashMap<>();
        for (String box : List.of("lecturers", "tasks", "money", "events", "other")) {
            byBox.put(box, new ArrayList<>());
        }
        List<Card> done = new ArrayList<>();
        for (Group group : groups(mails)) {
            Card card = card(group, choices, today);
            (card.done() ? done : byBox.get(boxOf(card))).add(card);
        }
        done.sort(NEWEST_FIRST);
```

with

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

Change 9: replace

```java
        byBox.get("money").sort(NEWEST_FIRST);
        other.sort(NEWEST_FIRST);
        List<Box> boxes = List.of(
                new Box("lecturers", "From lecturers", byBox.get("lecturers"), List.of(), List.of(), null),
                new Box("tasks", "School tasks", current(tasks, SOONEST_FIRST), List.of(), past(tasks), "Past"),
                new Box("money", "Money", byBox.get("money"), List.of(), List.of(), null),
                new Box("events", "Events", current(events, Comparator.comparing((Card c) -> !c.trainingPoints())
                        .thenComparing(SOONEST_FIRST)), List.of(), past(events), "Past events"),
                new Box("other", "Everything else", other.subList(0, Math.min(EVERYTHING_ELSE_SHOWN, other.size())),
                        other.subList(Math.min(EVERYTHING_ELSE_SHOWN, other.size()), other.size()), List.of(), null));
        return new View(boxes, done);
    }

```

with

```java
        byBox.get("money").sort(NEWEST_FIRST);
        other.sort(NEWEST_FIRST);
        List<Box> boxes = List.of(
                new Box("lecturers", "From lecturers", byBox.get("lecturers"), List.of(), null),
                new Box("tasks", "School tasks", current(tasks, SOONEST_FIRST), past(tasks), "Past"),
                new Box("money", "Money", byBox.get("money"), List.of(), null),
                new Box("events", "Events", current(events, Comparator.comparing((Card c) -> !c.trainingPoints())
                        .thenComparing(SOONEST_FIRST)), past(events), "Past events"),
                new Box("other", "Everything else", other, List.of(), null));
        return new View(boxes, done);
    }

```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailChoice.java`: 3 changes.

Change 1: replace

```java

    @Column(nullable = false)
    private boolean done;

    @Convert(converter = CommaLists.Words.class)
    @Column(length = 100)
```

with

```java

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false)
    private boolean opened;

    @Convert(converter = CommaLists.Words.class)
    @Column(length = 100)
```

Change 2: replace

```java
        return done;
    }

    public List<String> getCategories() {
        return categories;
    }
```

with

```java
        return done;
    }

    /** Whether the student opened this email from Mailbox (its subject or "Web ↗"). */
    public boolean isOpened() {
        return opened;
    }

    public List<String> getCategories() {
        return categories;
    }
```

Change 3: replace

```java
        this.updatedAt = now;
    }

    public void move(List<String> categories, boolean fromLecturer, LocalDateTime now) {
        this.categories = categories;
        this.fromLecturer = fromLecturer;
```

with

```java
        this.updatedAt = now;
    }

    public void open(LocalDateTime now) {
        this.opened = true;
        this.updatedAt = now;
    }

    public void move(List<String> categories, boolean fromLecturer, LocalDateTime now) {
        this.categories = categories;
        this.fromLecturer = fromLecturer;
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java`: 6 changes.

Change 1: replace

```java

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
```

with

```java

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
```

Change 2: replace

```java
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

```

with

```java
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

```

Change 3: replace

```java
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done and Move to…. Both only change the app,
 * never the real mailbox. A card of another user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
```

with

```java
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettings;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettingsRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done, Move to… and opening (which marks a card
 * opened, and Done when auto-Done is on). They only change the app, never the real mailbox. A card of another
 * user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
```

Change 4: replace

```java
    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailStatusRepository statuses, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.statuses = statuses;
        this.runs = runs;
    }
```

with

```java
    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailSessionRepository sessions;
    private final SchoolMailSettingsRepository settings;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailSessionRepository sessions, SchoolMailSettingsRepository settings,
            SchoolMailStatusRepository statuses, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.sessions = sessions;
        this.settings = settings;
        this.statuses = statuses;
        this.runs = runs;
    }
```

Change 5: replace

```java
    private Mailbox.View view(Integer userId) {
        Map<String, SchoolMailChoice> byKey = choices.findByUserId(userId).stream()
                .collect(Collectors.toMap(SchoolMailChoice::getMailKey, Function.identity()));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, VietnamTime.date(now()));
    }

    /** The user's card whose newest email has this key, else 404. */
```

with

```java
    private Mailbox.View view(Integer userId) {
        Map<String, SchoolMailChoice> byKey = choices.findByUserId(userId).stream()
                .collect(Collectors.toMap(SchoolMailChoice::getMailKey, Function.identity()));
        Map<String, List<Session>> sessionsByKey = sessions.findOfUser(userId).stream()
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                VietnamTime.of(now()).toLocalDateTime());
    }

    /** The user's card whose newest email has this key, else 404. */
```

Change 6: replace

```java
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList()));
        model.addAttribute("categories", Mailbox.CATEGORIES);
        return "school/mailbox";
    }

    @PostMapping("/{key}/done")
```

with

```java
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList()));
        model.addAttribute("labels", Mailbox.LABELS);
        model.addAttribute("autoDone", settings.autoDone(user.id()));
        return "school/mailbox";
    }

    /**
     * The student opened this card's email from its subject or "Web ↗" (mailbox.js). It is marked opened, and
     * Done too when auto-Done is on, unless it is an event or school task still ahead. Answers {"done": …}.
     */
    @PostMapping("/{key}/opened")
    @ResponseBody
    Map<String, Boolean> opened(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        Card card = card(user.id(), key);
        SchoolMailChoice choice = choice(user.id(), card.key());
        choice.open(now());
        if (settings.autoDone(user.id()) && card.doneWhenOpened()) {
            choice.setDone(true, now());
        }
        choices.save(choice);
        return Map.of("done", choice.isDone());
    }

    @PostMapping("/settings")
    String saveSettings(@AuthenticationPrincipal AppUser user, @RequestParam(defaultValue = "false") boolean autoDone) {
        SchoolMailSettings mine = settings.findById(user.id()).orElseGet(() -> new SchoolMailSettings(user.id(), true));
        mine.setAutoDone(autoDone);
        settings.save(mine);
        return "redirect:/school/mailbox";
    }

    @PostMapping("/{key}/done")
```

`web/src/main/resources/static/css/style.css`: one change.

Replace

```css
.fc .fc-list-event a.event-title { color: inherit; text-decoration: none; }
.fc .fc-list-event.event-cancelled a.event-title { text-decoration: line-through; }

/* School: Mailbox */
.mail-read { margin: 0 0 12px; }
.mail-cards { list-style: none; margin: 0; padding: 0; }
.mail-card { padding: 10px 0; border-top: 1px solid var(--border); }
.mail-card:first-child { border-top: 0; }
.mail-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 2px 12px; }
.mail-sender { font-weight: 600; overflow-wrap: anywhere; }
.mail-when { color: var(--muted); font-variant-numeric: tabular-nums; }
.mail-subject { margin: 2px 0 6px; overflow-wrap: anywhere; }
.mail-tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 0 0 6px; }
.tag { padding: 1px 8px; font-size: 0.8rem; border: 1px solid var(--border); border-radius: 999px; background: var(--bg); }
.tag-training_points { background: #fff4d6; border-color: #e0b64a; }
.mail-unsorted { margin: 0 0 6px; color: #c77700; font-size: 0.9rem; }
.mail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; }
.mail-open { padding: 4px 12px; font-size: 0.9rem; text-decoration: none; }
.mailbox-box details { margin-top: 8px; }
.mailbox-box summary { cursor: pointer; color: var(--accent); }
.field select {
  width: 100%;
  padding: 10px 12px;
```

with

```css
.fc .fc-list-event a.event-title { color: inherit; text-decoration: none; }
.fc .fc-list-event.event-cancelled a.event-title { text-decoration: line-through; }

/* School: Mailbox, one row per card (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.2) */
.container:has(.mailbox) { max-width: 1200px; }
.mail-top { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 4px 16px;
  margin: 0 0 10px; }
.mail-read { margin: 0; }
.mail-setting { margin: 0; font-size: 0.9rem; }
.mail-setting .check { display: flex; gap: 6px; align-items: center; }
.mailbox .card + .card { margin-top: 10px; }
.mailbox-box { padding: 10px 14px 8px; }
.mailbox-box h2 { margin: 0 0 4px; font-size: 1rem; }
.mailbox-box h2 .count { color: var(--muted); font-weight: 400; }
.mail-rows { list-style: none; margin: 0; padding: 0; }
.mail-row {
  display: grid; grid-template-columns: 170px minmax(0, 170px) minmax(0, 1fr) auto 130px 270px;
  align-items: center; column-gap: 12px; padding: 4px 0; border-top: 1px solid var(--border);
}
.mail-row:first-child { border-top: 0; }
.mail-text { display: contents; }
.mail-labels { display: flex; gap: 4px; }
.cat {
  padding: 1px 7px; font-size: 0.78rem; font-weight: 600; white-space: nowrap;
  border: 1px solid transparent; border-radius: 4px;
}
.cat-class { background: #e3edfb; color: #1f4f99; }
.cat-school_task { background: #efe6fb; color: #5b2a9a; }
.cat-money { background: #e2f3e7; color: #1d6b3a; }
.cat-event { background: #dff2f4; color: #0f5f68; }
.cat-training_points { background: #fff1cc; color: #7a5200; border-color: #e0b64a; }
.cat-requests_account { background: #e9edf2; color: #3b4757; }
.cat-system_notice { background: #efefef; color: #555555; }
.cat-promotion { background: #fde7ef; color: #9c2256; }
.cat-other { background: var(--surface); color: var(--muted); border-color: var(--border); }
.cat-unsorted { background: #fff0e0; color: #a24d00; border-color: #f0b57a; cursor: help; }
.mail-sender { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mail-subject { color: var(--text); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mail-subject:hover { color: var(--accent); text-decoration: underline; }
.mail-row.is-opened .mail-subject { color: var(--muted); }
.mail-extra { display: flex; gap: 4px; white-space: nowrap; }
.tag { padding: 0 6px; font-size: 0.75rem; color: var(--muted); border: 1px solid var(--border); border-radius: 999px; }
.mail-when { color: var(--muted); font-size: 0.9rem; white-space: nowrap; text-align: right;
  font-variant-numeric: tabular-nums; }
.mail-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; white-space: nowrap;
  font-size: 0.9rem; }
.mail-actions a { text-decoration: none; }
.mail-actions a:hover { text-decoration: underline; }
.done-button {
  min-height: 32px; padding: 4px 12px; font: inherit; font-weight: 600; color: var(--accent);
  background: var(--surface); border: 1px solid var(--accent); border-radius: 8px; cursor: pointer;
}
.done-button:hover { color: var(--accent-text); background: var(--accent); }
.mail-row.is-done .mail-done, .mail-row:not(.is-done) .mail-undo { display: none; }
.mailbox-box details { margin-top: 6px; }
.mailbox-box summary { cursor: pointer; color: var(--accent); font-size: 0.9rem; }
@media (max-width: 700px) {
  .mail-row {
    grid-template-columns: auto minmax(0, 1fr) auto;
    grid-template-areas: "labels extra when" "text text text" "actions actions actions";
    row-gap: 2px; padding: 6px 0;
  }
  .mail-labels { grid-area: labels; }
  .mail-extra { grid-area: extra; }
  .mail-when { grid-area: when; }
  .mail-text { display: block; grid-area: text; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mail-text .mail-sender::after { content: " · "; font-weight: 400; color: var(--muted); }
  .mail-actions { grid-area: actions; gap: 12px; }
}
.field select {
  width: 100%;
  padding: 10px 12px;
```

`web/src/main/resources/templates/school/mailbox.html`: 2 changes.

Change 1: replace

```html
      <code>sla-agent setup --outlook</code>.</p>
  </section>

  <th:block th:if="${status != null}">
    <p class="muted mail-read" th:text="|Mail read from Outlook ${@schoolFormat.when(status.syncedAt)}|">Mail read from
      Outlook Mon 28/09 07:02</p>
    <p class="status-warning" th:unless="${status.connected}">Outlook was offline when your laptop last read it, so the
      newest mail may be missing. Open Outlook (classic) and check that you're signed in.</p>

    <section class="card mailbox-box" th:each="box : ${view.boxes}" th:id="|box-${box.id}|">
      <h2 th:text="${box.title}">From lecturers</h2>
      <p class="muted" th:if="${box.empty()}">Nothing here.</p>
      <ul class="mail-cards" th:unless="${box.cards.isEmpty()}">
        <!--/* th:replace runs before th:each on one element, so the loop is on a block around it. */-->
        <th:block th:each="card : ${box.cards}"><li
            th:replace="~{school/mailbox :: card(${card}, ${box.id == 'events' or box.id == 'tasks'})}"></li></th:block>
      </ul>
      <details th:unless="${box.more.isEmpty()}">
        <summary th:text="|Show all (${box.cards.size() + box.more.size()})|">Show all (14)</summary>
        <ul class="mail-cards">
          <th:block th:each="card : ${box.more}"><li th:replace="~{school/mailbox :: card(${card}, false)}"></li></th:block>
        </ul>
      </details>
      <details th:unless="${box.past.isEmpty()}">
        <summary th:text="|${box.pastTitle} (${box.past.size()})|">Past events (15)</summary>
        <ul class="mail-cards">
          <th:block th:each="card : ${box.past}"><li th:replace="~{school/mailbox :: card(${card}, false)}"></li></th:block>
        </ul>
      </details>
    </section>
```

with

```html
      <code>sla-agent setup --outlook</code>.</p>
  </section>

  <div class="mailbox" th:if="${status != null}"
       th:attr="data-csrf-header=${_csrf.headerName},data-csrf=${_csrf.token}">
    <div class="mail-top">
      <p class="muted mail-read" th:text="|Mail read from Outlook ${@schoolFormat.when(status.syncedAt)}|">Mail read
        from Outlook Mon 28/09 07:02</p>
      <form class="mail-setting" method="post" th:action="@{/school/mailbox/settings}">
        <label class="check"><input type="checkbox" id="auto-done" name="autoDone" value="true" th:checked="${autoDone}">
          Mark emails as done when I open them</label>
        <noscript><button type="submit" class="link-button">Save</button></noscript>
      </form>
    </div>
    <p class="status-warning" th:unless="${status.connected}">Outlook was offline when your laptop last read it, so the
      newest mail may be missing. Open Outlook (classic) and check that you're signed in.</p>

    <section class="card mailbox-box" th:each="box : ${view.boxes}" th:id="|box-${box.id}|">
      <h2><th:block th:text="${box.title}">From lecturers</th:block> <span class="count"
          th:text="|(${box.cards.size()})|">(3)</span></h2>
      <p class="muted" th:if="${box.empty()}">Nothing here.</p>
      <ul class="mail-rows" th:unless="${box.cards.isEmpty()}">
        <!--/* th:replace runs before th:each on one element, so the loop is on a block around it. */-->
        <th:block th:each="card : ${box.cards}"><li
            th:replace="~{school/mailbox :: row(${card}, ${box.id == 'events' or box.id == 'tasks'})}"></li></th:block>
      </ul>
      <details th:unless="${box.past.isEmpty()}">
        <summary th:text="|${box.pastTitle} (${box.past.size()})|">Past events (15)</summary>
        <ul class="mail-rows">
          <th:block th:each="card : ${box.past}"><li th:replace="~{school/mailbox :: row(${card}, false)}"></li></th:block>
        </ul>
      </details>
    </section>
```

Change 2: replace

```html
    <section class="card mailbox-box" id="box-done">
      <details>
        <summary th:text="|Done (${view.done.size()})|">Done (0)</summary>
        <ul class="mail-cards" th:unless="${view.done.isEmpty()}">
          <th:block th:each="card : ${view.done}"><li th:replace="~{school/mailbox :: card(${card}, false)}"></li></th:block>
        </ul>
      </details>
    </section>
  </th:block>
</main>

<!--/* One card (Mailbox.Card). showNext: Events and School tasks show the next date instead of the time received. */-->
<li th:fragment="card(card, showNext)" class="mail-card" th:id="|mail-${card.key}|">
  <div class="mail-head">
    <span class="mail-sender" th:text="${card.senderName.isEmpty() ? card.subject : card.senderName}">P.CTSV [OSS]</span>
    <span class="mail-when" th:text="${showNext and card.nextDate != null
        ? 'Next: ' + @schoolFormat.dayLabel(card.nextDate) : @schoolFormat.when(card.receivedAt)}">Next: Tue 29/09</span>
  </div>
  <p class="mail-subject" th:text="${card.subject.isEmpty() ? '(no subject)' : card.subject}">[THƯ MỜI] Workshop</p>
  <p class="mail-tags">
    <span th:each="category : ${card.categories}" th:class="|tag tag-${category}|"
          th:text="${(category == 'training_points' ? '★ ' : '') + categories.get(category)}">Event</span>
    <span class="tag tag-count" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag tag-count" th:if="${card.copies <= 1 and card.messages > 1}"
          th:text="|${card.messages} messages|">3 messages</span>
  </p>
  <p class="mail-unsorted" th:unless="${card.sorted}">Couldn't sort this email automatically. Use Move to…</p>
  <div class="mail-actions">
    <a class="button mail-open" th:href="|sla-mail:${card.entryId}|">Open in Outlook</a>
    <a href="https://outlook.office.com/mail/" target="_blank" rel="noopener noreferrer">Outlook on the web ↗</a>
    <form class="inline" method="post" th:unless="${card.done}"
          th:action="@{/school/mailbox/{key}/done(key=${card.key})}"><button type="submit" class="link-button">✓ Done</button></form>
    <form class="inline" method="post" th:if="${card.done}"
          th:action="@{/school/mailbox/{key}/undone(key=${card.key})}"><button type="submit" class="link-button">Undo</button></form>
    <a th:href="@{/school/mailbox/{key}/edit(key=${card.key})}">Move to…</a>
  </div>
</li>

</body>
```

with

```html
    <section class="card mailbox-box" id="box-done">
      <details>
        <summary th:text="|Done (${view.done.size()})|">Done (0)</summary>
        <ul class="mail-rows" th:unless="${view.done.isEmpty()}">
          <th:block th:each="card : ${view.done}"><li th:replace="~{school/mailbox :: row(${card}, false)}"></li></th:block>
        </ul>
      </details>
    </section>
  </div>
  <script th:src="@{/js/mailbox.js}"></script>
</main>

<!--/* One row (Mailbox.Card). showNext: Events and School tasks show the next date instead of the time received.
     Clicking the subject or "Web ↗" opens the email; mailbox.js then tells the site (data-opened). */-->
<li th:fragment="row(card, showNext)" th:id="|mail-${card.key}|"
    th:class="|mail-row${card.opened ? ' is-opened' : ''}${card.done ? ' is-done' : ''}|">
  <span class="mail-labels">
    <span th:each="category : ${card.categories}" th:class="|cat cat-${category}|" th:text="${labels.get(category)}">Event</span>
    <span class="cat cat-other" th:if="${card.sorted and card.categories.isEmpty()}">Other</span>
    <span class="cat cat-unsorted" th:if="${!card.sorted and !card.moved}"
          title="Couldn't sort this email automatically. Use Move to…">Not sorted</span>
  </span>
  <span class="mail-text">
    <span class="mail-sender" th:text="${card.senderName.isEmpty() ? '—' : card.senderName}"
          th:title="${card.senderName}">P.CTSV [OSS]</span>
    <a class="mail-subject" th:href="|sla-mail:${card.entryId}|" th:title="${card.subject}"
       th:attr="data-opened=@{/school/mailbox/{key}/opened(key=${card.key})}"
       th:text="${card.subject.isEmpty() ? '(no subject)' : card.subject}">[THƯ MỜI] Workshop</a>
  </span>
  <span class="mail-extra">
    <span class="tag" th:if="${card.copies > 1}" th:text="|sent ${card.copies}×|">sent 2×</span>
    <span class="tag" th:if="${card.copies <= 1 and card.messages > 1}" th:text="|${card.messages} messages|">3
      messages</span>
  </span>
  <span class="mail-when" th:text="${showNext and card.nextDate != null
      ? 'Next: ' + @schoolFormat.dayLabel(card.nextDate) : @schoolFormat.when(card.receivedAt)}">Next: Tue 29/09</span>
  <span class="mail-actions">
    <a href="https://outlook.office.com/mail/" target="_blank" rel="noopener noreferrer"
       th:attr="data-opened=@{/school/mailbox/{key}/opened(key=${card.key})}">Web ↗</a>
    <a th:href="@{/school/mailbox/{key}/edit(key=${card.key})}">Move…</a>
    <form class="inline mail-done" method="post" th:action="@{/school/mailbox/{key}/done(key=${card.key})}"><button
        type="submit" class="done-button">✓ Done</button></form>
    <form class="inline mail-undo" method="post" th:action="@{/school/mailbox/{key}/undone(key=${card.key})}"><span
        class="muted">Done ✓</span> <button type="submit" class="done-button">Undo</button></form>
  </span>
</li>

</body>
```

- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest,SchoolTablesTest,MigrationTest')` → `Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 448, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat(web): Mailbox as compact rows with the category first, a bigger Done, and auto-Done when opening

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Join… and the Conflict / No conflict marks

Each event-like card lists its upcoming sessions under its row, each marked against the timetable of its day (§4.5): classes as the Timetable shows them (online and make-up classes count, cancelled ones don't), exams (90 minutes without a length) and the student's other joined events; back-to-back is free; an empty timetable is free. **Join…** opens a page with those sessions to tick, a session to add by hand and an optional place (§4.6). Joined sessions live in their own table, which a sync never touches.

**Files:**
- Create: `web/src/main/resources/db/migration/V20260928_1_4__mail_joined.sql`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailJoined.java`, `SchoolMailJoinedRepository.java` (in `school/model/`), `web/src/main/java/vn/edu/hcmiu/sla/school/mail/Conflicts.java`, `MailSessions.java` (in `school/mail/`), `web/src/main/java/vn/edu/hcmiu/sla/school/pages/JoinForm.java`, `web/src/main/resources/templates/school/mailbox-join.html`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`, `school/pages/MailboxController.java`, `web/src/main/resources/templates/school/mailbox.html`, `web/src/main/resources/static/css/style.css`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/mail/ConflictsTest.java`, `school/mail/MailSessionsTest.java`, `school/pages/JoinPageTest.java` (all new), `school/sync/IngestTest.java`, `core/MigrationTest.java`

**Interfaces:**
- Consumes: `Mailbox.Session`, `Card.eventLike()`, `Card.sessions()`, `Card.keys()` (Task 3); `Schedule.itemsBetween` and `Schedule.Item` (existing); `SchoolTestData.course`, `lecturerEmail`, `emailChange`, `save` (existing).
- Produces:
  - `SchoolMailJoined(userId, mailKey, day, start, end, title, place, trainingPoints, byHand, createdAt)` with getters; `SchoolMailJoinedRepository.findByUserIdAndDayBetweenOrderByDayAscStartAsc(userId, from, to)`, `findByUserIdAndMailKeyInOrderByDayAscStartAsc(userId, keys)`.
  - `Conflicts.Busy(name, start, end)`, `Conflicts.Mark(with)` with `conflict()` and `text()` ("✓ No conflict", "⚠ Conflict: X", "⚠ Conflict: X + n"), `Conflicts.of(session, busy)`.
  - `MailSessions.Line(session, found, joined, mark)` with `when()` ("Tue 29/09 13:00–14:00", "Thu 01/10 from 14:00") and `id()` ("2026-09-29T13:30"); `MailSessions.lines(userId, cards, nowInVietnam)` (by card key), `lines(userId, card, nowInVietnam)`, `joinedOf(userId, keys)`, `upcomingJoined(userId, keys, nowInVietnam)`.
  - `Card.suggested()` (moved to Event or School task by the student; the rules gave it neither).
  - `GET`/`POST /school/mailbox/{key}/join` (fields `sessions`, `day`, `start`, `end`, `place`), `POST /school/mailbox/{key}/leave`; both 404 for a card that isn't event-like; `MailboxController.PAST_DAY`, `NO_START`, `END_BEFORE_START`, `TOO_MANY`, `PLACE_TOO_LONG`, `MAX_JOINED` (10).

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/ConflictsTest.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.mail.Conflicts.Busy;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;

/** Conflict or No conflict for one session (spec 2026-09-28-mailbox-events-design.md, section 4.5). */
class ConflictsTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    static Session session(String start, String end) {
        return new Session(DAY, LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    static Busy busy(String name, String start, String end) {
        return new Busy(name, DAY.atTime(LocalTime.parse(start)), DAY.atTime(LocalTime.parse(end)));
    }

    static String mark(Session session, Busy... busy) {
        return Conflicts.of(session, List.of(busy)).text();
    }

    @Test
    void overlappingTimesConflict() {
        assertThat(mark(session("13:00", "14:00"), busy("Web Application", "13:30", "15:00")))
                .isEqualTo("⚠ Conflict: Web Application");
        assertThat(Conflicts.of(session("13:00", "14:00"), List.of(busy("Web Application", "12:00", "16:00")))
                .conflict()).isTrue();
    }

    @Test
    void backToBackIsNoConflict() {
        assertThat(mark(session("13:00", "14:00"), busy("Physics 4", "14:00", "15:30"), busy("Lab", "11:30", "13:00")))
                .isEqualTo("✓ No conflict");
    }

    @Test
    void aSessionWithoutAnEndLastsAnHour() {
        assertThat(mark(session("13:00", null), busy("Physics 4", "13:59", "15:00"))).startsWith("⚠ Conflict");
        assertThat(mark(session("13:00", null), busy("Physics 4", "14:00", "15:00"))).isEqualTo("✓ No conflict");
    }

    @Test
    void moreClashesAreCountedAndTheFirstIsNamed() {
        assertThat(mark(session("13:00", "15:00"), busy("Final exam: Physics 4", "13:40", "15:10"),
                busy("Web Application", "12:30", "14:00"), busy("Talkshow B", "14:30", "16:00")))
                .isEqualTo("⚠ Conflict: Web Application + 2");
    }

    @Test
    void anEmptyTimetableOrAnotherDayIsNoConflict() {
        assertThat(mark(session("13:00", "14:00"))).isEqualTo("✓ No conflict");
        assertThat(mark(session("13:00", "14:00"), new Busy("Web Application", DAY.plusDays(1).atTime(13, 0),
                DAY.plusDays(1).atTime(14, 0)))).isEqualTo("✓ No conflict");
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailSessionsTest.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.mail.MailSessions.Line;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;

/**
 * An event's sessions against the timetable as the Timetable page shows it: classes with their changes, exams and
 * other joined events (spec 2026-09-28-mailbox-events-design.md, sections 4.5 and 4.6). Times in comments are
 * Vietnam time; the rows are saved in UTC.
 */
@SpringBootTest
@Transactional
class MailSessionsTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // UTC: Mon 28/09 08:00 in Vietnam
    static final LocalDateTime NOW_IN_VIETNAM = LocalDateTime.of(2026, 9, 28, 8, 0);
    static final LocalDate TUE = LocalDate.of(2026, 9, 29);
    static final String TALK = "a".repeat(64);
    static final String OTHER = "f".repeat(64);

    @Autowired
    EntityManager db;

    @Autowired
    MailSessions mailSessions;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void aTimetable() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        // Tue 29/09 13:00-15:30 and Wed 30/09 08:00-10:00
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 9, 29, 6, 0), LocalDateTime.of(2026, 9, 29, 8, 30), "A2.401"));
        data.course(an, "PH012IU", "Physics 4",
                new Meeting(LocalDateTime.of(2026, 9, 30, 1, 0), LocalDateTime.of(2026, 9, 30, 3, 0), "A1.309"));
    }

    static Session at(LocalDate day, String start, String end) {
        return new Session(day, LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    /** An event email with these sessions, as its Mailbox card. */
    Card event(AppUser who, String key, List<String> categories, Session... sessions) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1", null, NOW.minusDays(1), "P.CTSV [OSS]", "oss@hcmiu.edu.vn",
                "Workshop", categories, false, List.of(), true, null);
        db.persist(mail);
        db.flush();
        return Mailbox.build(List.of(mail), Map.of(), Map.of(key, Arrays.asList(sessions)), NOW_IN_VIETNAM).card(key);
    }

    Card event(Session... sessions) {
        return event(an, TALK, List.of("event"), sessions);
    }

    List<String> marks(Card card) {
        return mailSessions.lines(an.id(), card, NOW_IN_VIETNAM).stream().map(line -> line.mark().text()).toList();
    }

    void join(String key, LocalDate day, String start, String end, String title) {
        db.persist(new SchoolMailJoined(an.id(), key, day, LocalTime.parse(start), end == null ? null : LocalTime.parse(end),
                title, null, false, false, NOW));
        db.flush();
    }

    @Test
    void aSessionDuringAClassConflictsWithIt() {
        assertThat(marks(event(at(TUE, "14:00", "16:00"), at(TUE, "15:30", "17:00"))))
                .containsExactly("⚠ Conflict: Web Application Development", "✓ No conflict");
    }

    @Test
    void anOnlineClassStillCountsAndACancelledOneDoesNot() {
        data.save(data.emailChange(data.emailChange(data.lecturerEmail(an, "e".repeat(64), NOW.minusDays(3), null),
                "IT093IU", "online", TUE, null, null, null), "PH012IU", "cancelled", TUE.plusDays(1), null, null, null));

        assertThat(marks(event(at(TUE, "14:00", null), at(TUE.plusDays(1), "08:30", "09:30"))))
                .containsExactly("⚠ Conflict: Web Application Development", "✓ No conflict");
    }

    @Test
    void aMakeUpClassCounts() {
        LocalDate saturday = TUE.plusDays(4);
        data.save(data.emailChange(data.lecturerEmail(an, "e".repeat(64), NOW.minusDays(3), null), "IT093IU", "makeup",
                saturday, LocalTime.of(8, 0), LocalTime.of(10, 0), "A2.401"));

        assertThat(marks(event(at(saturday, "09:00", null)))).containsExactly("⚠ Conflict: Web Application Development");
    }

    @Test
    void examsCountWithTheirLengthOrNinetyMinutes() {
        // Thu 01/10 09:00 without a length, Fri 02/10 13:00 for 120 minutes
        db.persist(new SchoolExam(an.id(), "20261", "MA026IU", "Probability", "final", LocalDateTime.of(2026, 10, 1, 2, 0),
                null, "A2.101", null));
        db.persist(new SchoolExam(an.id(), "20261", "PH012IU", "Physics 4", "midterm", LocalDateTime.of(2026, 10, 2, 6, 0),
                120, "A1.309", null));
        db.flush();

        assertThat(marks(event(at(TUE.plusDays(2), "10:20", null), at(TUE.plusDays(2), "10:30", null),
                at(TUE.plusDays(3), "14:30", null))))
                .containsExactly("⚠ Conflict: Final exam: Probability", "✓ No conflict", "⚠ Conflict: Midterm exam: Physics 4");
    }

    @Test
    void anotherJoinedEventCountsButTheEmailsOwnDoesNot() {
        join(OTHER, TUE, "17:00", "18:00", "Talkshow B");
        join(TALK, TUE, "17:30", null, "Workshop");

        List<Line> lines = mailSessions.lines(an.id(), event(at(TUE, "17:30", null)), NOW_IN_VIETNAM);

        assertThat(lines).extracting(line -> line.mark().text()).containsExactly("⚠ Conflict: Talkshow B");
        assertThat(lines).extracting(Line::joined).containsExactly(true);
    }

    @Test
    void withoutATimetableEverySessionIsFree() {
        AppUser binh = data.user("binh@example.com");

        Card card = event(binh, TALK, List.of("event"), at(TUE, "14:00", "16:00"));

        assertThat(mailSessions.lines(binh.id(), card, NOW_IN_VIETNAM)).extracting(line -> line.mark().text())
                .containsExactly("✓ No conflict");
    }

    @Test
    void sessionsAddedByHandShowWithTheFoundOnesButNotThoseOver() {
        join(TALK, TUE.plusDays(5), "18:00", "20:00", "Workshop");
        join(TALK, TUE.minusDays(2), "18:00", "20:00", "Workshop");

        List<Line> lines = mailSessions.lines(an.id(), event(at(TUE, "15:30", null)), NOW_IN_VIETNAM);

        assertThat(lines).extracting(Line::when, Line::found, Line::joined).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Tue 29/09 from 15:30", true, false),
                org.assertj.core.groups.Tuple.tuple("Sun 04/10 18:00–20:00", false, true));
        assertThat(lines.get(0).id()).isEqualTo("2026-09-29T15:30");
    }

    @Test
    void onlyEventsAndSchoolTasksHaveSessions() {
        Card invoice = event(an, OTHER, List.of("money"), at(TUE, "14:00", null));

        assertThat(mailSessions.lines(an.id(), invoice, NOW_IN_VIETNAM)).isEmpty();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/JoinPageTest.java` (new):

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSession;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;

/**
 * Join…: an event's sessions with their Conflict / No conflict marks, joining some, adding one by hand, leaving
 * (spec 2026-09-28-mailbox-events-design.md, sections 4.2, 4.5 and 4.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class JoinPageTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam
    static final LocalDate TUE = LocalDate.of(2026, 9, 29);
    static final String TALK = "a".repeat(64);
    static final String INVOICE = "b".repeat(64);
    static final String NOTICE = "c".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    SchoolMailJoinedRepository joined;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anInboxAndATimetable() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        clock.set(NOW);
        inbox(an);
        // Tue 29/09 13:00-15:30
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 9, 29, 6, 0), LocalDateTime.of(2026, 9, 29, 8, 30), "A2.401"));
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    SchoolMail mail(AppUser who, String key, String subject, List<String> categories) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), null, NOW.minusDays(1),
                "P.CTSV [OSS]", "oss@hcmiu.edu.vn", subject, categories, false, List.of(), true, null);
        db.persist(mail);
        return mail;
    }

    void inbox(AppUser who) {
        SchoolMail talk = mail(who, TALK, "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                List.of("event", "training_points"));
        talk.getSessions().add(new SchoolMailSession(talk, TUE, LocalTime.of(14, 0), LocalTime.of(16, 0)));
        talk.getSessions().add(new SchoolMailSession(talk, TUE.plusDays(2), LocalTime.of(13, 30), null));
        mail(who, INVOICE, "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", List.of("money"));
        SchoolMail notice = mail(who, NOTICE, "Thông báo họp lớp", List.of());
        notice.getSessions().add(new SchoolMailSession(notice, TUE.plusDays(4), LocalTime.of(9, 0), null));
        db.persist(new SchoolMailStatus(who.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
        db.flush();
    }

    String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(an))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    MockHttpServletRequestBuilder save(String key) {
        return post("/school/mailbox/" + key + "/join").with(user(an)).with(csrf());
    }

    List<String> saved() {
        return joined.findAll().stream().map(j -> j.getDay() + " " + j.getStart() + "-" + j.getEnd() + " "
                + j.getPlace() + " " + j.isTrainingPoints() + " " + j.isByHand()).toList();
    }

    @Test
    void theJoinPageMarksEachSession() throws Exception {
        String html = html(get("/school/mailbox/" + TALK + "/join"));

        assertThat(html).contains("<legend>Sessions</legend>").contains("Tue 29/09 14:00–16:00")
                .contains("⚠ Conflict: Web Application Development").contains("Thu 01/10 from 13:30")
                .contains("✓ No conflict").contains("value=\"2026-09-29T14:00\"").doesNotContain("Leave event");
    }

    @Test
    void theMailboxShowsEachEventsSessionsAndAJoinLink() throws Exception {
        String html = html(get("/school/mailbox"));

        String events = MailboxPageTest.box(html, "events");
        assertThat(events).contains("Tue 29/09 14:00–16:00").contains("⚠ Conflict: Web Application Development")
                .contains("href=\"/school/mailbox/" + TALK + "/join\">Join…</a>");
        assertThat(MailboxPageTest.box(html, "money")).doesNotContain("Join…");
    }

    @Test
    void joiningSavesTheTickedSessionsAndShowsThemAsJoined() throws Exception {
        mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30").param("place", "Hall A2"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-10-01 13:30-null Hall A2 true false");
        SchoolMailJoined row = joined.findAll().get(0);
        assertThat(row.getTitle()).isEqualTo("[THƯ MỜI] Workshop “Từ giảng đường tới công sở”");
        assertThat(MailboxPageTest.box(html(get("/school/mailbox")), "events"))
                .contains("<span class=\"mark mark-joined\">Joined</span>");
        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("value=\"2026-10-01T13:30\" checked=\"checked\"")
                .contains("value=\"Hall A2\"").contains("Leave event");
    }

    @Test
    void aSessionCanBeAddedByHand() throws Exception {
        mvc.perform(save(TALK).param("day", "2026-10-05").param("start", "18:00").param("end", "20:00"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-10-05 18:00-20:00 null true true");
        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("Mon 05/10 18:00–20:00")
                .contains("added by you");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-09-27 | 18:00 | ''    | ''  | " + MailboxController.PAST_DAY,
            "2026-10-05 | ''    | ''    | ''  | " + MailboxController.NO_START,
            "2026-10-05 | 18:00 | 17:00 | ''  | " + MailboxController.END_BEFORE_START,
            "''         | ''    | ''    | 101 | " + MailboxController.PLACE_TOO_LONG,
    })
    void badSessionsAreRefusedWithAMessage(String day, String start, String end, String place, String message)
            throws Exception {
        String html = mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30").param("day", day)
                        .param("start", start).param("end", end).param("place", place.isEmpty() ? "" : "x".repeat(101)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(message.replace("'", "&#39;"));
        assertThat(saved()).isEmpty();
    }

    @Test
    void oneEmailHasAtMostTenJoinedSessions() throws Exception {
        MockHttpServletRequestBuilder request = save(TALK).param("sessions", "2026-09-29T14:00", "2026-10-01T13:30");
        for (int day = 5; day < 14; day++) {
            db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 10, day), LocalTime.of(18, 0), null,
                    "Workshop", null, true, true, NOW));
            request.param("sessions", "2026-10-" + String.format("%02d", day) + "T18:00");
        }
        db.flush();

        String html = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MailboxController.TOO_MANY);
        assertThat(joined.count()).isEqualTo(9);
    }

    @Test
    void leavingRemovesTheSessionsAheadButKeepsThoseOver() throws Exception {
        db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 9, 27), LocalTime.of(18, 0), null,
                "Workshop", null, true, true, NOW));
        db.persist(new SchoolMailJoined(an.id(), TALK, TUE.plusDays(2), LocalTime.of(13, 30), null, "Workshop", null,
                true, false, NOW));
        db.flush();

        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-09-27 18:00-null null true true");
    }

    @Test
    void anEmailMovedToEventSuggestsTheTimesFoundInIt() throws Exception {
        mvc.perform(post("/school/mailbox/" + NOTICE + "/edit").with(user(an)).with(csrf())
                .param("category1", "event").param("category2", ""));

        String html = html(get("/school/mailbox/" + NOTICE + "/join"));

        assertThat(html).contains("<legend>Found in this email</legend>").contains("Sat 03/10 from 09:00")
                .doesNotContain("checked=\"checked\"");
    }

    @Test
    void trainingPointsFollowMoveTo() throws Exception {
        mvc.perform(post("/school/mailbox/" + TALK + "/edit").with(user(an)).with(csrf())
                .param("category1", "event").param("category2", ""));

        mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30"));

        assertThat(saved()).containsExactly("2026-10-01 13:30-null null false false");
    }

    @Test
    void onlyEventsAndSchoolTasksCanBeJoined() throws Exception {
        mvc.perform(get("/school/mailbox/" + INVOICE + "/join").with(user(an))).andExpect(status().isNotFound());
        mvc.perform(save(INVOICE).param("day", "2026-10-05").param("start", "18:00")).andExpect(status().isNotFound());
    }

    @Test
    void someoneElsesEventIs404() throws Exception {
        AppUser binh = data.user("binh@example.com");

        mvc.perform(get("/school/mailbox/" + TALK + "/join").with(user(binh))).andExpect(status().isNotFound());
        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(binh)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void joiningAndLeavingNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/school/mailbox/" + TALK + "/join").with(user(an)).param("sessions", "2026-10-01T13:30"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(an))).andExpect(status().isForbidden());
        assertThat(saved()).isEmpty();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`: one change.

Replace

```java
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status", "school_mail_sessions",
                "school_mail_settings",
                "flyway_schema_history");
    }

```

with

```java
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status", "school_mail_sessions",
                "school_mail_settings", "school_mail_joined",
                "flyway_schema_history");
    }

```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`: 3 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
```

Change 2: replace

```java

    @Autowired
    SchoolMailSessionRepository mailSessions;

    @Autowired
    SchoolMailChoiceRepository mailChoices;
```

with

```java

    @Autowired
    SchoolMailSessionRepository mailSessions;

    @Autowired
    SchoolMailJoinedRepository mailJoined;

    @Autowired
    SchoolMailChoiceRepository mailChoices;
```

Change 3: replace

```java
    }

    @Test
    void anotherUsersMailIsLeftAlone() {
        Integer other = makeUser("binh@example.com");
        syncOutlook(other, ok(outlookPayload()));
```

with

```java
    }

    @Test
    void joinedSessionsStayAfterASyncEvenWhenTheirEmailIsGone() {
        syncOutlook(userId, ok(outlookPayload()));
        mailJoined.save(new SchoolMailJoined(userId, KEY_2, LocalDate.of(2026, 10, 2), LocalTime.of(8, 0), null,
                "Workshop", "Hall A2", true, false, LocalDateTime.of(2026, 9, 28, 1, 0)));
        Map<String, Object> data = outlookPayload();
        list(data, "emails").subList(1, 3).clear();

        syncOutlook(userId, ok(data));

        assertThat(mailJoined.findAll()).extracting(SchoolMailJoined::getMailKey).containsExactly(KEY_2);
    }

    @Test
    void anotherUsersMailIsLeftAlone() {
        Integer other = makeUser("binh@example.com");
        syncOutlook(other, ok(outlookPayload()));
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='ConflictsTest,MailSessionsTest,JoinPageTest,IngestTest,MigrationTest')`
Expected: compilation fails: `cannot find symbol` for `class SchoolMailJoined`, `class Line` and `variable TOO_MANY`.

- [ ] **Step 3: The marks, the Join page and the joined sessions**

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Conflicts.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;

/**
 * Whether an event's session clashes with the timetable (docs/superpowers/specs/2026-09-28-mailbox-events-design.md,
 * section 4.5). Pure functions; times are Vietnam wall-clock times.
 */
public final class Conflicts {

    private Conflicts() {
    }

    /** Something on the timetable: a class, an exam or another joined event, by the name its mark shows. */
    public record Busy(String name, LocalDateTime start, LocalDateTime end) {
    }

    /** A session's mark: the names of what it clashes with, in time order. None is "No conflict". */
    public record Mark(List<String> with) {

        public boolean conflict() {
            return !with.isEmpty();
        }

        /** "✓ No conflict", "⚠ Conflict: Web Application" or "⚠ Conflict: Web Application + 1". */
        public String text() {
            if (with.isEmpty()) {
                return "✓ No conflict";
            }
            return "⚠ Conflict: " + with.get(0) + (with.size() > 1 ? " + " + (with.size() - 1) : "");
        }
    }

    /** A session clashes with what its time overlaps; back-to-back (one ends as the other starts) doesn't. */
    public static Mark of(Session session, List<Busy> busy) {
        return new Mark(busy.stream()
                .filter(b -> session.startAt().isBefore(b.end()) && b.start().isBefore(session.endAt()))
                .sorted(Comparator.comparing(Busy::start))
                .map(Busy::name)
                .toList());
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/MailSessions.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Conflicts.Busy;
import vn.edu.hcmiu.sla.school.mail.Conflicts.Mark;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.schedule.Schedule;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * The sessions shown under an event's row and on its Join page: the ones the laptop found and the ones the student
 * joined, each marked Conflict / No conflict against the timetable
 * (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, sections 4.2, 4.5 and 4.6).
 */
@Service
public class MailSessions {

    static final Duration EXAM_WITHOUT_LENGTH = Duration.ofMinutes(90);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** One session of a card: found in its email and/or joined, with its mark. */
    public record Line(Session session, boolean found, boolean joined, Mark mark) {

        /** "Tue 29/09 13:00–14:00", or "Thu 01/10 from 14:00" without an end. */
        public String when() {
            String start = CLOCK.format(session.start());
            return VietnamTime.dayLabel(session.day()) + " "
                    + (session.end() != null ? start + "–" + CLOCK.format(session.end()) : "from " + start);
        }

        /** How the Join form names it: "2026-09-29T13:30". */
        public String id() {
            return session.day() + "T" + CLOCK.format(session.start());
        }
    }

    private final Schedule schedule;
    private final SchoolMailJoinedRepository joined;

    public MailSessions(Schedule schedule, SchoolMailJoinedRepository joined) {
        this.schedule = schedule;
        this.joined = joined;
    }

    static Session session(SchoolMailJoined row) {
        return new Session(row.getDay(), row.getStart(), row.getEnd());
    }

    /** The student's joined sessions of one card's emails, in time order. */
    public List<SchoolMailJoined> joinedOf(Integer userId, Collection<String> keys) {
        return joined.findByUserIdAndMailKeyInOrderByDayAscStartAsc(userId, keys);
    }

    /** The student's joined sessions of one card's emails that haven't ended at `now` (Vietnam time). */
    public List<SchoolMailJoined> upcomingJoined(Integer userId, Collection<String> keys, LocalDateTime now) {
        return joinedOf(userId, keys).stream().filter(row -> session(row).endAt().isAfter(now)).toList();
    }

    /** Each event-like card's sessions that haven't ended at `now` (Vietnam time), by card key. */
    @Transactional(readOnly = true)
    public Map<String, List<Line>> lines(Integer userId, List<Card> cards, LocalDateTime now) {
        Map<String, Map<List<Object>, Line>> byCard = new LinkedHashMap<>();
        LocalDate first = null;
        LocalDate last = null;
        for (Card card : cards) {
            if (!card.eventLike()) {
                continue;
            }
            Map<List<Object>, Line> lines = new LinkedHashMap<>();
            for (Session s : card.sessions()) {
                lines.put(List.of(s.day(), s.start()), new Line(s, true, false, null));
            }
            for (SchoolMailJoined row : joinedOf(userId, card.keys())) {
                Session s = session(row);
                if (s.endAt().isAfter(now)) {
                    Line found = lines.get(List.of(s.day(), s.start()));
                    lines.put(List.of(s.day(), s.start()),
                            new Line(found != null ? found.session() : s, found != null, true, null));
                }
            }
            byCard.put(card.key(), lines);
            for (List<Object> key : lines.keySet()) {
                LocalDate day = (LocalDate) key.get(0);
                first = first == null || day.isBefore(first) ? day : first;
                last = last == null || day.isAfter(last) ? day : last;
            }
        }
        Map<String, List<Line>> result = new LinkedHashMap<>();
        if (first == null) {
            return result;
        }
        List<Busy> timetable = timetable(userId, first, last);
        List<SchoolMailJoined> allJoined = joined.findByUserIdAndDayBetweenOrderByDayAscStartAsc(userId, first, last);
        for (Card card : cards) {
            Map<List<Object>, Line> lines = byCard.get(card.key());
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<Busy> busy = new ArrayList<>(timetable);
            for (SchoolMailJoined row : allJoined) {
                if (!card.keys().contains(row.getMailKey())) {
                    Session s = session(row);
                    busy.add(new Busy(row.getTitle(), s.startAt(), s.endAt()));
                }
            }
            result.put(card.key(), lines.values().stream()
                    .sorted((a, b) -> Session.ORDER.compare(a.session(), b.session()))
                    .map(l -> new Line(l.session(), l.found(), l.joined(), Conflicts.of(l.session(), busy)))
                    .toList());
        }
        return result;
    }

    /** One card's lines, as {@link #lines(Integer, List, LocalDateTime)} gives them. */
    public List<Line> lines(Integer userId, Card card, LocalDateTime now) {
        return lines(userId, List.of(card), now).getOrDefault(card.key(), List.of());
    }

    /**
     * Classes and exams on the Vietnam days [first, last], as the Timetable shows them: cancelled classes, classes
     * without a time and joined events (counted apart, without the card's own) are left out.
     */
    private List<Busy> timetable(Integer userId, LocalDate first, LocalDate last) {
        List<Busy> busy = new ArrayList<>();
        List<Item> items = schedule.itemsBetween(userId, VietnamTime.dayStart(first),
                VietnamTime.dayStart(last.plusDays(1)));
        for (Item item : items) {
            if (item.allDay() || "cancelled".equals(item.change()) || "event".equals(item.kind())) {
                continue;
            }
            LocalDateTime start = VietnamTime.of(item.startAt()).toLocalDateTime();
            LocalDateTime end = item.endAt() != null ? VietnamTime.of(item.endAt()).toLocalDateTime()
                    : start.plus(EXAM_WITHOUT_LENGTH);
            busy.add(new Busy(item.label() != null ? item.label() + ": " + item.title() : item.title(), start, end));
        }
        return busy;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailJoined.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An event session the student joined from Mailbox. Day and times are Vietnam time; end may be empty. The
 * email's subject is copied in as the title, so it stays in the Timetable even when the email is gone.
 */
@Entity
@Table(name = "school_mail_joined")
public class SchoolMailJoined {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(name = "session_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time", nullable = false)
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 100)
    private String place;

    @Column(name = "training_points", nullable = false)
    private boolean trainingPoints;

    @Column(name = "by_hand", nullable = false)
    private boolean byHand;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    protected SchoolMailJoined() {
    }

    public SchoolMailJoined(Integer userId, String mailKey, LocalDate day, LocalTime start, LocalTime end, String title,
            String place, boolean trainingPoints, boolean byHand, LocalDateTime createdAt) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.day = day;
        this.start = start;
        this.end = end;
        this.title = title;
        this.place = place;
        this.trainingPoints = trainingPoints;
        this.byHand = byHand;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getMailKey() {
        return mailKey;
    }

    public LocalDate getDay() {
        return day;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public String getTitle() {
        return title;
    }

    public String getPlace() {
        return place;
    }

    public boolean isTrainingPoints() {
        return trainingPoints;
    }

    public boolean isByHand() {
        return byHand;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailJoinedRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

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

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/JoinForm.java` (new):

```java
package vn.edu.hcmiu.sla.school.pages;

import java.util.ArrayList;
import java.util.List;

/**
 * Join…: the sessions ticked (by MailSessions.Line.id, e.g. "2026-09-29T13:30"), one session added by hand (day,
 * start and an optional end; day "" means none), and an optional place.
 */
public class JoinForm {

    private List<String> sessions = new ArrayList<>();
    private String day = "";
    private String start = "";
    private String end = "";
    private String place = "";

    public JoinForm() {
    }

    JoinForm(List<String> sessions, String place) {
        this.sessions = new ArrayList<>(sessions);
        this.place = place;
    }

    public List<String> getSessions() {
        return sessions;
    }

    public void setSessions(List<String> sessions) {
        this.sessions = sessions == null ? new ArrayList<>() : sessions;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day == null ? "" : day.strip();
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start == null ? "" : start.strip();
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end == null ? "" : end.strip();
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = place == null ? "" : place.strip();
    }
}
```

`web/src/main/resources/db/migration/V20260928_1_4__mail_joined.sql` (new):

```sql
-- Mailbox, round two: the event sessions the student joined. A sync never touches them, so they stay even when
-- their email is gone (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.1 and 4.6).
CREATE TABLE school_mail_joined (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    session_day DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NULL,
    title VARCHAR(500) NOT NULL,
    place VARCHAR(100) NULL,
    training_points BOOLEAN NOT NULL,
    by_hand BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key, session_day, start_time),
    CONSTRAINT fk_school_mail_joined_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

`web/src/main/resources/templates/school/mailbox-join.html` (new):

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Join… · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Join…</h1>
  <nav th:replace="~{school/fragments :: subnav('mailbox')}"></nav>

  <section class="card join-card">
    <p><strong th:text="${card.subject.isEmpty() ? '(no subject)' : card.subject}">[THƯ MỜI] Workshop</strong><br>
      <span class="muted" th:text="${card.senderName}">P.CTSV [OSS]</span></p>
    <p class="field-error" th:if="${error != null}" th:text="${error}">Choose a day that isn't over yet.</p>

    <form method="post" th:action="@{/school/mailbox/{key}/join(key=${card.key})}" th:object="${form}">
      <fieldset class="join-sessions">
        <legend th:text="${card.suggested ? 'Found in this email' : 'Sessions'}">Sessions</legend>
        <p class="muted" th:if="${lines.isEmpty()}">No times found in this email. Add one below.</p>
        <label class="check join-line" th:each="line : ${lines}">
          <input type="checkbox" name="sessions" th:value="${line.id}" th:checked="${form.sessions.contains(line.id)}">
          <span th:text="${line.when}">Tue 29/09 13:00–14:00</span>
          <span th:class="${line.mark.conflict ? 'mark mark-conflict' : 'mark mark-free'}"
                th:text="${line.mark.text}">✓ No conflict</span>
          <span class="muted" th:unless="${line.found}">added by you</span>
        </label>
      </fieldset>
      <fieldset class="join-add">
        <legend>Add a session</legend>
        <div class="join-add-fields">
          <label>Day <input type="date" th:field="*{day}"></label>
          <label>Start <input type="time" th:field="*{start}"></label>
          <label>End (optional) <input type="time" th:field="*{end}"></label>
        </div>
      </fieldset>
      <div class="field">
        <label for="place">Place (optional)</label>
        <input id="place" type="text" maxlength="100" th:field="*{place}" placeholder="e.g. Hall A2">
      </div>
      <div class="form-actions">
        <button type="submit" class="button">Save</button>
        <a th:href="@{/school/mailbox}">Cancel</a>
      </div>
    </form>

    <form method="post" th:action="@{/school/mailbox/{key}/leave(key=${card.key})}" class="mail-automatic"
          th:if="${anyJoined}">
      <button type="submit" class="link-button danger">Leave event</button>
      <span class="muted">(its sessions leave your Timetable)</span>
    </form>
  </section>
</main>
</body>
</html>
```

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`: 2 changes.

Change 1: replace

```java
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. sessions: an event or school task's sessions that
     * haven't ended (none for other cards). nextDate: the day of the first of them, or else the earliest date
     * from today on. past: every session has ended, or (without sessions) every date is over.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, List<Session> sessions, LocalDate nextDate, boolean past, boolean sorted,
            boolean opened, boolean done, int messages, int copies) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
```

with

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

Change 2: replace

```java
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

with

```java
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies(), eventLike(categories) && !eventLike(newest.getCategories()));
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java`: 6 changes.

Change 1: replace

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

```

with

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.stream.Collectors;

```

Change 2: replace

```java
import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettings;
```

with

```java
import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.MailSessions;
import vn.edu.hcmiu.sla.school.mail.MailSessions.Line;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettings;
```

Change 3: replace

```java
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done, Move to… and opening (which marks a card
 * opened, and Done when auto-Done is on). They only change the app, never the real mailbox. A card of another
 * user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
public class MailboxController {

    static final String CATEGORY_ERROR = "Choose a first category, and a different second one or none.";

    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailSessionRepository sessions;
    private final SchoolMailSettingsRepository settings;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailSessionRepository sessions, SchoolMailSettingsRepository settings,
            SchoolMailStatusRepository statuses, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.sessions = sessions;
        this.settings = settings;
        this.statuses = statuses;
        this.runs = runs;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Mailbox.View view(Integer userId) {
```

with

```java
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done, Move to…, opening (which marks a card
 * opened, and Done when auto-Done is on) and Join… for events. They only change the app, never the real mailbox.
 * A card of another user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
public class MailboxController {

    static final String CATEGORY_ERROR = "Choose a first category, and a different second one or none.";
    static final String PAST_DAY = "Choose a day that isn't over yet.";
    static final String NO_START = "Give the new session a start time.";
    static final String END_BEFORE_START = "The end must be after the start.";
    static final String TOO_MANY = "One email can have at most 10 joined sessions.";
    static final String PLACE_TOO_LONG = "The place can be at most 100 characters.";
    static final int MAX_JOINED = 10;
    static final int MAX_PLACE = 100;

    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailSessionRepository sessions;
    private final SchoolMailSettingsRepository settings;
    private final SchoolMailJoinedRepository joined;
    private final MailSessions mailSessions;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailSessionRepository sessions, SchoolMailSettingsRepository settings,
            SchoolMailJoinedRepository joined, MailSessions mailSessions, SchoolMailStatusRepository statuses,
            SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.sessions = sessions;
        this.settings = settings;
        this.joined = joined;
        this.mailSessions = mailSessions;
        this.statuses = statuses;
        this.runs = runs;
    }

    /** Now in UTC, as stored. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Now in Vietnam (wall clock), as sessions are written. */
    private LocalDateTime nowInVietnam() {
        return VietnamTime.of(now()).toLocalDateTime();
    }

    private Mailbox.View view(Integer userId) {
```

Change 4: replace

```java
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                VietnamTime.of(now()).toLocalDateTime());
    }

    /** The user's card whose newest email has this key, else 404. */
```

with

```java
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                nowInVietnam());
    }

    /** The user's card whose newest email has this key, else 404. */
```

Change 5: replace

```java
        return card;
    }

    private SchoolMailChoice choice(Integer userId, String key) {
        return choices.findByUserIdAndMailKey(userId, key).orElseGet(() -> new SchoolMailChoice(userId, key, now()));
    }

    @GetMapping
    String mailbox(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("view", view(user.id()));
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList()));
```

with

```java
        return card;
    }

    /** The user's event or school-task card with this key, else 404. */
    private Card eventCard(Integer userId, String key) {
        Card card = card(userId, key);
        if (!card.eventLike()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return card;
    }

    private SchoolMailChoice choice(Integer userId, String key) {
        return choices.findByUserIdAndMailKey(userId, key).orElseGet(() -> new SchoolMailChoice(userId, key, now()));
    }

    @GetMapping
    String mailbox(@AuthenticationPrincipal AppUser user, Model model) {
        Mailbox.View view = view(user.id());
        model.addAttribute("view", view);
        model.addAttribute("lines", mailSessions.lines(user.id(),
                view.boxes().stream().flatMap(b -> b.cards().stream()).toList(), nowInVietnam()));
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList()));
```

Change 6: replace

```java
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @PostMapping("/{key}/automatic")
    String automatic(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
```

with

```java
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @GetMapping("/{key}/join")
    String join(@AuthenticationPrincipal AppUser user, @PathVariable String key, Model model) {
        Card card = eventCard(user.id(), key);
        List<Line> lines = mailSessions.lines(user.id(), card, nowInVietnam());
        String place = mailSessions.joinedOf(user.id(), card.keys()).stream().map(SchoolMailJoined::getPlace)
                .filter(Objects::nonNull).findFirst().orElse("");
        return joinPage(model, card, lines, new JoinForm(lines.stream().filter(Line::joined).map(Line::id).toList(),
                place), null);
    }

    private String joinPage(Model model, Card card, List<Line> lines, JoinForm form, String error) {
        model.addAttribute("card", card);
        model.addAttribute("lines", lines);
        model.addAttribute("anyJoined", lines.stream().anyMatch(Line::joined));
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        return "school/mailbox-join";
    }

    /**
     * Saves the ticked sessions and the one added by hand in place of the card's joined sessions that haven't
     * ended; sessions already over stay in the Timetable.
     */
    @PostMapping("/{key}/join")
    String saveJoin(@AuthenticationPrincipal AppUser user, @PathVariable String key,
            @ModelAttribute("form") JoinForm form, Model model, RedirectAttributes redirect) {
        Card card = eventCard(user.id(), key);
        LocalDateTime now = nowInVietnam();
        List<Line> lines = mailSessions.lines(user.id(), card, now);
        List<SchoolMailJoined> rows = new ArrayList<>();
        String place = form.getPlace().isEmpty() ? null : form.getPlace();
        String title = card.subject().isEmpty() ? "(no subject)" : card.subject();
        for (Line line : lines) {
            if (form.getSessions().contains(line.id())) {
                rows.add(new SchoolMailJoined(user.id(), card.key(), line.session().day(), line.session().start(),
                        line.session().end(), title, place, card.trainingPoints(), !line.found(), now()));
            }
        }
        List<SchoolMailJoined> upcoming = mailSessions.upcomingJoined(user.id(), card.keys(), now);
        List<Integer> upcomingIds = upcoming.stream().map(SchoolMailJoined::getId).toList();
        List<SchoolMailJoined> over = mailSessions.joinedOf(user.id(), card.keys()).stream()
                .filter(row -> !upcomingIds.contains(row.getId())).toList();
        String error = form.getPlace().length() > MAX_PLACE ? PLACE_TOO_LONG : null;
        if (error == null && !form.getDay().isEmpty()) {
            error = addByHand(form, now.toLocalDate(), over, rows, user.id(), card, title, place);
        }
        if (error == null && rows.size() > MAX_JOINED) {
            error = TOO_MANY;
        }
        if (error != null) {
            return joinPage(model, card, lines, form, error);
        }
        joined.deleteAll(upcoming);
        joined.flush();
        joined.saveAll(rows);
        Flash.success(redirect, rows.isEmpty() ? "You left this event. It is no longer in your Timetable."
                : "Joined. It is in your Timetable now.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    /** The session added by hand, added to rows unless it is already there or over; or what is wrong with it. */
    private String addByHand(JoinForm form, LocalDate today, List<SchoolMailJoined> over, List<SchoolMailJoined> rows,
            Integer userId, Card card, String title, String place) {
        LocalDate day;
        LocalTime start;
        LocalTime end;
        try {
            day = LocalDate.parse(form.getDay());
        } catch (DateTimeParseException error) {
            return PAST_DAY;
        }
        if (day.isBefore(today)) {
            return PAST_DAY;
        }
        try {
            start = form.getStart().isEmpty() ? null : LocalTime.parse(form.getStart());
            end = form.getEnd().isEmpty() ? null : LocalTime.parse(form.getEnd());
        } catch (DateTimeParseException error) {
            return NO_START;
        }
        if (start == null) {
            return NO_START;
        }
        if (end != null && !end.isAfter(start)) {
            return END_BEFORE_START;
        }
        LocalTime startTime = start;
        if (Stream.concat(rows.stream(), over.stream())
                .noneMatch(r -> r.getDay().equals(day) && r.getStart().equals(startTime))) {
            rows.add(new SchoolMailJoined(userId, card.key(), day, start, end, title, place, card.trainingPoints(),
                    true, now()));
        }
        return null;
    }

    /** Removes the card's joined sessions that haven't ended; those already over stay in the Timetable. */
    @PostMapping("/{key}/leave")
    String leave(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = eventCard(user.id(), key);
        joined.deleteAll(mailSessions.upcomingJoined(user.id(), card.keys(), nowInVietnam()));
        Flash.success(redirect, "You left this event. It is no longer in your Timetable.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @PostMapping("/{key}/automatic")
    String automatic(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
```

`web/src/main/resources/static/css/style.css`: 2 changes.

Change 1: replace

```css
}
.done-button:hover { color: var(--accent-text); background: var(--accent); }
.mail-row.is-done .mail-done, .mail-row:not(.is-done) .mail-undo { display: none; }
.mailbox-box details { margin-top: 6px; }
.mailbox-box summary { cursor: pointer; color: var(--accent); font-size: 0.9rem; }
@media (max-width: 700px) {
```

with

```css
}
.done-button:hover { color: var(--accent-text); background: var(--accent); }
.mail-row.is-done .mail-done, .mail-row:not(.is-done) .mail-undo { display: none; }
.mail-sessions { grid-column: 2 / -1; font-size: 0.85rem; color: var(--muted); }
.mark { font-weight: 600; }
.mark-conflict { color: var(--error); }
.mark-free { color: #1d6b3a; }
.mark-joined { padding: 0 6px; color: #1d6b3a; background: #e2f3e7; border-radius: 4px; }
.join-card { max-width: 560px; margin: 0 auto; }
.join-sessions, .join-add { margin: 0 0 14px; padding: 0; border: 0; }
.join-sessions legend, .join-add legend { margin-bottom: 6px; font-weight: 600; }
.join-line { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 4px 0; }
.join-add-fields { display: flex; flex-wrap: wrap; gap: 8px 12px; }
.join-add-fields label { display: flex; flex-direction: column; gap: 2px; font-size: 0.9rem; }
.join-add-fields input {
  padding: 6px 8px; font: inherit; color: var(--text); background: var(--bg);
  border: 1px solid var(--border); border-radius: 8px;
}
.mailbox-box details { margin-top: 6px; }
.mailbox-box summary { cursor: pointer; color: var(--accent); font-size: 0.9rem; }
@media (max-width: 700px) {
```

Change 2: replace

```css
  .mail-text { display: block; grid-area: text; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mail-text .mail-sender::after { content: " · "; font-weight: 400; color: var(--muted); }
  .mail-actions { grid-area: actions; gap: 12px; }
}
.field select {
  width: 100%;
```

with

```css
  .mail-text { display: block; grid-area: text; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mail-text .mail-sender::after { content: " · "; font-weight: 400; color: var(--muted); }
  .mail-actions { grid-area: actions; gap: 12px; }
  .mail-sessions { grid-column: 1 / -1; }
}
.field select {
  width: 100%;
```

`web/src/main/resources/templates/school/mailbox.html`: one change.

Replace

```html
    <a href="https://outlook.office.com/mail/" target="_blank" rel="noopener noreferrer"
       th:attr="data-opened=@{/school/mailbox/{key}/opened(key=${card.key})}">Web ↗</a>
    <a th:href="@{/school/mailbox/{key}/edit(key=${card.key})}">Move…</a>
    <form class="inline mail-done" method="post" th:action="@{/school/mailbox/{key}/done(key=${card.key})}"><button
        type="submit" class="done-button">✓ Done</button></form>
    <form class="inline mail-undo" method="post" th:action="@{/school/mailbox/{key}/undone(key=${card.key})}"><span
        class="muted">Done ✓</span> <button type="submit" class="done-button">Undo</button></form>
  </span>
</li>

</body>
```

with

```html
    <a href="https://outlook.office.com/mail/" target="_blank" rel="noopener noreferrer"
       th:attr="data-opened=@{/school/mailbox/{key}/opened(key=${card.key})}">Web ↗</a>
    <a th:href="@{/school/mailbox/{key}/edit(key=${card.key})}">Move…</a>
    <a th:if="${card.eventLike}" th:href="@{/school/mailbox/{key}/join(key=${card.key})}">Join…</a>
    <form class="inline mail-done" method="post" th:action="@{/school/mailbox/{key}/done(key=${card.key})}"><button
        type="submit" class="done-button">✓ Done</button></form>
    <form class="inline mail-undo" method="post" th:action="@{/school/mailbox/{key}/undone(key=${card.key})}"><span
        class="muted">Done ✓</span> <button type="submit" class="done-button">Undo</button></form>
  </span>
  <!--/* th:if runs before th:with on one element, so the sessions are looked up on a block around it. */-->
  <th:block th:with="found=${lines == null ? null : lines.get(card.key)}"><span class="mail-sessions"
      th:if="${found != null and !found.isEmpty()}"><th:block th:each="line, i : ${found}"><span
      class="session"><th:block th:text="${line.when}">Tue 29/09 13:00–14:00</th:block>
    <span th:if="${line.joined}" class="mark mark-joined">Joined</span><span th:unless="${line.joined}"
        th:class="${line.mark.conflict ? 'mark mark-conflict' : 'mark mark-free'}" th:text="${line.mark.text}">✓ No
      conflict</span></span><th:block th:unless="${i.last}"> · </th:block></th:block></span></th:block>
</li>

</body>
```

- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='ConflictsTest,MailSessionsTest,JoinPageTest,IngestTest,MigrationTest')` → `Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 477, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat(web): Join… for events, each session marked Conflict or No conflict against the timetable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Joined events in the Timetable, the calendar and Overview

`Schedule` adds the student's joined sessions as items of kind `event` (§4.7): "Event" or "★ Training points" before the title, the place as the room, a link to the email in Mailbox, an hour long without an end, green. Class changes never apply to them. The calendar feed, the Timetable page and Overview's Today and Tomorrow show them with no further change; the Overview item shows the place and "See email", and the Timetable legend gains "Joined event".

**Files:**
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/schedule/Schedule.java`, `web/src/main/resources/templates/school/fragments.html`, `timetable.html` (in `templates/school/`), `web/src/main/resources/static/css/style.css`, `web/src/main/resources/static/js/timetable.js`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`, `school/pages/SchoolPagesTest.java`

**Interfaces:**
- Consumes: `SchoolMailJoinedRepository.findByUserIdAndDayBetweenOrderByDayAscStartAsc` (Task 4); `ClassChanges.Source.email` (existing).
- Produces: `Schedule` takes `SchoolMailJoinedRepository` as its last constructor argument; `Schedule.Item` of kind `"event"` (code null, label "Event" or "★ Training points", room = place, source = the email); `Schedule.JOINED_WITHOUT_END` (1 hour); CSS `--event-color`, `.item-event`, `.fc .event-event`, `.legend-event`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java`: 4 changes.

Change 1: replace

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
```

with

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

```

with

```java
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

```

Change 3: replace

```java
        assertThat(item.group(1)).contains("<a href=\"/school/courses/" + courseId + "\">See announcement</a>");
    }

    // ---- Timetable, exams, tuition --------------------------------------------------------

    @Test
```

with

```java
        assertThat(item.group(1)).contains("<a href=\"/school/courses/" + courseId + "\">See announcement</a>");
    }

    @Test
    void theOverviewShowsAnEventJoinedForToday() throws Exception {
        db.persist(new SchoolMailJoined(an.id(), "a".repeat(64), LocalDate.of(2026, 9, 29), LocalTime.of(18, 0), null,
                "Talkshow B", "Hall A2", false, false, LocalDateTime.of(2026, 9, 28, 1, 0)));
        db.flush();
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30)); // Tue 07:30 in Vietnam

        String html = page("/school");

        Matcher item = Pattern.compile("<li class=\"item item-event\">(.*?)</li>", Pattern.DOTALL).matcher(html);
        assertThat(item.find()).isTrue();
        assertThat(item.group(1)).contains("18:00–19:00", "<strong>Event:</strong>", "Talkshow B")
                .containsPattern("Hall A2 · <a\\s+href=\"/school/mailbox#mail-" + "a".repeat(64) + "\">See email</a>");
    }

    // ---- Timetable, exams, tuition --------------------------------------------------------

    @Test
```

Change 4: replace

```java

    @Test
    void theTimetableLegendExplainsTheNewColours() throws Exception {
        assertThat(page("/school/timetable")).contains("Online / make-up", "Cancelled");
    }

    @Test
```

with

```java

    @Test
    void theTimetableLegendExplainsTheNewColours() throws Exception {
        assertThat(page("/school/timetable")).contains("Online / make-up", "Cancelled", "Joined event");
    }

    @Test
```

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`: 3 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;

/**
 * The Timetable's calendar feed: Java twin of the feed tests in tests/test_school_schedule_pages.py,
```

with

```java
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;

/**
 * The Timetable's calendar feed: Java twin of the feed tests in tests/test_school_schedule_pages.py,
```

Change 2: replace

```java
        return data.save(course);
    }

    // ---- Classes and exams ------------------------------------------------------------

    @Test
```

with

```java
        return data.save(course);
    }

    void join(AppUser who, LocalDate day, LocalTime start, LocalTime end, String place, boolean points) {
        db.persist(new SchoolMailJoined(who.id(), "a".repeat(64), day, start, end, "[THƯ MỜI] Workshop A", place,
                points, false, LocalDateTime.of(2026, 9, 28, 1, 0)));
        db.flush();
    }

    // ---- Classes and exams ------------------------------------------------------------

    @Test
```

Change 3: replace

```java
                          "classNames": ["event-class"],
                          "extendedProps": {"kind": "class", "code": "IT093IU", "room": "A2.508"}}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
```

with

```java
                          "classNames": ["event-class"],
                          "extendedProps": {"kind": "class", "code": "IT093IU", "room": "A2.508"}}]
                        """, JsonCompareMode.STRICT));
    }

    // ---- Events joined from Mailbox (spec 2026-09-28-mailbox-events-design.md, 4.7) ----

    @Test
    void aJoinedEventIsGreenInTheCalendarAndLinksToItsEmail() throws Exception {
        join(an, LocalDate.of(2026, 9, 29), LocalTime.of(14, 0), LocalTime.of(16, 0), "Hall A2", true);

        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END)
                        .with(user(an)))
                .andExpect(content().json("""
                        [{"title": "★ Training points: [THƯ MỜI] Workshop A",
                          "start": "2026-09-29T14:00:00",
                          "end": "2026-09-29T16:00:00",
                          "classNames": ["event-event"],
                          "url": "/school/mailbox#mail-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "extendedProps": {"kind": "event", "code": null, "room": "Hall A2"}}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void aJoinedSessionWithoutAnEndLastsAnHourNextToAClass() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        join(an, LocalDate.of(2026, 9, 29), LocalTime.of(9, 0), null, null, false);

        List<Map<String, Object>> week = nextWeek();

        assertThat(week).extracting(e -> e.get("title")).containsExactly("Web Application Development",
                "Event: [THƯ MỜI] Workshop A");
        assertThat(week.get(1)).containsEntry("start", "2026-09-29T09:00:00").containsEntry("end", "2026-09-29T10:00:00");
    }

    @Test
    void someoneElsesJoinedEventsAreNeverInMyCalendar() throws Exception {
        join(data.user("binh@example.com"), LocalDate.of(2026, 9, 29), LocalTime.of(14, 0), null, null, false);

        assertThat(nextWeek()).isEmpty();
    }

    @Test
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='CalendarFeedTest,SchoolPagesTest')`
Expected: `Tests run: 63, Failures: 4`: `CalendarFeedTest.aJoinedEventIsGreenInTheCalendarAndLinksToItsEmail`, `CalendarFeedTest.aJoinedSessionWithoutAnEndLastsAnHourNextToAClass`, `SchoolPagesTest.theOverviewShowsAnEventJoinedForToday` and `SchoolPagesTest.theTimetableLegendExplainsTheNewColours`.

- [ ] **Step 3: Joined sessions in Schedule, and how they look**

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/Schedule.java`: 4 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Emailed;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Emailed;
```

Change 2: replace

```java
    public static final Map<String, String> EXAM_LABELS = Map.of(
            "final", "Final exam", "midterm", "Midterm exam", "other", "Exam");
    static final Duration DEFAULT_CLASS_LENGTH = Duration.ofMinutes(90); // a make-up of a course with no known classes

    /**
     * A class or an exam on the timetable. Times are UTC; endAt may be empty. label: "Final exam" for exams.
     * change: online / cancelled / makeup, from a Blackboard announcement or a lecturer's email, with source the
     * app's page where it was announced. allDay: a make-up class announced without a time.
     */
    public record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room,
            String label, String change, Source source, boolean allDay) {
```

with

```java
    public static final Map<String, String> EXAM_LABELS = Map.of(
            "final", "Final exam", "midterm", "Midterm exam", "other", "Exam");
    static final Duration DEFAULT_CLASS_LENGTH = Duration.ofMinutes(90); // a make-up of a course with no known classes
    static final Duration JOINED_WITHOUT_END = Duration.ofHours(1); // an event session whose email gave no end

    /**
     * A class, an exam or a joined event on the timetable. Times are UTC; endAt may be empty. label: "Final exam"
     * for exams, "Event" or "★ Training points" for events (whose code is empty and room the place typed on Join).
     * change: online / cancelled / makeup, from a Blackboard announcement or a lecturer's email, with source the
     * app's page where it was announced (for an event, its email in Mailbox). allDay: a make-up class announced
     * without a time.
     */
    public record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room,
            String label, String change, Source source, boolean allDay) {
```

Change 3: replace

```java
    private final SchoolBbAnnouncementRepository announcements;
    private final SchoolBbAssignmentRepository assignments;
    private final SchoolMailChangeRepository mailChanges;

    public Schedule(SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolCourseRepository courses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments,
            SchoolMailChangeRepository mailChanges) {
        this.meetings = meetings;
        this.exams = exams;
        this.courses = courses;
        this.announcements = announcements;
        this.assignments = assignments;
        this.mailChanges = mailChanges;
    }

    /** Classes and exams starting in [startUtc, endUtc), with announced changes, in time order. */
    @Transactional(readOnly = true)
    public List<Item> itemsBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> items = new ArrayList<>();
```

with

```java
    private final SchoolBbAnnouncementRepository announcements;
    private final SchoolBbAssignmentRepository assignments;
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailJoinedRepository joined;

    public Schedule(SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolCourseRepository courses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments,
            SchoolMailChangeRepository mailChanges, SchoolMailJoinedRepository joined) {
        this.meetings = meetings;
        this.exams = exams;
        this.courses = courses;
        this.announcements = announcements;
        this.assignments = assignments;
        this.mailChanges = mailChanges;
        this.joined = joined;
    }

    /** Classes, exams and joined events starting in [startUtc, endUtc), with announced changes, in time order. */
    @Transactional(readOnly = true)
    public List<Item> itemsBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> items = new ArrayList<>();
```

Change 4: replace

```java
            items.add(new Item("exam", e.getStartAt(), end, e.getCourseCode(), e.getCourseName(), e.getRoom(),
                    EXAM_LABELS.getOrDefault(e.getExamType(), "Exam"), null, null, false));
        }
        return withChanges(items, announcedChanges(userId), timetableCourses(userId), startUtc, endUtc);
    }

    /** The items of one Vietnam day. */
```

with

```java
            items.add(new Item("exam", e.getStartAt(), end, e.getCourseCode(), e.getCourseName(), e.getRoom(),
                    EXAM_LABELS.getOrDefault(e.getExamType(), "Exam"), null, null, false));
        }
        List<Item> result = new ArrayList<>(withChanges(items, announcedChanges(userId), timetableCourses(userId),
                startUtc, endUtc));
        result.addAll(joinedEvents(userId, startUtc, endUtc));
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
        return result;
    }

    /**
     * The event sessions the student joined from Mailbox, starting in [startUtc, endUtc)
     * (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 4.7). Class changes never apply to them.
     */
    private List<Item> joinedEvents(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> events = new ArrayList<>();
        for (SchoolMailJoined row : joined.findByUserIdAndDayBetweenOrderByDayAscStartAsc(userId,
                VietnamTime.date(startUtc), VietnamTime.date(endUtc))) {
            LocalDateTime startAt = VietnamTime.utc(row.getDay(), row.getStart());
            LocalDateTime endAt = row.getEnd() != null ? VietnamTime.utc(row.getDay(), row.getEnd())
                    : startAt.plus(JOINED_WITHOUT_END);
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                events.add(new Item("event", startAt, endAt, null, row.getTitle(), row.getPlace(),
                        row.isTrainingPoints() ? "★ Training points" : "Event", null, Source.email(row.getMailKey()),
                        false));
            }
        }
        return events;
    }

    /** The items of one Vietnam day. */
```

`web/src/main/resources/static/css/style.css`: one change.

Replace

```css
/* Blackboard names like "Entrepreneurship_S1_2026-27_G01" have no spaces to wrap at. */
h1, .module-card h2, .bb-item h3 { overflow-wrap: anywhere; }

/* School: To submit */
.item.is-overdue { border-left-color: var(--error); }
.badge-overdue { background: var(--error); color: #fff; }
```

with

```css
/* Blackboard names like "Entrepreneurship_S1_2026-27_G01" have no spaces to wrap at. */
h1, .module-card h2, .bb-item h3 { overflow-wrap: anywhere; }

/* School: events joined from Mailbox (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.7) */
:root { --event-color: #1d8a4e; }
.legend-event::before { background: var(--event-color); }
.fc .event-event { background: var(--event-color); border-color: var(--event-color); color: #fff; }
.fc .fc-list-event.event-event { background: transparent; color: var(--text); }
.fc .fc-list-event.event-event .fc-list-event-dot { border-color: var(--event-color); }
.item-event { border-left-color: var(--event-color); }

/* School: To submit */
.item.is-overdue { border-left-color: var(--error); }
.badge-overdue { background: var(--error); color: #fff; }
```

`web/src/main/resources/static/js/timetable.js`: one change.

Replace

```javascript
    height: "auto",
    slotLabelFormat: clock,
    eventTimeFormat: clock,
    noEventsText: "No classes or exams in this period.",
    views: {
      timeGridWeek: { titleFormat: weekTitle },
      listWeek: { titleFormat: weekTitle },
```

with

```javascript
    height: "auto",
    slotLabelFormat: clock,
    eventTimeFormat: clock,
    noEventsText: "No classes, exams or events in this period.",
    views: {
      timeGridWeek: { titleFormat: weekTitle },
      listWeek: { titleFormat: weekTitle },
```

`web/src/main/resources/templates/school/fragments.html`: 2 changes.

Change 1: replace

```html
  </form>
</section>

<!--/* One class or exam (Schedule.Item). A class changed by an announcement or email gets a badge and a link to it. */-->
<li th:fragment="item(item)" th:with="look=${item.change == 'cancelled' ? 'cancelled' : 'changed'}"
    th:class="|item item-${item.kind}${item.change != null ? ' item-' + look : ''}|">
  <span class="item-time" th:text="${item.allDay ? 'All day' : @schoolFormat.clock(item.startAt)
```

with

```html
  </form>
</section>

<!--/* One class, exam or joined event (Schedule.Item). A class changed by an announcement or email gets a badge and
     a link to it; an event links to its email in Mailbox. */-->
<li th:fragment="item(item)" th:with="look=${item.change == 'cancelled' ? 'cancelled' : 'changed'}"
    th:class="|item item-${item.kind}${item.change != null ? ' item-' + look : ''}|">
  <span class="item-time" th:text="${item.allDay ? 'All day' : @schoolFormat.clock(item.startAt)
```

Change 2: replace

```html
    <th:block th:if="${item.label != null}"><strong th:text="|${item.label}:|">Final exam:</strong> </th:block><th:block
        th:text="${item.title}">Web Application Development</th:block>
  </span>
  <span class="item-meta">
    <th:block th:text="${item.code}">IT093IU</th:block>
    <th:block th:if="${item.room != null and !item.room.isEmpty() and item.change != 'online'}">
      · <span th:if="${item.room.toUpperCase().startsWith('ONLINE')}" class="badge">Online</span><th:block
```

with

```html
    <th:block th:if="${item.label != null}"><strong th:text="|${item.label}:|">Final exam:</strong> </th:block><th:block
        th:text="${item.title}">Web Application Development</th:block>
  </span>
  <span class="item-meta" th:if="${item.kind == 'event'}">
    <th:block th:if="${item.room != null}" th:text="|${item.room} · |">Hall A2 · </th:block><a
        th:href="@{${item.source.link}}" th:text="${item.source.text}">See email</a>
  </span>
  <span class="item-meta" th:unless="${item.kind == 'event'}">
    <th:block th:text="${item.code}">IT093IU</th:block>
    <th:block th:if="${item.room != null and !item.room.isEmpty() and item.change != 'online'}">
      · <span th:if="${item.room.toUpperCase().startsWith('ONLINE')}" class="badge">Online</span><th:block
```

`web/src/main/resources/templates/school/timetable.html`: one change.

Replace

```html
    <span class="legend-item legend-due">Deadline</span>
    <span class="legend-item legend-changed">Online / make-up</span>
    <span class="legend-item legend-cancelled">Cancelled</span>
    <span class="muted">All times are Vietnam time.</span>
  </p>

```

with

```html
    <span class="legend-item legend-due">Deadline</span>
    <span class="legend-item legend-changed">Online / make-up</span>
    <span class="legend-item legend-cancelled">Cancelled</span>
    <span class="legend-item legend-event">Joined event</span>
    <span class="muted">All times are Vietnam time.</span>
  </p>

```

- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='CalendarFeedTest,SchoolPagesTest')` → `Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 481, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat(web): joined events in the Timetable, the calendar feed and Overview

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Every test, on MySQL too, and the pages in a real browser

Nothing in the repository changes in this task.

**Files:** none.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: the go-ahead for the checkpoint.

- [ ] **Step 1: Every test**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (415 tests). Run: `(cd web && ./mvnw -B test)` → `Tests run: 481, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 2: The Java tests on a throwaway MySQL server**

The student's database account can't create databases, so start a private MySQL 8.4 server from the installed one, on port 3310 (the service on 3306 is never touched). In the background:

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 8.4/bin"
"$MYSQL/mysqld.exe" --no-defaults --initialize-insecure --datadir="$SCRATCH/mysqldata"
"$MYSQL/mysqld.exe" --no-defaults --datadir="$SCRATCH/mysqldata" --port=3310 --bind-address=127.0.0.1 --mysqlx=OFF --console
```

When its output says `ready for connections`:

```bash
"$MYSQL/mysql.exe" --no-defaults -uroot -h127.0.0.1 -P3310 -e "CREATE DATABASE sla_web_test CHARACTER SET utf8mb4"
(cd web && SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3310/sla_web_test" SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= ./mvnw -B test)
"$MYSQL/mysqladmin.exe" --no-defaults -uroot -h127.0.0.1 -P3310 shutdown
```

Expected: `Database: jdbc:mysql://127.0.0.1:3310/sla_web_test (MySQL 8.4)`, `now at version v20260928.1.4`, `Tests run: 481, Failures: 0, Errors: 0, Skipped: 0`. When the background server has exited, `rm -rf "$SCRATCH/mysqldata"`.

- [ ] **Step 3: The pages in Edge, with made-up mail**

Start the site on a throwaway in-memory database with the test settings (it never touches MySQL), in the background:

```bash
(cd web && ./mvnw -B spring-boot:test-run -Dspring-boot.run.arguments="--server.port=8099 --server.servlet.session.cookie.secure=false")
```

If `$SCRATCH/pwenv` doesn't exist yet, make a scratch Playwright environment (it uses the installed Edge; nothing is downloaded but the Python package):

```bash
py -3.12 -m venv "$SCRATCH/pwenv" && "$SCRATCH/pwenv/Scripts/python.exe" -m pip install -q playwright
```

When the site says `Started SlaWebApplication`, save this as `$SCRATCH/mailbox_events_check.py` (with the editor). It registers an account, adds a device, uploads made-up mail with sessions and a made-up timetable through the real sync API, opens Mailbox, Join…, Overview and the Timetable at 1400×1000 and 390×844, and tries Done, opening with Web ↗, the auto-Done setting, Join… and Move to…:

```python
"""Browser check of the compact Mailbox, auto-Done and Join in Edge, on the throwaway in-memory site at :8099.

Registers an account, adds a device, uploads made-up mail (sorting results and sessions only, as the agent sends
them) and a made-up timetable through the real sync API, then opens Mailbox, Join..., Overview and the Timetable at
desktop and phone size, and tries Done, opening with "Web ↗" (Outlook on the web is never really opened), the
auto-Done setting, Join... and Move to.... It never clicks an sla-mail: link, which would start the laptop agent.
Usage: python mailbox_events_check.py <screenshot folder>
"""
import hashlib
import json
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone

from playwright.sync_api import sync_playwright

OUT, BASE = sys.argv[1], "http://127.0.0.1:8099"
VN = timezone(timedelta(hours=7))
SIZES = {"desktop": {"width": 1400, "height": 1000}, "phone": {"width": 390, "height": 844}}
READ = """() => ({width: document.documentElement.scrollWidth, page: getComputedStyle(document.body).backgroundColor,
                  text: getComputedStyle(document.body).color})"""
problems, notes = [], []
TODAY = datetime.now(VN).date()


def day(offset):
    return (TODAY + timedelta(days=offset)).isoformat()


def email(n, subject, sender, address, categories=(), hours_ago=1, **more):
    item = {"key": hashlib.sha256(f"<made-up-{n}@example.com>".encode()).hexdigest(), "entry_id": f"00AB{n:04X}",
            "received_at": (datetime.now(timezone.utc) - timedelta(hours=hours_ago)).isoformat(),
            "sender_name": sender, "sender_address": address, "subject": subject, "categories": list(categories)}
    item.update(more)
    return item


def payload():
    lecturer = {"from_lecturer": True}
    emails = [
        email(1, "Online class tomorrow", "Tran Van An", "tvan@hcmiu.edu.vn", ["class"], 1, **lecturer,
              dates=[day(1)], class_changes=[{"course_code": "IT093IU", "kind": "online", "day": day(1)}]),
        email(2, "Re: Lab 4 questions", "Vo Minh Khoa", "vmkhoa@hcmiu.edu.vn", ["class"], 2, thread_id="T1", **lecturer),
        email(3, "Lab 4 questions", "Vo Minh Khoa", "vmkhoa@hcmiu.edu.vn", ["class"], 30, thread_id="T1", **lecturer),
        email(4, "Thông báo về việc đăng ký tạm trú", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["school_task"], 3,
              dates=[day(5)]),
        email(5, "Nhắc nhở hạn cuối khảo sát", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["school_task", "training_points"],
              90, dates=[day(-3)]),
        email(6, "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 00001", "M-INVOICE", "noreply@example.vn", ["money"], 4),
        email(7, "[Thông báo] Chương trình học bổng 2026", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["money"], 60),
        email(8, "[THƯ MỜI] Workshop A", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["event", "training_points"], 5,
              dates=[day(1), day(3)], sessions=[{"day": day(1), "start": "09:00:00", "end": "11:00:00"},
                                                {"day": day(3), "start": "13:30:00"}]),
        email(9, "Talkshow B", "Hoi Sinh Vien IU", "hoisinhvien@hcmiu.edu.vn", ["event"], 6, dates=[day(1)],
              sessions=[{"day": day(1), "start": "18:00:00", "end": "20:00:00"}]),
        email(10, "[Thông báo] Casting C", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["event"], 120, dates=[day(-4)]),
        email(11, "[THƯ MỜI] Workshop D", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["event"], 20, dates=[day(2)]),
        email(12, "[thư mời] WORKSHOP D", "P.CTSV [OSS]", "oss@hcmiu.edu.vn", ["event"], 8, dates=[day(2)]),
        email(13, "A notice the rules couldn't read", "Someone", "someone@example.com", [], 9, sorted=False),
    ] + [email(20 + i, f"Receipt {i}", "bb@hcmiu.edu.vn", "bb@hcmiu.edu.vn", ["class"], 10 + i) for i in range(12)]

    def at(offset, hhmm):
        hour, minute = map(int, hhmm.split(":"))
        d = TODAY + timedelta(days=offset)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=VN).isoformat()

    return {
        "schema_version": 1,
        "timetable": {"status": "ok", "data": {"term_code": "20261", "courses": [
            {"course_code": "IT093IU", "course_name": "Web Application Development", "group": "02",
             "meetings": [{"start_at": at(1, "08:00"), "end_at": at(1, "10:30"), "room": "A2.307"}]}]}},
        "outlook": {"status": "ok", "data": {"since": "2026-08-01", "connected": True, "emails": emails}},
    }


def api(key, path, body=None):
    request = urllib.request.Request(f"{BASE}/api/school/sync{path}", method="POST" if body is not None else "GET",
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def rows(tab, box, part="> ul.mail-rows"):
    return tab.locator(f"#box-{box} {part} > li.mail-row .mail-subject").all_text_contents()


def row(tab, subject):
    return tab.locator("li.mail-row", has=tab.locator(".mail-subject", has_text=subject)).first


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page()
    page.goto(f"{BASE}/auth/register")
    page.fill("#email", f"an.mail.{int(time.time())}@example.com")
    page.fill("#displayName", "An")
    page.fill("#password", "correct-horse-8")
    page.fill("#confirm", "correct-horse-8")
    page.click("button[type=submit]")
    page.goto(f"{BASE}/school/devices")
    page.fill("#name", "My laptop")
    page.click("form[action='/school/devices'] button[type=submit]")
    key = page.locator("#new-key").inner_text()
    run_id = api(key, "/runs", {"trigger": "manual"})["run_id"]
    notes.append(f"finish: {api(key, f'/runs/{run_id}/finish', payload())}")
    cookies = page.context.cookies()
    page.close()

    for size, viewport in SIZES.items():
        context = browser.new_context(viewport=viewport)
        context.add_cookies(cookies)
        context.route("https://outlook.office.com/**", lambda route: route.abort())
        tab = context.new_page()
        errors, failed = [], []
        tab.on("console", lambda m: m.type == "error" and errors.append(m.text))
        tab.on("requestfailed", lambda r: failed.append(r.url))
        tab.goto(f"{BASE}/school/mailbox")
        join_path = row(tab, "Workshop A").locator("a:has-text('Join')").get_attribute("href")
        for name, path in [("mailbox", "/school/mailbox"), ("join", join_path), ("overview", "/school"),
                           ("timetable", "/school/timetable")]:
            tab.goto(f"{BASE}{path}")
            tab.wait_for_load_state("networkidle")
            look = tab.evaluate(READ)
            if look["width"] > viewport["width"]:
                problems.append(f"{size} {name}: page is {look['width']}px wide")
            if look["page"] != "rgb(246, 247, 249)" or look["text"] != "rgb(0, 0, 0)":
                problems.append(f"{size} {name}: colours {look}")
            tab.screenshot(path=f"{OUT}/{size}-{name}.png", full_page=True)
        if size == "desktop":
            tab.goto(f"{BASE}/school/mailbox")
            notes.append(f"boxes: {tab.locator('section.mailbox-box h2').all_inner_texts()}")
            for box in ["lecturers", "tasks", "money", "events"]:
                notes.append(f"{box}: {rows(tab, box)}")
            notes.append(f"other: {len(rows(tab, 'other'))} shown, show all: {tab.locator('text=Show all').count()}")
            notes.append(f"past: tasks {rows(tab, 'tasks', 'details ul.mail-rows')}, "
                         f"events {rows(tab, 'events', 'details ul.mail-rows')}")
            workshop = row(tab, "Workshop A")
            notes.append(f"Workshop A labels: {workshop.locator('.mail-labels .cat').all_inner_texts()}; marks: "
                         f"{workshop.locator('.mail-sessions .mark').all_inner_texts()}")
            notes.append(f"unsorted label: {row(tab, 'rules couldn').locator('.cat-unsorted').inner_text()} "
                         f"({row(tab, 'rules couldn').locator('.cat-unsorted').get_attribute('title')})")
            done = row(tab, "M-Invoice").locator("button.done-button:visible")
            notes.append(f"Done button height: {round(done.bounding_box()['height'])}px")
            done.click()
            tab.wait_for_load_state("networkidle")
            notes.append("after Done: " + tab.locator("#box-done summary").inner_text()
                         + f"; still in Money: {len([s for s in rows(tab, 'money') if 'M-Invoice' in s])}")
            for subject in ["Online class tomorrow", "Talkshow B"]:
                with tab.expect_response(lambda r: r.url.endswith("/opened")):
                    with context.expect_page():
                        row(tab, subject).locator("a:has-text('Web')").click()
                tab.wait_for_timeout(300)
                notes.append(f"opened {subject}: {row(tab, subject).get_attribute('class')}")
            tab.reload()
            notes.append(f"after reload: Talkshow B still in Events: {'Talkshow B' in rows(tab, 'events')}; "
                         f"Online class in Done: {'Online class tomorrow' in rows(tab, 'done', 'details ul.mail-rows')}")
            tab.uncheck("#auto-done")
            tab.wait_for_load_state("networkidle")
            notes.append(f"auto-Done after unticking: {tab.is_checked('#auto-done')}")
            tab.check("#auto-done")
            tab.wait_for_load_state("networkidle")
            row(tab, "Workshop A").locator("a:has-text('Join')").click()
            tab.wait_for_load_state("networkidle")
            notes.append(f"join page: {tab.locator('.join-line').all_inner_texts()}")
            tab.locator(".join-line input").nth(1).check()
            tab.fill("#place", "Hall A2")
            tab.click("button:has-text('Save')")
            tab.wait_for_load_state("networkidle")
            notes.append("after Join: " + tab.locator(".flash").first.inner_text() + "; marks: "
                         + str(row(tab, "Workshop A").locator(".mail-sessions .mark").all_inner_texts()))
            row(tab, "Talkshow B").locator("a:has-text('Join')").click()
            tab.locator(".join-line input").first.check()
            tab.click("button:has-text('Save')")
            tab.wait_for_load_state("networkidle")
            feed = tab.evaluate(f"fetch('/school/api/calendar?start={day(0)}&end={day(7)}').then(r => r.json())")
            notes.append("calendar: " + str([(e["title"], e["classNames"][0]) for e in feed
                                             if e["extendedProps"]["kind"] == "event"]))
            row(tab, "rules couldn").locator("a:has-text('Move')").click()
            tab.select_option("#category1", "school_task")
            tab.click("button:has-text('Save')")
            tab.wait_for_load_state("networkidle")
            notes.append(f"moved to School tasks: {'A notice the rules couldn' + chr(39) + 't read' in rows(tab, 'tasks')}")
            tab.goto(f"{BASE}/school")
            notes.append("overview status: " + tab.locator(".system-lines").inner_text().replace("\n", " | "))
            notes.append(f"joined events on Overview: {tab.locator('li.item-event').count()}")
        if errors or failed:
            problems.append(f"{size}: console errors {errors}, failed requests {failed}")
        context.close()
    browser.close()

print("\n".join(notes))
print("PROBLEMS:" if problems else "No problems found.", *problems, sep="\n")
```

Run: `mkdir -p "$SCRATCH/shots" && PYTHONIOENCODING=utf-8 "$SCRATCH/pwenv/Scripts/python.exe" "$SCRATCH/mailbox_events_check.py" "$SCRATCH/shots"`

Expected (the dates and times differ):

```text
finish: {'status': 'success'}
boxes: ['From lecturers (2)', 'School tasks (1)', 'Money (2)', 'Events (3)', 'Everything else (13)']
lecturers: ['Online class tomorrow', 'Re: Lab 4 questions']
tasks: ['Thông báo về việc đăng ký tạm trú']
money: ['[ M-Invoice ] TB: Xuất hóa đơn điện tử số 00001', '[Thông báo] Chương trình học bổng 2026']
events: ['[THƯ MỜI] Workshop A', 'Talkshow B', '[thư mời] WORKSHOP D']
other: 13 shown, show all: 0
past: tasks ['Nhắc nhở hạn cuối khảo sát'], events ['[Thông báo] Casting C']
Workshop A labels: ['Event', '★ Points']; marks: ['⚠ Conflict: Web Application Development', '✓ No conflict']
unsorted label: Not sorted (Couldn't sort this email automatically. Use Move to…)
Done button height: 32px
after Done: Done (1); still in Money: 0
opened Online class tomorrow: mail-row is-opened is-done
opened Talkshow B: mail-row is-opened
after reload: Talkshow B still in Events: True; Online class in Done: True
auto-Done after unticking: False
join page: ['Wed 30/09 09:00–11:00\n⚠ Conflict: Web Application Development', 'Fri 02/10 from 13:30\n✓ No conflict']
after Join: Joined. It is in your Timetable now.; marks: ['⚠ Conflict: Web Application Development', 'Joined']
calendar: [('Event: Talkshow B', 'event-event'), ('★ Training points: [THƯ MỜI] Workshop A', 'event-event')]
moved to School tasks: True
overview status: EduSoft: synced at 01:05 | Outlook: synced at 01:05
joined events on Overview: 1
No problems found.
```

Look at `desktop-mailbox.png`, `phone-mailbox.png` and `desktop-join.png`: rows in order with the date and Done columns lined up, labels on one line, the Conflict mark in red and No conflict in green, three short lines per row at phone width, nothing cut off. Then stop the site (its Java process keeps port 8099 until it is stopped: find it with PowerShell `Get-NetTCPConnection -LocalPort 8099 -State Listen`, check that its command line holds `--server.port=8099`, and stop that process).

---

### Task 7: Checkpoint: a real sync, and the student checks the new Mailbox

Nothing in the repository changes in this task. It runs the new site on the student's database (Flyway applies `1_2`, `1_3` and `1_4`: `school_mail` loses its `loses_points` column, whose values the next sync no longer sends), runs one real sync with the new agent, and then **the executor stops and asks the student** to check Mailbox. It replaces the Outlook plan's paused Task 10, Step 5. Main session only.

**Files:** none.

**Interfaces:**
- Consumes: Tasks 1–6; `state.json` with `outlook_account` (set up on 2026-09-28); classic Outlook signed in.
- Produces: the student's go-ahead, and a fresh `state.json` for Task 8.

- [ ] **Step 1: Stop the running site, start the new one**

With PowerShell, `Get-NetTCPConnection -LocalPort 5000 -State Listen`. If a site is running, check that its command line (`(Get-CimInstance Win32_Process -Filter "ProcessId=<pid>").CommandLine`) holds `SlaWebApplication` or `spring-boot:run`, then stop it. Anything else on port 5000: stop and ask the student. Then, in the background (it reads the repository's `.env`, so the student's database):

```bash
(cd web && ./mvnw -B spring-boot:run)
```

Expected in its output: `Migrating schema ... to version "20260928.1.2 - mail sessions"`, `... "20260928.1.3 - mail opened"`, `... "20260928.1.4 - mail joined"`, `Tomcat started on port 5000`, `Started SlaWebApplication`.

- [ ] **Step 2: One real sync**

**Main session only.** Run: `.venv/Scripts/sla-agent.exe sync-now`
Expected: `Sync finished.` (`The last sync was less than 5 minutes ago` means wait and run it again.)

- [ ] **Step 3: What was saved (counts only, no subjects)**

Save as `$SCRATCH/mail_saved.py` (with the editor):

```python
"""How many emails and sessions the site saved, and the newest run's parts (reads DATABASE_URL from .env)."""

import json
from collections import Counter
from urllib.parse import unquote, urlsplit

import pymysql

url = next(line.split("=", 1)[1].strip().strip('"').strip("'")
           for line in open(".env", encoding="utf-8") if line.strip().startswith("DATABASE_URL="))
parts = urlsplit(url)
db = pymysql.connect(host=parts.hostname, port=parts.port or 3306, user=unquote(parts.username),
                     password=unquote(parts.password or ""), database=parts.path.lstrip("/"), charset="utf8mb4")
with db.cursor() as cursor:
    cursor.execute("SELECT status, sections FROM school_sync_runs ORDER BY id DESC LIMIT 1")
    status, sections = cursor.fetchone()
    print("run", status, {k: v.get("status") for k, v in (json.loads(sections) or {}).items()})
    cursor.execute("SELECT categories, from_lecturer, is_sorted FROM school_mail")
    rows = cursor.fetchall()
    print("emails", len(rows), "from lecturers", sum(r[1] for r in rows), "unsorted", sum(not r[2] for r in rows))
    print(Counter(c for r in rows for c in (r[0].split(",") if r[0] else ["(none)"])))
    cursor.execute("SELECT m.categories FROM school_mail_sessions s JOIN school_mail m ON m.id = s.mail_id")
    found = cursor.fetchall()
    print("sessions", len(found), "in events or school tasks",
          sum(1 for (c,) in found if "event" in c or "school_task" in c))
    cursor.execute("SELECT COUNT(*) FROM school_mail_changes")
    print("class changes found in emails", cursor.fetchone()[0])
db.close()
```

Run: `.venv/Scripts/python.exe "$SCRATCH/mail_saved.py"`
Expected: `run success {… 'outlook': 'ok'}`, 56 emails or more (as of 2026-09-28), 8 or more from lecturers, 0 unsorted, every category present, some sessions (most in events and school tasks), and 2 class changes.

- [ ] **Step 4: The student's own check**

Stop and ask the student to:

1. Open http://localhost:5000/school/mailbox: one row per email with its category first, counts in the box titles, every email shown; events show their times with **Conflict** / **No conflict** marks that match their timetable.
2. Click the **subject** of one email: Edge asks to open `sla-agent` (allow it; tick "Always allow"), and the email must open in Outlook. With the tick box on, a non-event email moves to Done after the next reload; an upcoming event only turns grey. Untick "Mark emails as done when I open them" and check that opening then only turns the subject grey; tick it again.
3. Press **Join…** on an event, tick a session (add one by hand if a time was missed), add a place, **Save**; the event is in the Timetable (green) and, if it is today or tomorrow, on Overview. Then **Leave event** if they don't want it.
4. Look at Overview: the sync status has an **Outlook** line.
5. Try **✓ Done** and **Move to…** on one row, then undo them (Undo, Back to automatic).

Continue with Task 8 only after the student says it's right. If a time or a mark is wrong, note which email and what it should be, and discuss it with the student: a rule change is outside this plan.

---

### Task 8: Checkpoint: anonymized samples of the student's Inbox

`anonymize_mail.py` copies the student's Inbox (subject, sender, time, and the text of announcements, events, school tasks and promotions) with the student and every IU person replaced by made-up people whose addresses are built the same way, so the lecturer rules still apply. Each email gets the result the rules give now as `expected`, **sessions included**; **the student checks the file and the results before it is committed** (Outlook §8). `test_mail_samples.py` then pins those results. This is the Outlook plan's Task 11 with sessions in place of "loses points".

**Files:**
- Create: `agent/tools/anonymize_mail.py`, `agent/tests/fixtures/mail-samples.json` (made by the tool)
- Test: `agent/tests/test_anonymize_mail.py`, `agent/tests/test_mail_samples.py`

**Interfaces:**
- Consumes: `inbox_of`, `open_outlook`, `read_emails`, `semester_start` (Outlook plan Task 4); `Email`, `Context`, `sort_email` and the helpers of Outlook plan Task 3; `MailItem.sessions` (Tasks 1–2); `load_state` with `outlook_account`, `term_code`, `courses`, `bb_courses`, `student_id` (filled by Task 7's sync).
- Produces: `anonymize(emails, context, student_name, student_id, private=()) -> (emails, context, swaps)`; `sample(email, context) -> dict`; `PLACEHOLDER_TEXT`; the command `python -m agent.tools.anonymize_mail OUTPUT --name "<full name>" [--student-id ID] [--private TEXT …]`.

- [ ] **Step 1: Write the tool's failing test**

`agent/tests/test_anonymize_mail.py` (new):

```python
"""The tool that makes agent/tests/fixtures/mail-samples.json: made-up people must sort like the real ones."""

from datetime import datetime, timezone

from agent.tools.anonymize_mail import PLACEHOLDER_TEXT, anonymize
from sla_agent.mail_rules import Context, Email, sort_email

ARRIVED = datetime(2026, 9, 21, 1, 5, tzinfo=timezone.utc)
CONTEXT = Context(courses=(("IT093IU", "Web Application Development", "P.Q.Hùng"),),
                  bb_courses=(("Web Application Development_S1_2026-27_G02", "IT093IU"),))


def email(key, subject, text, address, name):
    return Email(key=key * 64, entry_id="00AB", thread_id=None, received_at=ARRIVED, sender_name=name,
                 sender_address=address, subject=subject, text=text)


EMAILS = [
    email("1", "Web Application Development_S1_2026-27_G02: Online class on 22/9",
          "Class 22/9 is online: https://teams.microsoft.com/l/meetup-join/19%3a 22-9-2026. Hung Quoc Pham, Ph.D.",
          "bb@hcmiu.edu.vn", "Hung Quoc Pham - pqhung@hcmiu.edu.vn"),
    email("2", "Re: Slide bài tập", "Dear Thị Mai, see page 78. Khoa", "vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"),
    email("3", "[Ticket: 12345] Trần Thị Mai – Yêu cầu mới được tạo", "Em là Trần Thị Mai, MSSV ITITIU99001",
          "oss@hcmiu.edu.vn", "OSS HCMIU"),
    email("4", "[THƯ MỜI] Workshop", "Gửi ititiu99001@student.hcmiu.edu.vn: cộng điểm rèn luyện. Mã 12345678.\n"
          "Thời gian: 13h30 – 16h30, ngày 24/9/2026", "oss@hcmiu.edu.vn", "P.CTSV [OSS]"),
]


def test_people_are_replaced_by_made_up_people_who_sort_the_same():
    fake, context, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    text = repr(fake) + repr(context)
    for real in ("Thi Mai", "ITITIU99001", "ititiu99001", "pqhung", "vmkhoa", "Khoa", "Hung Quoc Pham", "P.Q.Hùng"):
        assert real not in text
    assert [sort_email(e, context).categories for e in fake] == [sort_email(e, CONTEXT).categories for e in EMAILS]
    assert [sort_email(e, context).from_lecturer for e in fake] == [True, True, False, False]
    assert sort_email(fake[0], context).class_changes[0].course_code == "IT093IU"


def test_texts_are_kept_only_for_announcements_without_links_or_long_numbers():
    fake, _, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    assert "https://example.com/link" in fake[0].text and "teams.microsoft" not in fake[0].text
    assert (fake[1].text, fake[2].text) == (PLACEHOLDER_TEXT, PLACEHOLDER_TEXT)
    assert "student01@student.hcmiu.edu.vn" in fake[3].text.lower() and "00000000" in fake[3].text


def test_an_events_times_survive_anonymizing():
    fake, context, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    sessions = sort_email(fake[3], context).sessions

    assert [(s.day.isoformat(), s.start.isoformat(), s.end.isoformat()) for s in sessions] == [
        ("2026-09-24", "13:30:00", "16:30:00")]
```

- [ ] **Step 2: Run it to see it fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_anonymize_mail.py -q`
Expected: FAIL: `ModuleNotFoundError: No module named 'agent.tools.anonymize_mail'`.

- [ ] **Step 3: Write the tool**

`agent/tools/anonymize_mail.py` (new):

```python
"""Make an anonymized test copy of the student's Inbox, for the sorting tests (agent/tests/test_mail_samples.py).

Usage (on the laptop, with classic Outlook set up and one sync done):
    python -m agent.tools.anonymize_mail agent/tests/fixtures/mail-samples.json --name "<your full name>"
        [--private "text to hide" ...]
--name is the student's name as their emails write it; the student ID is the one `sla-agent setup` saved.

Keeps each email's subject, time and thread, and the text of announcements (events, school tasks,
training points, promotions, Blackboard announcements). Replaces:
- the student's name, ID and address, and every --private text;
- every person (lecturers, IU staff) with a made-up person whose address is built from the made-up name
  the same way, so the lecturer rules still apply;
- links, and runs of 6 or more digits;
- the whole text of every other email (replies from people, tickets, password resets, receipts, invoices).

Each email's `expected` result is what the sorting rules give now, sessions included
(docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 3.2); the student checks them before the file is
committed. The tool reads Outlook only; it writes nothing but the output file."""

import argparse
import json
import re
import sys
import unicodedata
from dataclasses import replace
from datetime import date, datetime, timezone
from pathlib import Path

from sla_agent.mail_rules import (
    Context,
    Email,
    blackboard_lecturer,
    fold,
    lecturer_handle,
    name_handles,
    sender_handle,
    sort_email,
)
from sla_agent.outlook_reader import inbox_of, open_outlook, read_emails, semester_start
from sla_agent.state import load_state

KEEP_TEXT = {"event", "school_task", "training_points", "promotion"}
PLACEHOLDER_TEXT = "(Anonymized text.)"
FAKE_PEOPLE = [("Trần", "Văn", "An"), ("Lê", "Thị", "Bình"), ("Phạm", "Minh", "Châu"), ("Hoàng", "Quốc", "Dũng"),
               ("Võ", "Thanh", "Giang"), ("Đặng", "Hữu", "Hải"), ("Bùi", "Ngọc", "Khánh"), ("Đỗ", "Thu", "Lan"),
               ("Ngô", "Đức", "Minh"), ("Dương", "Mai", "Nga"), ("Lý", "Gia", "Phúc"), ("Mai", "Anh", "Quân"),
               ("Hồ", "Bảo", "Sơn"), ("Tạ", "Kim", "Thoa"), ("Châu", "Hoài", "Uyên"), ("Kiều", "Tuấn", "Vinh")]
URL = re.compile(r"https?://\S+")
DIGITS = re.compile(r"\d{6,}")


def plain(text):
    """Without accents, keeping letter case: "Đặng Văn Long" -> "Do Xuan Hoi"."""
    text = unicodedata.normalize("NFD", text.replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c))


class People:
    """Real person -> made-up person, keyed by the address beginning (e.g. "vmkhoa")."""

    def __init__(self):
        self.fakes = {}  # real handle -> (family, middle, given)

    def fake(self, handle):
        if handle not in self.fakes:
            self.fakes[handle] = FAKE_PEOPLE[len(self.fakes) % len(FAKE_PEOPLE)]
        return self.fakes[handle]

    def _written(self, handle, real_name):
        """The made-up name's words in the real name's word order (Vietnamese "Vo Minh Khoa" or the other
        way round, "Hung Quoc Pham")."""
        family, middle, given = self.fake(handle)
        words = re.findall(r"[a-z]+", fold(real_name))
        reverse = len(words) >= 2 and handle in {"".join(w[0] for w in words[:0:-1]) + words[0],
                                                 "".join(words[::-1])}
        return [given, middle, family] if reverse else [family, middle, given]

    def name(self, handle, real_name):
        """(made-up name written like the real one: word order, accents, capitals; its address beginning,
        built the same way: initials + given name, or the whole name joined)."""
        family, middle, given = self.fake(handle)
        name = " ".join(self._written(handle, real_name))
        if real_name == plain(real_name):
            name = plain(name)
        if real_name.isupper():
            name = name.upper()
        words = re.findall(r"[a-z]+", fold(real_name))
        canonical = re.findall(r"[a-z]+", fold(f"{family} {middle} {given}"))
        joined = handle in ("".join(words), "".join(words[::-1]))
        fake_handle = "".join(canonical) if joined else canonical[0][0] + canonical[1][0] + canonical[2]
        return name, fake_handle

    def variants(self, handle, real_name):
        """(real, made-up) pairs for the name as it may appear in a text: both word orders, with and without
        accents, as written and in capitals."""
        real, fake = real_name.split(), self._written(handle, real_name)
        pairs = []
        for real_words, fake_words in ((real, fake), (real[::-1], fake[::-1])):
            for shape in (lambda t: t, plain, str.upper, lambda t: plain(t).upper()):
                pairs.append((shape(" ".join(real_words)), shape(" ".join(fake_words))))
        return pairs

    def short(self, handle):
        """EduSoft's short form: "T.V.An"."""
        family, middle, given = self.fake(handle)
        return f"{family[0]}.{middle[0]}.{given}"


def anonymize(emails, context, student_name, student_id, private=()):
    """(anonymized emails, anonymized context, replacements): made-up people for real ones, and the text
    rules of this module's docstring. `emails` are mail_rules.Email objects."""
    people = People()
    swaps = []  # (real, fake), longest first when applied

    def person(handle, real_name, domain):
        name, fake_handle = people.name(handle, real_name)
        if len(real_name.split()) >= 2:
            swaps.extend(people.variants(handle, real_name))
        swaps.append((f"{handle}@{domain}", f"{fake_handle}@{domain}"))
        return name, f"{fake_handle}@{domain}"

    courses = []
    for code, course_name, lecturer in context.courses:
        if lecturer:
            handle = lecturer_handle(lecturer)
            swaps.append((lecturer, people.short(handle)))
            courses.append((code, course_name, people.short(handle)))
        else:
            courses.append((code, course_name, lecturer))

    student_words = student_name.split()
    for real in {student_name, plain(student_name), plain(student_name).upper(), " ".join(student_words[-2:]),
                 plain(" ".join(student_words[-2:]))}:
        swaps.append((real, "Student Name"))
    swaps.append((student_id, "STUDENT01"))  # replaced in any letter case
    swaps += [(text, "(private)") for text in private]

    fakes = {}
    for email in emails:
        handle = sender_handle(email)
        domain = email.sender_address.rpartition("@")[2]
        lecturer = blackboard_lecturer(email)
        if lecturer:
            real_name = email.sender_name.split(" - ")[0].strip()
            name, address = person(lecturer, real_name, "hcmiu.edu.vn")
            fakes[email.key] = (f"{name} - {address}", email.sender_address)
        elif handle and (handle in name_handles(email.sender_name)
                         or any(handle == lecturer_handle(c[2]) for c in context.courses if c[2])):
            fakes[email.key] = person(handle, email.sender_name, domain)

    swaps.sort(key=lambda pair: len(pair[0]), reverse=True)

    def clean(text):
        for real, fake in swaps:
            text = re.sub(re.escape(real), fake.replace("\\", "\\\\"), text, flags=re.IGNORECASE)
        return DIGITS.sub(lambda m: "0" * len(m.group(0)), URL.sub("https://example.com/link", text))

    result = []
    for email in emails:
        sorted_now = sort_email(email, context)
        keep = bool(set(sorted_now.categories) & KEEP_TEXT) and "requests_account" not in sorted_now.categories
        keep = keep or bool(blackboard_lecturer(email))
        name, address = fakes.get(email.key, (email.sender_name, email.sender_address))
        result.append(replace(email, sender_name=clean(name) if email.key not in fakes else name,
                              sender_address=address, subject=clean(email.subject),
                              text=clean(email.text) if keep else PLACEHOLDER_TEXT))
    fake_context = Context(courses=tuple(courses), bb_courses=context.bb_courses)
    return result, fake_context, swaps


def sample(email, context):
    item = sort_email(email, context)
    return {
        "subject": email.subject, "sender_name": email.sender_name, "sender_address": email.sender_address,
        "received_at": email.received_at.isoformat(), "thread_id": email.thread_id, "text": email.text,
        "expected": {
            "categories": item.categories, "from_lecturer": item.from_lecturer,
            "dates": [d.isoformat() for d in item.dates],
            "sessions": [s.model_dump(mode="json", exclude_none=True) for s in item.sessions],
            "blackboard_title": item.blackboard_title,
            "class_changes": [c.model_dump(mode="json", exclude_none=True) for c in item.class_changes],
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output")
    parser.add_argument("--name", required=True, help="the student's full name, as in emails")
    parser.add_argument("--student-id", help="default: the one `sla-agent setup` saved")
    parser.add_argument("--private", action="append", default=[], help="any other text to hide")
    parser.add_argument("--since", type=date.fromisoformat)
    args = parser.parse_args(argv)

    state = load_state()
    if not state.outlook_account or not (args.student_id or state.student_id):
        print("Run `sla-agent setup`, `sla-agent setup --outlook` and one sync first.")
        return 1
    context = Context(courses=tuple(tuple(c) for c in state.courses or []),
                      bb_courses=tuple(tuple(c) for c in state.bb_courses or []))
    since = args.since or semester_start(state.term_code, datetime.now(timezone.utc).date())
    _, inbox = inbox_of(open_outlook(), state.outlook_account)
    emails, skipped = read_emails(inbox, since)

    fake, fake_context, swaps = anonymize(emails, context, args.name, args.student_id or state.student_id,
                                          args.private)
    samples = [sample(email, fake_context) for email in fake]
    real_results = [sort_email(email, context) for email in emails]
    differ = [s["subject"] for s, real in zip(samples, real_results)
              if s["expected"]["categories"] != real.categories
              or s["expected"]["from_lecturer"] != real.from_lecturer]
    Path(args.output).write_text(json.dumps({
        "about": "Anonymized copies of the student's Inbox made by agent/tools/anonymize_mail.py. "
                 "`expected` is what the sorting rules must give; checked by the student.",
        "context": {"courses": [list(c) for c in fake_context.courses],
                    "bb_courses": [list(c) for c in fake_context.bb_courses]},
        "emails": samples,
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    written = Path(args.output).read_text(encoding="utf-8")
    leaks = [real for real, _ in swaps if len(real) >= 4 and real.lower() in written.lower()]
    print(f"emails: {len(samples)} (skipped {skipped}), names and addresses replaced: {len({r for r, _ in swaps})}, "
          f"still present: {len(leaks)}, sorted differently after anonymizing: {len(differ)}")
    for subject in differ:
        print("  differs:", subject)
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_anonymize_mail.py -q` → PASS (3 tests).

- [ ] **Step 4: Make the samples from the student's Inbox**

**Main session only.** `--name` is the student's full name as their emails write it (with Vietnamese accents); ask the student if unsure. The student ID comes from what `sla-agent setup` saved, so the command never shows it.

Run: `PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m agent.tools.anonymize_mail agent/tests/fixtures/mail-samples.json --name "<the student's full name>"`
Expected: `emails: 56 (skipped 0), names and addresses replaced: …, still present: 0, sorted differently after anonymizing: 0` (more emails if new mail has arrived since 2026-09-28). If `still present` or `sorted differently` is not 0, stop and report.

- [ ] **Step 5: The student checks the file and the results**

Print the results for the student:

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -c "
import json
for e in json.load(open('agent/tests/fixtures/mail-samples.json', encoding='utf-8'))['emails']:
    x = e['expected']
    times = ', '.join(s['day'][5:] + ' ' + s['start'][:5] + ('-' + s['end'][:5] if 'end' in s else '') for s in x['sessions'])
    print(e['received_at'][:10], '+'.join(x['categories']) or '(none)', 'L' if x['from_lecturer'] else '', times, '|', e['subject'][:60])
"
```

Stop and ask the student to (1) open `agent/tests/fixtures/mail-samples.json` and check that nothing personal is left (names, IDs, addresses, private replies), and (2) check the categories and the times found for each event. If the student wants a result changed, that is a rule change: stop and discuss it. Continue only with the student's go-ahead.

- [ ] **Step 6: Pin the checked results**

`agent/tests/test_mail_samples.py` (new):

```python
"""The sorting rules on anonymized copies of the student's real Inbox: agent/tests/fixtures/mail-samples.json,
made by agent/tools/anonymize_mail.py. Each `expected` result, sessions included, was checked by the student."""

import json
from datetime import datetime
from pathlib import Path

import pytest

from sla_agent.mail_rules import Context, Email, sort_email

SAMPLES = json.loads((Path(__file__).parent / "fixtures" / "mail-samples.json").read_text(encoding="utf-8"))
CONTEXT = Context(courses=tuple(tuple(c) for c in SAMPLES["context"]["courses"]),
                  bb_courses=tuple(tuple(c) for c in SAMPLES["context"]["bb_courses"]))


@pytest.mark.parametrize("sample", SAMPLES["emails"],
                         ids=[f"{n:02d} {s['subject'][:40]}" for n, s in enumerate(SAMPLES["emails"])])
def test_each_email_sorts_as_the_student_checked(sample):
    email = Email(key="0" * 64, entry_id="00", thread_id=sample["thread_id"],
                  received_at=datetime.fromisoformat(sample["received_at"]), sender_name=sample["sender_name"],
                  sender_address=sample["sender_address"], subject=sample["subject"], text=sample["text"])

    item = sort_email(email, CONTEXT)

    assert {
        "categories": item.categories, "from_lecturer": item.from_lecturer,
        "dates": [d.isoformat() for d in item.dates],
        "sessions": [s.model_dump(mode="json", exclude_none=True) for s in item.sessions],
        "blackboard_title": item.blackboard_title,
        "class_changes": [c.model_dump(mode="json", exclude_none=True) for c in item.class_changes],
    } == sample["expected"]


def test_the_samples_cover_every_box():
    categories = {c for sample in SAMPLES["emails"] for c in sample["expected"]["categories"]}

    assert categories >= {"class", "event", "training_points", "school_task", "money", "requests_account",
                          "system_notice", "promotion"}
    assert any(sample["expected"]["class_changes"] for sample in SAMPLES["emails"])
    assert any(sample["expected"]["sessions"] for sample in SAMPLES["emails"])
```

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (415 + 3 + the number of sample emails + 1 tests).

- [ ] **Step 7: Commit**

```bash
git add agent/tools/anonymize_mail.py agent/tests/test_anonymize_mail.py agent/tests/test_mail_samples.py agent/tests/fixtures/mail-samples.json
git commit -m "test(agent): the sorting rules and event times on anonymized samples of the student's Inbox, checked by the student

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: The README and both specs

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md`, `docs/superpowers/specs/2026-09-28-mailbox-events-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: documentation only.

- [ ] **Step 1: The README, and both specs as built**

The README gets `setup --outlook` and a Mailbox section describing rows, opening, auto-Done, event times and Join…; the Outlook spec's status becomes "Built", it records what trying out the Outlook plan settled, and it notes where the mailbox-events design changed it; the mailbox-events spec becomes "Built" and records this plan's decisions.

`README.md`: one change.

Replace

```markdown
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Remove the saved passwords, key and schedule | `sla-agent forget` |

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

---

```

with

```markdown
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Read your Inbox through classic Outlook | `sla-agent setup --outlook` |
| Remove the saved passwords, key, schedule and `sla-mail:` link type | `sla-agent forget` |

**Mailbox (Outlook).** IU doesn't let students approve apps that read mail, so the agent reads your Inbox through the classic Outlook app on your laptop (Windows only). Open **Outlook (classic)**, sign in with your IU account, wait for "All folders are up to date", then run `sla-agent setup --outlook`. Each sync then reads your Inbox since the start of the semester, sorts every email on your laptop, and uploads only the results (sender, subject, time, categories, dates, event times, class changes), **never the text**. School → Mailbox shows them, one row per email with its category first. Clicking the subject opens the email in Outlook on this laptop (Edge asks once to open `sla-agent`), and **Web ↗** opens Outlook on the web anywhere. Opening an email marks it Done, unless it is an event or school task still ahead; untick "Mark emails as done when I open them" to press ✓ Done yourself. Events show the times the laptop found in them, each marked **Conflict** or **No conflict** against your timetable, and **Join…** puts the sessions you pick into your Timetable. If Outlook shows a security warning or blocks the agent, the agent never clicks past it; Mailbox says so at the top.

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

`contract/samples/class-changes/sentences.json` lists example announcements and the class changes each must give. The agent's Python reader (lecturers' emails) and the site's Java reader (Blackboard announcements) both check every one, so the two stay the same.

---

```

`docs/superpowers/specs/2026-09-28-mailbox-events-design.md`: 6 changes.

Change 1: replace

```markdown
**Date:** 2026-09-28
**Scope:** show Mailbox as compact rows with the category first, mark opened emails Done by themselves (a setting turns this off), drop "lose points", let the agent find events' times, mark each time Conflict / No conflict against the timetable, and put the events the student joins into the Timetable
**Owner:** Nguyen Khang Vy
**Status:** Design, not built
**Builds on:** [Outlook mail in a Mailbox tab](2026-09-28-outlook-mailbox-design.md) (built on the branch `outlook-mailbox`, Tasks 1–9 of its plan). Everything there stays the same unless this document says otherwise. Section numbers like "Outlook §6.3" point into that spec.

---
```

with

```markdown
**Date:** 2026-09-28
**Scope:** show Mailbox as compact rows with the category first, mark opened emails Done by themselves (a setting turns this off), drop "lose points", let the agent find events' times, mark each time Conflict / No conflict against the timetable, and put the events the student joins into the Timetable
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-29-mailbox-events.md)
**Builds on:** [Outlook mail in a Mailbox tab](2026-09-28-outlook-mailbox-design.md) (built on the branch `outlook-mailbox`, Tasks 1–9 of its plan). Everything there stays the same unless this document says otherwise. Section numbers like "Outlook §6.3" point into that spec.

---
```

Change 2: replace

```markdown

**Pairing a time with its day:**

1. A time goes with the dates in the **same line**. If the line has none, it takes the dates of the **nearest line above** that has dates (e.g. "Ngày: 01/10/2026" then "Thời gian: 13h00 – 16h00").
2. **Several dates, one time** on a line: one session per date ("ngày 29/09 và 01/10, 13:00–14:00" → 2 sessions).
3. **Several times, one date** on a line: one session per time ("Ca 1: 8:00–10:00; Ca 2: 13:00–15:00 ngày 30/9" → 2 sessions on 30/9).
4. A line with several dates and several times pairs them in order when the counts are equal; otherwise every date gets every time.
```

with

```markdown

**Pairing a time with its day:**

1. A time goes with the dates in the **same sentence or line**. If it has none, it takes the dates of the **nearest sentence or line above** that has dates (e.g. "Ngày: 01/10/2026" then "Thời gian: 13h00 – 16h00").
2. **Several dates, one time** on a line: one session per date ("ngày 29/09 và 01/10, 13:00–14:00" → 2 sessions).
3. **Several times, one date** on a line: one session per time ("Ca 1: 8:00–10:00; Ca 2: 13:00–15:00 ngày 30/9" → 2 sessions on 30/9).
4. A line with several dates and several times pairs them in order when the counts are equal; otherwise every date gets every time.
```

Change 3: replace

```markdown

### 4.1 Tables

One Flyway migration, `V20260928_1_2__mail_events.sql`:

- **`school_mail`**: drop `loses_points`.
- **`school_mail_sessions`** (new): `id`, `mail_id` (deleted with its email), `day` DATE, `start_time` TIME, `end_time` TIME NULL. Replaced with the mail at every sync (Outlook §6.2).
```

with

```markdown

### 4.1 Tables

Three Flyway migrations, each with the part that needs it: `V20260928_1_2__mail_sessions.sql` (sessions, no `loses_points`), `V20260928_1_3__mail_opened.sql` (opened, settings) and `V20260928_1_4__mail_joined.sql` (joined sessions). Days are stored in `session_day` (`day` is a reserved word):

- **`school_mail`**: drop `loses_points`.
- **`school_mail_sessions`** (new): `id`, `mail_id` (deleted with its email), `day` DATE, `start_time` TIME, `end_time` TIME NULL. Replaced with the mail at every sync (Outlook §6.2).
```

Change 4: replace

```markdown
  5. **When**: "Next: Thu 01/10" for cards in the Events and School tasks boxes with an upcoming date or session (as Outlook §6.3), otherwise the time received.
  6. **Actions**: **Web ↗** (Outlook on the web, new tab, `rel="noopener noreferrer"`), **Move…**, **Join…** (event-like cards only), and **✓ Done** (Undo in the Done list) as a real button at the end, at least 32 px tall with a visible border.
- **Sessions line:** a card with upcoming sessions gets one small extra line under its row listing them, e.g. `Tue 29/09 13:00–14:00 ⚠ Conflict: Web Application · Thu 01/10 13:00–14:00 ✓ No conflict`. A joined session shows **Joined** (green) instead of its mark. A session without an end shows "from 14:00".
- **Phone (under 700 px):** each row wraps to two lines: labels and extra tags, then when (first line); sender · subject, then actions (second line). The sessions line wraps below.
- **Removed:** the "Open in Outlook" button (the subject is the link), the "⚠ lose points if absent" tag, and the "Couldn't sort…" sentence on the card (now the label's hover text).

### 4.3 Opening, auto-Done and the setting
```

with

```markdown
  5. **When**: "Next: Thu 01/10" for cards in the Events and School tasks boxes with an upcoming date or session (as Outlook §6.3), otherwise the time received.
  6. **Actions**: **Web ↗** (Outlook on the web, new tab, `rel="noopener noreferrer"`), **Move…**, **Join…** (event-like cards only), and **✓ Done** (Undo in the Done list) as a real button at the end, at least 32 px tall with a visible border.
- **Sessions line:** a card with upcoming sessions gets one small extra line under its row listing them, e.g. `Tue 29/09 13:00–14:00 ⚠ Conflict: Web Application · Thu 01/10 13:00–14:00 ✓ No conflict`. A joined session shows **Joined** (green) instead of its mark. A session without an end shows "from 14:00".
- **Phone (under 700 px):** each row takes three short lines: labels and extra tags, then when; sender · subject; the actions. (Two lines left the subject no room next to Web ↗, Move…, Join… and ✓ Done.) The sessions line wraps below.
- **Removed:** the "Open in Outlook" button (the subject is the link), the "⚠ lose points if absent" tag, and the "Couldn't sort…" sentence on the card (now the label's hover text).

### 4.3 Opening, auto-Done and the setting
```

Change 5: replace

```markdown
- The student's **other joined sessions** of this email (added by hand, or no longer in the email), ticked, labelled "added by you".
- **Add a session:** day, start, end (optional). One per save.
- **Place** (optional, up to 100 characters), shown for every joined session of this email.
- **Save** (`POST …/join`), **Leave event** (`POST …/leave`: removes all of this email's joined sessions), **Cancel**.

Saving replaces this email's joined sessions with the ticked ones plus the added one, copying the card's subject as `title` and whether its categories (the student's choice from Move to… wins) include Training points. Refused with a message: a day before today, an end not after its start, more than 10 joined sessions for one email. Joining does not mark the card Done.

**Addresses** (login, CSRF on POSTs, a key that isn't one of the user's cards gives 404): `GET`/`POST /school/mailbox/{key}/join`, `POST /school/mailbox/{key}/leave`, `POST /school/mailbox/{key}/opened`, `POST /school/mailbox/settings`.

```

with

```markdown
- The student's **other joined sessions** of this email (added by hand, or no longer in the email), ticked, labelled "added by you".
- **Add a session:** day, start, end (optional). One per save.
- **Place** (optional, up to 100 characters), shown for every joined session of this email.
- **Save** (`POST …/join`), **Leave event** (`POST …/leave`: removes this email's joined sessions that haven't ended), **Cancel**.

Saving replaces this email's joined sessions that haven't ended with the ticked ones plus the added one (sessions already over stay in the Timetable), copying the card's subject as `title` and whether its categories (the student's choice from Move to… wins) include Training points. Refused with a message: a day before today, an end not after its start, more than 10 joined sessions for one email. Joining does not mark the card Done.

**Addresses** (login, CSRF on POSTs, a key that isn't one of the user's cards gives 404): `GET`/`POST /school/mailbox/{key}/join`, `POST /school/mailbox/{key}/leave`, `POST /school/mailbox/{key}/opened`, `POST /school/mailbox/settings`.

```

Change 6: replace

```markdown

---

## 9. Risks

- **The agent misreads or misses a time.** The student sees the sessions on the card and can add or fix one on the Join page.
- **A deadline read as a session** when its line lacks the deadline words. The student simply doesn't tick it.
```

with

```markdown

---

## 9. Decided while building (2026-09-29)

- **A repeated day and start keeps an end if any copy has one:** "Workshop 30/9 lúc 14h" in the subject and "14h00 – 16h00 ngày 30/9" in the text give 14:00–16:00.
- **`13.00` needs two digits after the dot**, so money ("15.000.000") and phone numbers are never times.
- **Joined sessions are saved under the card's newest email** and found by any of the card's emails.
- **"Not sorted" disappears** once the student has used Move to… on the card.
- **The date and action columns have fixed widths** (130 and 270 px) so the rows line up.

## 10. Risks

- **The agent misreads or misses a time.** The student sees the sessions on the card and can add or fix one on the Join page.
- **A deadline read as a session** when its line lacks the deadline words. The student simply doesn't tick it.
```

`docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md`: 10 changes.

Change 1: replace

```markdown
**Date:** 2026-09-28
**Scope:** read the student's IU Inbox through classic Outlook on the laptop, sort every email into the agreed categories, show them in a new **Mailbox** tab in priority order, and let class-change emails change the timetable like Blackboard announcements do, without counting a change twice
**Owner:** Nguyen Khang Vy
**Status:** Design, not built
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and "To submit"](2026-09-26-class-changes-and-to-submit-design.md) and [the Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---
```

with

```markdown
**Date:** 2026-09-28
**Scope:** read the student's IU Inbox through classic Outlook on the laptop, sort every email into the agreed categories, show them in a new **Mailbox** tab in priority order, and let class-change emails change the timetable like Blackboard announcements do, without counting a change twice
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-28-outlook-mailbox.md). The Mailbox page, "lose points" and event times were then changed by [a compact Mailbox, auto-Done, and events you can join](2026-09-28-mailbox-events-design.md); each section that changed says so.
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and "To submit"](2026-09-26-class-changes-and-to-submit-design.md) and [the Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---
```

Change 2: replace

```markdown
- **Only the default Inbox** of the confirmed account, and only emails (Outlook item class 43), not meeting requests or delivery reports.
- **From the semester start**, taken from the EduSoft term code of the last timetable read (`YYYYS`): semester 1 → 1 August of `YYYY`; semester 2 → 1 January of `YYYY+1`; semester 3 (summer) → 1 June of `YYYY+1`. For `20261` this is **1 August 2026**. With no term code yet: the last 90 days.
- **Every sync reads all of them again.** About 50 ms per email, so 500 emails take under 30 seconds. This keeps it simple: deleted or moved emails disappear by themselves, and an improved rule re-sorts old mail at the next sync (no "re-read" button is needed).
- **Outlook is started hidden** if it isn't running. After reading, the agent closes it again only if the agent started it and no Outlook window is open.
- **Time limit:** the Outlook read runs in a child process and is stopped after **3 minutes** (for example when Outlook shows a security prompt nobody answers).
- **Read only, in code:** the Outlook reader only reads item properties and, in `open-mail`, calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`, or sets any property (including `UnRead`). A test scans the reader's source for these names.
- **Outlook's security warning is never answered by the agent.** If Outlook blocks the read, the Outlook part fails with a message (4.4).

```

with

```markdown
- **Only the default Inbox** of the confirmed account, and only emails (Outlook item class 43), not meeting requests or delivery reports.
- **From the semester start**, taken from the EduSoft term code of the last timetable read (`YYYYS`): semester 1 → 1 August of `YYYY`; semester 2 → 1 January of `YYYY+1`; semester 3 (summer) → 1 June of `YYYY+1`. For `20261` this is **1 August 2026**. With no term code yet: the last 90 days.
- **Every sync reads all of them again.** About 50 ms per email, so 500 emails take under 30 seconds. This keeps it simple: deleted or moved emails disappear by themselves, and an improved rule re-sorts old mail at the next sync (no "re-read" button is needed).
- **Outlook is started hidden** if it isn't running, and the reader waits up to 30 seconds for it to connect (it needs about 5). The agent never closes Outlook: a hidden Outlook closes by itself when the agent lets go of it and no Outlook window is open (checked 2026-09-28), so a window the student opened meanwhile is never closed.
- **The time an email arrived** is read from `PR_MESSAGE_DELIVERY_TIME` (UTC). pywin32 labels Outlook's `ReceivedTime` as UTC although it is the laptop's local time, so that is only the fallback, read as local time.
- **Time limit:** the Outlook read runs in a background thread and is given up after **3 minutes** (for example when Outlook shows a security prompt nobody answers): the Outlook part fails with `outlook_blocked`, and the stuck call ends when the agent's process does.
- **Read only, in code:** the Outlook reader only reads item properties and, in `open-mail`, calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`, or sets any property (including `UnRead`). A test scans the reader's source for these names.
- **Outlook's security warning is never answered by the agent.** If Outlook blocks the read, the Outlook part fails with a message (4.4).

```

Change 3: replace

```markdown

**Never uploaded:** the email text or HTML, attachments, To/CC lists, the Outlook account's other folders.

### 4.5 Problems

New error codes in the upload format: `outlook_not_set_up`, `outlook_blocked`.
```

with

```markdown

**Never uploaded:** the email text or HTML, attachments, To/CC lists, the Outlook account's other folders.

**Changed later:** `loses_points` was removed and each email gained `sessions`, the days and times its event takes place (mailbox-events design, 3.3).

### 4.5 Problems

New error codes in the upload format: `outlook_not_set_up`, `outlook_blocked`.
```

Change 4: replace

```markdown
2. **Timetable lecturer:** the part before `@` equals a timetable lecturer's name with dots, spaces and accents removed, in lower case (`P.Q.Hùng` → `pqhung`).
3. **An IU person:** the address is at `hcmiu.edu.vn` or one of its sub-domains, except `student.hcmiu.edu.vn`, and the part before `@` is built from the sender's own name (accents removed, lower case). It is either the initials of every word but the last followed by the last word (`Vo Minh Khoa` → `vmkhoa`), or all words joined (`Bui Thanh Nga` → `buithanhnga`). Both word orders are tried, so `Hung Quoc Pham` also gives `pqhung`. Office accounts (`oss@`, `hoisinhvien@`, `iuyouth@`, `bb@`, `noreply.cis@` …) never match because their names aren't built this way.

**Automatic Microsoft notices never count as lecturer mail:** Teams "added you to a group" emails (subject contains "Microsoft Teams" and one of "được thêm", "đã thêm", "added you") and anything from `sharepointonline.com` or `microsoft.com` addresses.

### 5.2 Categories
```

with

```markdown
2. **Timetable lecturer:** the part before `@` equals a timetable lecturer's name with dots, spaces and accents removed, in lower case (`P.Q.Hùng` → `pqhung`).
3. **An IU person:** the address is at `hcmiu.edu.vn` or one of its sub-domains, except `student.hcmiu.edu.vn`, and the part before `@` is built from the sender's own name (accents removed, lower case). It is either the initials of every word but the last followed by the last word (`Vo Minh Khoa` → `vmkhoa`), or all words joined (`Bui Thanh Nga` → `buithanhnga`). Both word orders are tried, so `Hung Quoc Pham` also gives `pqhung`. Office accounts (`oss@`, `hoisinhvien@`, `iuyouth@`, `bb@`, `noreply.cis@` …) never match because their names aren't built this way.

Every rule needs an IU staff address (`hcmiu.edu.vn` or a sub-domain, not `student.hcmiu.edu.vn`). Rule 3 needs a name of at least two words, so a one-word office name never counts.

**Automatic Microsoft notices never count as lecturer mail:** Teams "added you to a group" emails (subject contains "Microsoft Teams" and one of "được thêm", "đã thêm", "added you") and anything from `sharepointonline.com` or `microsoft.com` addresses.

### 5.2 Categories
```

Change 5: replace

```markdown
### 5.4 Dates and "loses points"

- **Dates:** every date in the subject and text, found with the class-change reader's date formats (`29/09/2026`, `27/9`, `18-9-2026`, `ngày 18 tháng 9`, `September 24`, `24th September`, …). Dates inside links are ignored. A date without a year takes the year closest to the day the email arrived. Dates before the day it arrived are dropped. At most 30, sorted, no repeats.
- **`loses_points`:** the text has "trừ" followed within 30 characters by "điểm rèn luyện" (e.g. "bị trừ điểm rèn luyện", "trừ 05 điểm rèn luyện").

### 5.5 Class changes from email

```

with

```markdown
### 5.4 Dates and "loses points"

- **Dates:** every date in the subject and text, found with the class-change reader's date formats (`29/09/2026`, `27/9`, `18-9-2026`, `ngày 18 tháng 9`, `September 24`, `24th September`, …). Dates inside links are ignored. A date without a year takes the year closest to the day the email arrived. Dates before the day it arrived are dropped. At most 30, sorted, no repeats.
- **`loses_points`** was removed by the mailbox-events design. The agent finds the times an event takes place instead (mailbox-events 3.2).

### 5.5 Class changes from email

```

Change 6: replace

```markdown

The Python class-change reader removed with the Python website (`app/school/services/class_changes.py` at commit `471cac0^`, 178 lines) comes back in the agent as `agent/sla_agent/class_changes.py`. The Java `ClassChanges` stays for Blackboard announcements.

So the two can't drift apart, a shared file `contract/samples/class-change-sentences.json` lists example announcements (title, text, posting time) with the changes they must give. Both the Python and the Java tests check every example. The examples start with those in the class-changes design's tests.

---

```

with

```markdown

The Python class-change reader removed with the Python website (`app/school/services/class_changes.py` at commit `471cac0^`, 178 lines) comes back in the agent as `agent/sla_agent/class_changes.py`. The Java `ClassChanges` stays for Blackboard announcements.

So the two can't drift apart, a shared file `contract/samples/class-changes/sentences.json` lists example announcements (title, text, posting time) with the changes they must give, each distinct change once. Both the Python and the Java tests check every example. The examples start with those in the class-changes design's tests. The file is in a sub-folder because every `*.json` directly in `contract/samples/` is read as an upload.

---

```

Change 7: replace

```markdown

### 6.1 Tables

One Flyway migration, `V<build date>_1_<n>__school_mail.sql` (School module = 1):

- **`school_mail`**: `id`, `user_id`, `mail_key` CHAR(64), `entry_id` VARCHAR(512), `thread_id` VARCHAR(64) NULL, `received_at` DATETIME (UTC), `sender_name` VARCHAR(255), `sender_address` VARCHAR(255), `subject` VARCHAR(500), `categories` VARCHAR(100) (comma-separated), `from_lecturer`, `dates` VARCHAR(400) (comma-separated ISO dates), `loses_points`, `sorted`, `blackboard_title` VARCHAR(255) NULL. Unique (`user_id`, `mail_key`).
- **`school_mail_changes`**: `id`, `mail_id` (deleted with its email), `course_code`, `kind`, `day`, `start_time` NULL, `end_time` NULL, `room` NULL.
- **`school_mail_choices`**: `id`, `user_id`, `mail_key`, `done`, `categories` VARCHAR(100) NULL, `from_lecturer` NULL, `updated_at`. Unique (`user_id`, `mail_key`). The student's Done and Move to… choices.
- **`school_mail_status`**: `user_id` (key), `since`, `connected`, `synced_at` (UTC).

```

with

```markdown

### 6.1 Tables

One Flyway migration, `V<build date>_1_<n>__school_mail.sql` (School module = 1). The mailbox-events design (4.1) later dropped `loses_points` and added `school_mail_sessions`, `school_mail_joined`, `school_mail_settings` and `school_mail_choices.opened`.

- **`school_mail`**: `id`, `user_id`, `mail_key` VARCHAR(64), `entry_id` VARCHAR(512), `thread_id` VARCHAR(64) NULL, `received_at` DATETIME (UTC), `sender_name` VARCHAR(255), `sender_address` VARCHAR(255), `subject` VARCHAR(500), `categories` VARCHAR(100) (comma-separated), `from_lecturer`, `dates` VARCHAR(400) (comma-separated ISO dates), `loses_points`, `is_sorted`, `blackboard_title` VARCHAR(255) NULL. Unique (`user_id`, `mail_key`).
- **`school_mail_changes`**: `id`, `mail_id` (deleted with its email), `course_code`, `kind`, `change_day`, `start_time` NULL, `end_time` NULL, `room` NULL.
- **`school_mail_choices`**: `id`, `user_id`, `mail_key`, `done`, `categories` VARCHAR(100) NULL, `from_lecturer` NULL, `updated_at`. Unique (`user_id`, `mail_key`). The student's Done and Move to… choices.
- **`school_mail_status`**: `user_id` (key), `since`, `connected`, `synced_at` (UTC).

```

Change 8: replace

```markdown
### 6.3 The Mailbox tab

**Menu:** Overview · **Mailbox** · Timetable · Courses · Exams · Tuition · Devices. Page `/school/mailbox`.

**Cards.** Emails are grouped into cards:

```

with

```markdown
### 6.3 The Mailbox tab

**Menu:** Overview · **Mailbox** · Timetable · Courses · Exams · Tuition · Devices. Page `/school/mailbox`.

This section describes the first Mailbox. The mailbox-events design (4.2–4.6) turned the cards into one row each, shows every card (no "Show all"), removed the "Open in Outlook" button and the lose-points tag, and added opening, auto-Done, event sessions and Join….

**Cards.** Emails are grouped into cards:

```

Change 9: replace

```markdown
- **Categories and order:** subject-only words (the beFood prize), the top-two order, no match.
- **Dates:** each format, dates in links, year guessing, dates before arrival, the 30 limit; `loses_points`.
- **Class changes:** the four course rules; not from a lecturer → none.
- **Shared sentences:** every example in `class-change-sentences.json`.

**Agent: Outlook (with a fake Outlook, no real Outlook needed):**

```

with

```markdown
- **Categories and order:** subject-only words (the beFood prize), the top-two order, no match.
- **Dates:** each format, dates in links, year guessing, dates before arrival, the 30 limit; `loses_points`.
- **Class changes:** the four course rules; not from a lecturer → none.
- **Shared sentences:** every example in `class-changes/sentences.json`.

**Agent: Outlook (with a fake Outlook, no real Outlook needed):**

```

Change 10: replace

```markdown
## 9. Build order

1. Upload format: the `outlook` part and error codes (Python and Java), samples.
2. Agent: the Python date reader restored, with `class-change-sentences.json` checked by both test suites.
3. Agent: `anonymize_mail.py` and the sample file (the student checks it).
4. Agent: sorting rules (5.1–5.5).
5. Agent: the Outlook reader, `setup --outlook`, the link type, `open-mail`, `forget`, sync and problems.
```

with

```markdown
## 9. Build order

1. Upload format: the `outlook` part and error codes (Python and Java), samples.
2. Agent: the Python date reader restored, with `class-changes/sentences.json` checked by both test suites.
3. Agent: `anonymize_mail.py` and the sample file (the student checks it).
4. Agent: sorting rules (5.1–5.5).
5. Agent: the Outlook reader, `setup --outlook`, the link type, `open-mail`, `forget`, sync and problems.
```

- [ ] **Step 2: Commit**

```bash
git add README.md docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md docs/superpowers/specs/2026-09-28-mailbox-events-design.md
git commit -m "docs: Mailbox rows, auto-Done and Join… in the README; both specs as built

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
