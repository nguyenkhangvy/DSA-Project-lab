# Outlook Mailbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The laptop agent reads the student's IU Inbox through classic Outlook, sorts every email on the laptop into the 8 agreed categories, and uploads only the results (never the text); the website shows them in a new Mailbox tab in priority order, with Done and Move to…, and lecturers' class-change emails change the Timetable like Blackboard announcements do, without counting a change twice.

**Architecture:** Contract first: a new `outlook` part in the shared upload format (Python and its Java twin), with example uploads both test suites check. The agent gets back the Python class-change reader (checked against a shared list of example sentences that the Java reader also checks), pure sorting rules, an Outlook reader behind Windows COM (pywin32, loaded lazily and tested with a fake Outlook), and the `setup --outlook` / `open-mail` commands with a per-user `sla-mail:` link type. The website stores the results in four new tables, shows them through a pure `Mailbox` builder and a small controller, and merges emailed class changes into the existing `ClassChanges` fold. Two checkpoints with the student end it: a real sync from their Outlook, and the anonymized samples of their Inbox that pin the sorting rules.

**Tech Stack:** Python 3.12, pydantic, pywin32 312 (Windows only), keyring, pytest (agent and shared format); Java 17, Spring Boot 4.1.1 (Spring MVC, Thymeleaf, Spring Data JPA / Hibernate 7, Flyway), JUnit 5 + MockMvc, H2 for tests; Playwright + Edge for the browser check.

**Spec:** `docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md` (approved 2026-09-28). Section numbers below (§4.2, §5.1 …) are the spec's.

**Tried first:** every change below was built and run in a scratch worktree before this plan was written; the code blocks are taken from it. With all of it, 427 Java tests pass on H2 and on a throwaway MySQL 8.4 server, and 423 Python tests pass (including 55 anonymized copies of the student's real Inbox). The Outlook reader read the student's real Inbox (55 emails since 1 August in 6 seconds, Outlook started hidden), and a browser check in Edge covered Mailbox, Move to…, Overview and the Timetable at 1400×1000 and 390×844 with those real results uploaded through the real sync API: no console errors, no failed requests, no page wider than the screen, light background, black text.

## Global Constraints

- **Email text never leaves the laptop:** not in the upload, `state.json`, the agent's log, the website or a committed file. The upload format refuses unknown fields; there is no field for text (§4.4, §7).
- **Read only:** the Outlook reader only reads item properties, and `open-mail` only calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`/`SaveAs`, `MarkAsRead` or sets `UnRead` (§4.2). It never answers Outlook's security prompts.
- **Never borrow a Microsoft first-party client ID** or otherwise get around IU's "Need admin approval" rule (§2).
- `pywin32` is imported only inside the functions that use Outlook; every agent test runs on Linux (GitHub) with fakes. Tests never touch the real registry (conftest's fake), Credential Manager or `%LOCALAPPDATA%`.
- Outlook problems never pause EduSoft, Blackboard or Outlook itself: every sync tries again (§4.5).
- Times are stored in UTC without an offset and shown in Vietnam time (UTC+7); a Vietnam day runs from 17:00 UTC the day before.
- The site is always light: white and light-grey backgrounds, black text, no dark mode.
- Every query is filtered by the logged-in user; another user's card is 404. Every form posts with its CSRF token.
- `spring.jpa.open-in-view=false`: a template may only read what its query loaded.
- Java 17, no new Java dependencies. Migrations are named `V<date>_<module>_<n>__<what>.sql` (School = 1).
- Real syncs with the saved EduSoft and Blackboard logins, and anything that reads the student's Outlook, run in the main session only, never a subagent. Never print `.env` values, the student ID, the device key, or any email text.
- Work on a branch (`outlook-mailbox`) **in the main working tree**, not a separate worktree: the checkpoints (Tasks 10 and 11) use the agent installed in `.venv`, which runs the main working tree's code.
- Commands are for Git Bash, run from the repository root. `SCRATCH` is the session's scratchpad folder. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The failure modes most likely to bite that the spec implies but a quick read of the tests might miss, and the test that pins each:

1. **Outlook's "received" time is wrong by 7 hours.** pywin32 labels Outlook's `ReceivedTime` as UTC although it is the laptop's local time; used as is, an email that arrived just after midnight on 1 August in Vietnam is dropped as July, and every card's time is off. The reader uses `PR_MESSAGE_DELIVERY_TIME` (real UTC) and falls back to reading `ReceivedTime` as local time. Pinned by `test_reads_the_chosen_accounts_inbox_emails_from_the_semester_start` (31/07 17:30 UTC is in, 16:59 is out) and `test_without_a_delivery_time_it_reads_outlooks_local_time` (Task 4).
2. **Email text leaking out.** Through the upload (`test_the_text_never_leaves_the_laptop`, Task 4; `test_the_text_never_leaves_in_the_result`, Task 3; the shared invalid sample `email-with-text.json`, refused by both suites, Task 1), through the log when the rules fail on an email (`test_an_email_the_rules_fail_on_is_uploaded_unsorted`, Task 3), or through the committed samples (the tool's leak check and the student's review, Task 11).
3. **A class change counted twice or in the wrong order.** Blackboard's copy of an announcement must be skipped whichever arrives first, a newer email must win, and a make-up must not erase a cancellation. Pinned by the five `CalendarFeedTest` cases added in Task 8.
4. **Another user's mail.** Pinned by `IngestTest.anotherUsersMailIsLeftAlone` (Task 6), `MailboxPageTest.someoneElsesCardIs404` (Task 7) and `CalendarFeedTest.anotherUsersEmailsNeverChangeMyClasses` (Task 8).
5. **An Outlook that never answers** (a security prompt nobody sees on a scheduled run) must not hang the sync forever. Pinned by `test_an_outlook_that_does_not_answer_is_given_up_after_the_time_limit` (Task 4) and `test_an_outlook_problem_fails_only_outlook_and_pauses_nothing` (Task 5).

## Decisions made while trying it out

- **The time an email arrived comes from `PR_MESSAGE_DELIVERY_TIME`** (Review Focus 1). Checked on the student's Outlook: `ReceivedTime` gave 09:36 "UTC" for an email delivered at 02:36 UTC.
- **The agent never closes Outlook.** A hidden Outlook started by COM closes by itself when the agent lets go of it and no Outlook window is open (checked 2026-09-28), so there is no `Quit()` that could close a window the student opened meanwhile. The spec (§4.2) is updated in Task 12.
- **A hidden Outlook needs about 5 seconds to connect** after it starts (it reported `ExchangeConnectionMode` 400, then 700). The reader waits up to 30 seconds, then reads what it has and reports `connected: false`.
- **The shared example sentences live in `contract/samples/class-changes/sentences.json`**, a sub-folder, because both suites read every `*.json` directly in `contract/samples/` as an upload. They list each *distinct* change once: an announcement whose title and text say the same thing gives the same change twice.
- **Lecturer rules (§5.1), made exact:** every rule needs an IU staff address (`hcmiu.edu.vn` or a sub-domain, not `student.hcmiu.edu.vn`); a name-built address needs a name of at least two words, so a one-word office name like "IUOSS" never counts; a Blackboard email counts only when its sender name holds an address other than `bb@`.
- **Column names avoid reserved words:** `is_sorted`, `change_day`. Mail keys are `VARCHAR(64)`, not `CHAR(64)`, because Hibernate's schema check expects `varchar`.
- **Categories and dates are stored as comma-separated text** through two small JPA converters (`CommaLists`).
- **Done is saved for a card's newest email; Move to… for all of its emails** (§6.3). The saving side keeps the first of two emails with the same key instead of failing on the unique key.
- **Everything else's "Show all"** and the Past and Done lists are `<details>` elements: no JavaScript.
- **Thymeleaf reads `categories[category]` as the literal key "category"**, so templates call `categories.get(category)`.
- **`open-mail` answers with Windows message boxes,** because the browser starts it without a console.
- **The agent's `conftest.py` gives every test a fake registry,** like its fake keyring, so no test can add or remove the real `sla-mail:` link type.
- **The samples of the student's Inbox come last (Task 11), not third as in the spec's build order (§9):** the tool needs the course list and Blackboard course names that only a real sync (Task 10) saves, and that sync needs the new website.
- **Tests and examples use made-up people** (the student "Trần Thị Mai", lecturers "Vo Minh Khoa", "Hung Quoc Pham" …) whose addresses are built the same way as the real ones, as the project's earlier tests do; the repository is public.

## File Structure

```
contract/
  sla_contract/schema.py                    + outlook part, MailItem, MailClassChange, error codes (Task 1)
  samples/finish-outlook.json               a valid outlook upload (Task 1)
  samples/invalid/email-with-text.json, email-with-three-categories.json   (Task 1)
  samples/class-changes/sentences.json      45 example announcements for both readers (Task 2)
  tests/test_contract.py                    + outlook part (Task 1)
agent/
  pyproject.toml, ../requirements-dev.txt   + pywin32 on Windows (Task 5)
  sla_agent/class_changes.py                the class-change reader, back from git history, + dates_in (Task 2)
  sla_agent/mail_rules.py                   sorting one email (Task 3)
  sla_agent/errors.py                       + OutlookNotSetUp, OutlookBlocked, EmailNotFound (Task 4)
  sla_agent/outlook_reader.py               reading the Inbox, opening one email (Task 4)
  sla_agent/mail_link.py                    the sla-mail: link type (Task 5)
  sla_agent/state.py, sync.py, cli.py       Outlook in each sync; setup --outlook, open-mail, status, forget (Task 5)
  tools/anonymize_mail.py                   anonymized samples of the student's Inbox (Task 11)
  tests/test_class_changes.py (2), test_mail_rules.py (3), outlook_fakes.py + test_outlook_reader.py (4),
        conftest.py + test_mail_link.py + test_sync.py + test_cli.py (5),
        test_anonymize_mail.py + test_mail_samples.py + fixtures/mail-samples.json (11)
web/src/main/
  resources/db/migration/V20260928_1_1__school_mail.sql   four tables (Task 6)
  java/.../school/model/CommaLists.java, SchoolMail*.java (8 files)   (Task 6)
  java/.../school/sync/SyncContract.java    + outlook part (Task 1); Ingest.java + saveOutlook (Task 6)
  java/.../school/pages/SyncStatus.java     + Outlook line, problems, mailProblem (Task 6)
  java/.../school/mail/Mailbox.java         cards and boxes (Task 7)
  java/.../school/pages/MailForm.java, MailboxController.java   (Task 7)
  resources/templates/school/mailbox.html, mailbox-edit.html; fragments.html (menu Task 7, link Task 8)
  resources/static/css/style.css            + Mailbox styles (Task 7)
  java/.../school/schedule/ClassChanges.java, Schedule.java, CalendarController.java   emailed changes (Task 8)
web/src/test/java/.../
  school/sync/Payloads.java, SyncContractTest.java (1); school/schedule/ClassChangesTest.java (2, 8)
  school/model/SchoolTablesTest.java, core/MigrationTest.java, school/sync/IngestTest.java,
        school/pages/SyncStatusTest.java (6)
  school/mail/MailboxTest.java, school/pages/MailboxPageTest.java (7)
  school/SchoolTestData.java, school/schedule/CalendarFeedTest.java, school/pages/SchoolPagesTest.java (8)
README.md, docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md   (Task 12)
```

Test counts: today 312 Java and 205 Python tests pass. After each task: Task 1 → 330 Java, 223 Python; Task 2 → 375, 271; Task 3 → 314 Python; Task 4 → 343; Task 5 → 365; Task 6 → 390 Java; Task 7 → 419; Task 8 → 427; Task 11 → 365 + (number of sample emails) + 3 Python (423 with the 55 emails of 2026-09-28).

---

### Task 1: The `outlook` part of the upload format

The agent will upload one more part, `outlook`: for each email the results of sorting it, never its text. Python's `schema.py` and Java's `SyncContract.java` change together; the shared samples prove both accept and refuse the same things.

**Files:**
- Create: `contract/samples/finish-outlook.json`, `contract/samples/invalid/email-with-text.json`, `contract/samples/invalid/email-with-three-categories.json`
- Modify: `contract/sla_contract/schema.py`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`
- Test: `contract/tests/test_contract.py`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`

**Interfaces:**
- Consumes: the existing `FinishRun`, `SectionOk`/`SectionFailed` (Python) and `FinishRun`, `Section` (Java).
- Produces:
  - Python: `MailCategory` (the 8 names: `class`, `event`, `training_points`, `school_task`, `money`, `requests_account`, `system_notice`, `promotion`), `MAIL_CATEGORIES`; `MailClassChange(course_code, kind, day, start=None, end=None, room=None)`; `MailItem(key, entry_id, thread_id=None, received_at, sender_name="", sender_address="", subject="", categories=[], from_lecturer=False, dates=[], loses_points=False, sorted=True, blackboard_title=None, class_changes=[])`; `Outlook(since, connected, emails)`; `FinishRun.outlook`; `SECTION_NAMES` ends with `"outlook"`; error codes `outlook_not_set_up`, `outlook_blocked`.
  - Java: `SyncContract.MailClassChange(courseCode, kind, day, start, end, room)`, `SyncContract.MailItem(key, entryId, threadId, receivedAt, senderName, senderAddress, subject, categories, fromLecturer, dates, losesPoints, sorted, blackboardTitle, classChanges)` (missing text becomes "", lists `List.of()`, `fromLecturer`/`losesPoints` false, `sorted` true), `SyncContract.Outlook(since, connected, emails)`, `FinishRun.outlook()`, `SyncContract.MAIL_CATEGORIES` (a regular expression of the 8 names); `sections()` ends with `"outlook"`.

- [ ] **Step 1: Write the failing tests and samples**

`contract/samples/finish-outlook.json` (new):

```json
{
  "schema_version": 1,
  "outlook": {
    "status": "ok",
    "data": {
      "since": "2026-08-01",
      "connected": true,
      "emails": [
        {
          "key": "e59f2b527cc57265e4719faaa2bed978ce0cb5468cc8fb109fb90b137e85b860",
          "entry_id": "00000000A1B2C3D4E5F6071829304A5B6C7D8E9F0700C1D2E3F4A5B6C7D8E9F0A1B2C3D4",
          "thread_id": "8E2D1C0B9A8F7E6D5C4B3A2918070605",
          "received_at": "2026-09-21T01:05:00+00:00",
          "sender_name": "Tran Van An - tvan@hcmiu.edu.vn",
          "sender_address": "bb@hcmiu.edu.vn",
          "subject": "Web Application Development_S1_2026-27_G02: Online class on 22/9",
          "categories": ["class"],
          "from_lecturer": true,
          "dates": ["2026-09-22"],
          "blackboard_title": "Online class on 22/9",
          "class_changes": [
            {"course_code": "IT093IU", "kind": "online", "day": "2026-09-22"}
          ]
        },
        {
          "key": "51e77916873ea9f29962ffb91e14c24907e439a41fc6bfdfad52010416af1631",
          "entry_id": "00000000A1B2C3D4E5F6071829304A5B6C7D8E9F0700C1D2E3F4A5B6C7D8E9F0A1B2C3D5",
          "thread_id": null,
          "received_at": "2026-09-24T03:30:00+00:00",
          "sender_name": "P.CTSV [OSS]",
          "sender_address": "oss@hcmiu.edu.vn",
          "subject": "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
          "categories": ["event", "training_points"],
          "dates": ["2026-09-25", "2026-10-02"],
          "loses_points": true
        },
        {
          "key": "8a8799a54ac207a586b7fc42e9fd68adb4e6c2ff5f8b6e0712d68df3acc157ad",
          "entry_id": "00000000A1B2C3D4E5F6071829304A5B6C7D8E9F0700C1D2E3F4A5B6C7D8E9F0A1B2C3D6",
          "received_at": "2026-09-25T09:00:00+00:00",
          "sender_name": "Lecturer Two",
          "sender_address": "ltwo@hcmiu.edu.vn",
          "subject": "Make-up class",
          "from_lecturer": true,
          "sorted": false,
          "class_changes": [
            {"course_code": "MA026IU", "kind": "makeup", "day": "2026-10-03", "start": "13:15:00", "end": "15:45:00", "room": "A2.401"}
          ]
        }
      ]
    }
  }
}
```

`contract/samples/invalid/email-with-text.json` (new):

```json
{
  "outlook": {
    "status": "ok",
    "data": {
      "since": "2026-08-01",
      "connected": true,
      "emails": [
        {
          "key": "e59f2b527cc57265e4719faaa2bed978ce0cb5468cc8fb109fb90b137e85b860",
          "entry_id": "00000000A1B2C3D4",
          "received_at": "2026-09-21T01:05:00+00:00",
          "subject": "Online class on 22/9",
          "text": "The email's text must never leave the laptop."
        }
      ]
    }
  }
}
```

`contract/samples/invalid/email-with-three-categories.json` (new):

```json
{
  "outlook": {
    "status": "ok",
    "data": {
      "since": "2026-08-01",
      "connected": true,
      "emails": [
        {
          "key": "e59f2b527cc57265e4719faaa2bed978ce0cb5468cc8fb109fb90b137e85b860",
          "entry_id": "00000000A1B2C3D4",
          "received_at": "2026-09-21T01:05:00+00:00",
          "categories": ["event", "training_points", "promotion"]
        }
      ]
    }
  }
}
```

`contract/tests/test_contract.py`: one change.

Replace

```python
    assert finish.overall_status() == "partial"


# ---- contract/samples/: the Java website's tests check the same files ----------
```

with

```python
    assert finish.overall_status() == "partial"


# A valid Outlook section as the agent uploads it: results only, never an email's text.
def outlook_payload():
    return _sample("finish-outlook.json")["outlook"]["data"]


def test_an_outlook_section_is_accepted_next_to_the_others():
    payload = full_payload()
    payload["outlook"] = {"status": "ok", "data": outlook_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "outlook"]
    first, second, third = finish.outlook.data.emails
    assert first.class_changes[0].kind == "online"
    assert second.categories == ["event", "training_points"]
    assert (third.sorted, third.class_changes[0].start.isoformat()) == (False, "13:15:00")
    assert (second.from_lecturer, second.sorted, second.class_changes) == (False, True, [])


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["emails"][0].update(text="The email's text must never leave the laptop."),
        lambda p: p["emails"][0].update(body="<p>html</p>"),
        lambda p: p["emails"][0].update(categories=["event", "training_points", "promotion"]),
        lambda p: p["emails"][0].update(categories=["event", "event"]),
        lambda p: p["emails"][0].update(categories=["homework"]),
        lambda p: p["emails"][0].update(entry_id="00000000a1b2"),
        lambda p: p["emails"][0].update(entry_id="javascript:alert(1)"),
        lambda p: p["emails"][0].update(key="not-a-hash"),
        lambda p: p["emails"][0].update(received_at="2026-09-21T01:05:00"),
        lambda p: p["emails"][0]["class_changes"][0].update(kind="moved"),
        lambda p: p["emails"][0].update(dates=["2026-09-22"] * 31),
        lambda p: p.update(emails=p["emails"] * 667),
    ],
    ids=["text", "html", "three-categories", "repeated-category", "unknown-category", "lower-case-entry-id",
         "script-entry-id", "bad-key", "naive-time", "unknown-change", "too-many-dates", "too-many-emails"],
)
def test_bad_outlook_data_is_rejected(change):
    data = outlook_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"outlook": {"status": "ok", "data": data}})


@pytest.mark.parametrize("code", ["outlook_not_set_up", "outlook_blocked"])
def test_a_failed_outlook_part_says_why(code):
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "outlook": {"status": "failed", "error_code": code, "error_message": "Outlook problem"},
    })

    assert finish.overall_status() == "partial"


# ---- contract/samples/: the Java website's tests check the same files ----------
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`: one change.

Replace

```java
        return at(read("finish-blackboard.json"), "blackboard", "data");
    }

    static Map<String, Object> ok(Object data) {
        return Map.of("status", "ok", "data", data);
    }
```

with

```java
        return at(read("finish-blackboard.json"), "blackboard", "data");
    }

    /** A valid Outlook section's data, as the agent uploads it: results only, never an email's text. */
    static Map<String, Object> outlookPayload() {
        return at(read("finish-outlook.json"), "outlook", "data");
    }

    static Map<String, Object> ok(Object data) {
        return Map.of("status", "ok", "data", data);
    }
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`: 3 changes.

Change 1: replace

```java
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.failed;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
```

with

```java
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.failed;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.list;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;
import static vn.edu.hcmiu.sla.school.sync.Payloads.outlookPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
```

Change 2: replace

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
```

with

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
```

Change 3: replace

```java
        assertThat(finish.overallStatus()).isEqualTo("partial");
    }

    // ---- contract/samples/: the Python tests check the same files --------------

    static Stream<Path> samples(Path folder) throws IOException {
```

with

```java
        assertThat(finish.overallStatus()).isEqualTo("partial");
    }

    @Test
    void anOutlookSectionIsAcceptedNextToTheOthers() {
        Map<String, Object> payload = fullPayload();
        payload.put("outlook", ok(outlookPayload()));

        FinishRun finish = read(payload);

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "tuition", "outlook");
        var emails = finish.outlook().data().emails();
        assertThat(emails.get(0).classChanges().get(0).kind()).isEqualTo("online");
        assertThat(emails.get(1).categories()).containsExactly("event", "training_points");
        assertThat(emails.get(2).sorted()).isFalse();
        assertThat(emails.get(2).classChanges().get(0).start()).isEqualTo(LocalTime.of(13, 15));
        assertThat(List.of(emails.get(1).fromLecturer(), emails.get(1).sorted(), emails.get(1).classChanges()))
                .containsExactly(false, true, List.of());
    }

    static Stream<Arguments> badOutlookData() {
        return Stream.<Arguments>of(
                Arguments.of("text", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("text", "The email's text must never leave the laptop.")),
                Arguments.of("html", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0).put("body", "<p>html</p>")),
                Arguments.of("three-categories", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("categories", List.of("event", "training_points", "promotion"))),
                Arguments.of("repeated-category", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("categories", List.of("event", "event"))),
                Arguments.of("unknown-category", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("categories", List.of("homework"))),
                Arguments.of("lower-case-entry-id", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("entry_id", "00000000a1b2")),
                Arguments.of("script-entry-id", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("entry_id", "javascript:alert(1)")),
                Arguments.of("bad-key", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0).put("key", "not-a-hash")),
                Arguments.of("naive-time", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("received_at", "2026-09-21T01:05:00")),
                Arguments.of("unknown-change", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0, "class_changes", 0)
                        .put("kind", "moved")),
                Arguments.of("too-many-dates", (Consumer<Map<String, Object>>) p -> at(p, "emails", 0)
                        .put("dates", Collections.nCopies(31, "2026-09-22"))),
                Arguments.of("too-many-emails", (Consumer<Map<String, Object>>) p -> {
                    List<Object> emails = new ArrayList<>();
                    for (int i = 0; i < 667; i++) {
                        emails.addAll(list(p, "emails"));
                    }
                    p.put("emails", emails);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badOutlookData")
    void badOutlookDataIsRejected(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> data = outlookPayload();
        change.accept(data);

        assertRefused(new HashMap<>(Map.of("outlook", ok(data))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outlook_not_set_up", "outlook_blocked"})
    void aFailedOutlookPartSaysWhy(String code) {
        FinishRun finish = read(Map.of(
                "timetable", fullPayload().get("timetable"),
                "outlook", failed(code, "Outlook problem")));

        assertThat(finish.overallStatus()).isEqualTo("partial");
    }

    // ---- contract/samples/: the Python tests check the same files --------------

    static Stream<Path> samples(Path folder) throws IOException {
```


- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest contract -q`
Expected: FAIL: `test_an_outlook_section_is_accepted_next_to_the_others` and `test_every_shared_sample_is_accepted[finish-outlook.json]` (`outlook` is an unknown field); `test_a_failed_outlook_part_says_why` (unknown error code).

Run: `(cd web && ./mvnw -B test -Dtest=SyncContractTest)`
Expected: FAIL: `anOutlookSectionIsAcceptedNextToTheOthers`, `aFailedOutlookPartSaysWhy`, and `everySharedSampleIsAccepted` for `finish-outlook.json`.

- [ ] **Step 3: Add the part on both sides**

`contract/sla_contract/schema.py`: 5 changes.

Change 1: replace

```python
together. contract/samples/ holds example uploads that both test suites check.
"""

from datetime import date
from typing import Annotated, Generic, Literal, TypeVar

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator
```

with

```python
together. contract/samples/ holds example uploads that both test suites check.
"""

from datetime import date, time
from typing import Annotated, Generic, Literal, TypeVar, get_args

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator
```

Change 2: replace

```python
    "edusoft_changed",  # a page no longer looks the way the parser expects
    "source_changed",  # Blackboard's answers are in an unexpected format
    "extra_verification",  # EduSoft asked for a CAPTCHA, one-time code or Microsoft sign-in
    "unknown",
]
Trigger = Literal["scheduled", "manual", "import"]
```

with

```python
    "edusoft_changed",  # a page no longer looks the way the parser expects
    "source_changed",  # Blackboard's answers are in an unexpected format
    "extra_verification",  # EduSoft asked for a CAPTCHA, one-time code or Microsoft sign-in
    "outlook_not_set_up",  # classic Outlook is missing, has no account, or the chosen account is gone
    "outlook_blocked",  # Outlook refused the read or didn't answer in time (e.g. a security prompt)
    "unknown",
]
Trigger = Literal["scheduled", "manual", "import"]
```

Change 3: replace

```python
    courses: Annotated[list[BbCourse], Field(max_length=40)]


T = TypeVar("T")
```

with

```python
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
    loses_points: bool = False
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
```

Change 4: replace

```python
ExamsResult = Annotated[SectionOk[Exams] | SectionFailed, Field(discriminator="status")]
TuitionResult = Annotated[SectionOk[Tuition] | SectionFailed, Field(discriminator="status")]
BlackboardResult = Annotated[SectionOk[Blackboard] | SectionFailed, Field(discriminator="status")]

EDUSOFT_SECTIONS = ("timetable", "exams", "tuition")
SECTION_NAMES = EDUSOFT_SECTIONS + ("blackboard",)


class FinishRun(_Strict):
```

with

```python
ExamsResult = Annotated[SectionOk[Exams] | SectionFailed, Field(discriminator="status")]
TuitionResult = Annotated[SectionOk[Tuition] | SectionFailed, Field(discriminator="status")]
BlackboardResult = Annotated[SectionOk[Blackboard] | SectionFailed, Field(discriminator="status")]
OutlookResult = Annotated[SectionOk[Outlook] | SectionFailed, Field(discriminator="status")]

EDUSOFT_SECTIONS = ("timetable", "exams", "tuition")
SECTION_NAMES = EDUSOFT_SECTIONS + ("blackboard", "outlook")


class FinishRun(_Strict):
```

Change 5: replace

```python
    exams: ExamsResult | None = None
    tuition: TuitionResult | None = None
    blackboard: BlackboardResult | None = None

    @model_validator(mode="after")
    def _error_or_sections(self):
```

with

```python
    exams: ExamsResult | None = None
    tuition: TuitionResult | None = None
    blackboard: BlackboardResult | None = None
    outlook: OutlookResult | None = None

    @model_validator(mode="after")
    def _error_or_sections(self):
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`: 6 changes.

Change 1: replace

```java
package vn.edu.hcmiu.sla.school.sync;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

with

```java
package vn.edu.hcmiu.sla.school.sync;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

Change 2: replace

```java
    private SyncContract() {
    }

    static final String ERROR_CODES =
            "bad_credentials|session_expired|network|edusoft_changed|source_changed|extra_verification|unknown";
    static final String BLACKBOARD_URL = "(?s)https://blackboard\\.hcmiu\\.edu\\.vn/.*";

    public record StartRun(@NotNull @Pattern(regexp = "scheduled|manual|import") String trigger) {
```

with

```java
    private SyncContract() {
    }

    static final String ERROR_CODES = "bad_credentials|session_expired|network|edusoft_changed|source_changed"
            + "|extra_verification|outlook_not_set_up|outlook_blocked|unknown";
    public static final String MAIL_CATEGORIES =
            "class|event|training_points|school_task|money|requests_account|system_notice|promotion";
    static final String BLACKBOARD_URL = "(?s)https://blackboard\\.hcmiu\\.edu\\.vn/.*";

    public record StartRun(@NotNull @Pattern(regexp = "scheduled|manual|import") String trigger) {
```

Change 3: replace

```java
    public record Blackboard(@NotNull @Size(max = 40) List<@Valid BbCourse> courses) {
    }

    // ---- A whole sync -----------------------------------------------------------

    /** One part of a sync: {"status": "ok", "data": …} or {"status": "failed", "error_code": …, "error_message": …}. */
```

with

```java
    public record Blackboard(@NotNull @Size(max = 40) List<@Valid BbCourse> courses) {
    }

    // ---- Outlook ------------------------------------------------------------------

    /** A class change a lecturer's email announces. day and times are Vietnam time. */
    public record MailClassChange(
            @NotNull @Chars(min = 1, max = 20) String courseCode,
            @NotNull @Pattern(regexp = "online|cancelled|makeup") String kind,
            @NotNull LocalDate day,
            LocalTime start,
            LocalTime end,
            @Chars(max = 50) String room) {
    }

    /** What the website may know about one email: never its text. */
    public record MailItem(
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String key,
            @NotNull @Pattern(regexp = "[0-9A-F]{2,512}") String entryId,
            @Chars(max = 64) String threadId,
            @NotNull OffsetDateTime receivedAt,
            @Chars(max = 255) String senderName,
            @Chars(max = 255) String senderAddress,
            @Chars(max = 500) String subject,
            @Size(max = 2) List<@NotNull @Pattern(regexp = MAIL_CATEGORIES) String> categories,
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            Boolean losesPoints,
            Boolean sorted,
            @Chars(max = 255) String blackboardTitle,
            @Size(max = 10) List<@Valid MailClassChange> classChanges) {

        public MailItem {
            senderName = senderName == null ? "" : senderName;
            senderAddress = senderAddress == null ? "" : senderAddress;
            subject = subject == null ? "" : subject;
            categories = categories == null ? List.of() : categories;
            fromLecturer = fromLecturer != null && fromLecturer;
            dates = dates == null ? List.of() : dates;
            losesPoints = losesPoints != null && losesPoints;
            sorted = sorted == null || sorted;
            classChanges = classChanges == null ? List.of() : classChanges;
        }

        @AssertTrue(message = "categories must differ")
        boolean isEachCategoryOnce() {
            return new HashSet<>(categories).size() == categories.size();
        }
    }

    public record Outlook(
            @NotNull LocalDate since,
            @NotNull Boolean connected,
            @NotNull @Size(max = 2000) List<@Valid MailItem> emails) {
    }

    // ---- A whole sync -----------------------------------------------------------

    /** One part of a sync: {"status": "ok", "data": …} or {"status": "failed", "error_code": …, "error_message": …}. */
```

Change 4: replace

```java
            @Valid Section<Timetable> timetable,
            @Valid Section<Exams> exams,
            @Valid Section<Tuition> tuition,
            @Valid Section<Blackboard> blackboard) {

        @AssertTrue(message = "schema_version must be 1")
        boolean isVersion1() {
```

with

```java
            @Valid Section<Timetable> timetable,
            @Valid Section<Exams> exams,
            @Valid Section<Tuition> tuition,
            @Valid Section<Blackboard> blackboard,
            @Valid Section<Outlook> outlook) {

        @AssertTrue(message = "schema_version must be 1")
        boolean isVersion1() {
```

Change 5: replace

```java
            return errorCode != null || !sections().isEmpty();
        }

        /** The parts that were sent, by name, in the order timetable, exams, tuition, blackboard. */
        public Map<String, Section<?>> sections() {
            Map<String, Section<?>> sent = new LinkedHashMap<>();
            if (timetable != null) {
```

with

```java
            return errorCode != null || !sections().isEmpty();
        }

        /** The parts that were sent, by name, in the order timetable, exams, tuition, blackboard, outlook. */
        public Map<String, Section<?>> sections() {
            Map<String, Section<?>> sent = new LinkedHashMap<>();
            if (timetable != null) {
```

Change 6: replace

```java
            if (blackboard != null) {
                sent.put("blackboard", blackboard);
            }
            return sent;
        }
```

with

```java
            if (blackboard != null) {
                sent.put("blackboard", blackboard);
            }
            if (outlook != null) {
                sent.put("outlook", outlook);
            }
            return sent;
        }
```


- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest contract -q` → PASS. Run: `(cd web && ./mvnw -B test -Dtest=SyncContractTest)` → `Tests run: 63, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add contract web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java web/src/test/java/vn/edu/hcmiu/sla/school/sync
git commit -m "feat(contract): an outlook part in the upload: each email's sorting results, never its text

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The class-change reader in the agent, and sentences both readers check

The agent reads lecturers' emails with the Python reader that left with the Python website (`app/school/services/class_changes.py` at `471cac0^`); the Java reader keeps reading Blackboard announcements. `sentences.json` holds 45 example announcements with the changes each must give; both suites check all of them, so the two readers can't drift apart. The reader also gains `dates_in`, every date in a whole text (spec §5.4).

**Files:**
- Create: `contract/samples/class-changes/sentences.json`, `agent/sla_agent/class_changes.py`
- Test: `agent/tests/test_class_changes.py`, `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `sla_agent.class_changes.Announced(kind, day, start=None, end=None, room=None)` (a `NamedTuple`); `read_announcement(title, text, posted_at) -> list[Announced]` (`posted_at` naive UTC); `dates_in(text, from_day) -> list[date]` (sorted, each once, links ignored, only from `from_day` on).

- [ ] **Step 1: Write the shared sentences and the Python test**

`contract/samples/class-changes/sentences.json` (new):

```json
{
  "about": "Example announcements and the class changes each must give. Both the laptop agent's Python reader (agent/tests/test_class_changes.py) and the website's Java reader (ClassChangesTest) check every case, so the two readers can't drift apart. changes lists each distinct change once, in the order it is first found. posted_at is UTC without an offset; days and times are Vietnam time.",
  "cases": [
    {
      "name": "real: probability online",
      "title": "ONLINE CLASS ON SEPTEMBER 24",
      "text": "Dear all, The class on September 24 is online on MS TEAMS. Please use the following code to access the class.",
      "posted_at": "2026-09-23T16:07:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "real: web application online",
      "title": "Online Class Notification – Web Application – 22 September 2026",
      "text": "Dear Students, Please be informed that our Web Application class will be conducted online via Microsoft Teams. Date: Tuesday, 22 September 2026 Time: From 8:00 AM Platform: Microsoft Teams Online Class Link: https://teams.microsoft.com/l/meetup-join/19%3ameeting_x%40thread.v2/0?context=%7b%22Tid%22%3a%2212-10%22",
      "posted_at": "2026-09-15T16:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-22"
        }
      ]
    },
    {
      "name": "real: physics online",
      "title": "Link học online sáng thứ Sáu, 18/9/2026, 8g00-9g40",
      "text": "Link: Physics 4 Friday, September 18 Time zone: Asia/Ho_Chi_Minh Google Meet joining info Video call link: https://meet.google.com/abc-defg-hij",
      "posted_at": "2026-09-17T09:30:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-18"
        }
      ]
    },
    {
      "name": "real: probability cancelled",
      "title": "Cancel class on September 17",
      "text": "The class this week on September 17 will be canceled. The makeup schedule will be announced later",
      "posted_at": "2026-09-15T02:36:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-17"
        }
      ]
    },
    {
      "name": "real: only a sentence with a change word counts",
      "title": "Logistics Reminder",
      "text": "Lecture attendance: You will attend the first five lectures with me in person in Room A1.603 , with our last in-person lecture on 10/10. Attendance will be taken during these sessions. Starting the week of 12/10 , lectures will be taught online by Dr. Nguyen Van A via MS Teams. Please register your group in SkillsGroupTerm1-26-27_Sat.xlsx , available on our MS Teams Channel.",
      "posted_at": "2026-09-22T03:59:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-10-12"
        }
      ]
    },
    {
      "name": "date format: Online class on Sept. 24",
      "title": "Online class on Sept. 24",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "date format: Online class on 24th September",
      "title": "Online class on 24th September",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "date format: Online class on September 24th, 2026",
      "title": "Online class on September 24th, 2026",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "date format: Lớp học trực tuyến ngày 24 tháng 9 năm 2026",
      "title": "Lớp học trực tuyến ngày 24 tháng 9 năm 2026",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "date format: The lecture on 24/09 is online",
      "title": "The lecture on 24/09 is online",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Lớp nghỉ ngày 24/09",
      "title": "Lớp nghỉ ngày 24/09",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Hủy buổi học 24-9-2026",
      "title": "Hủy buổi học 24-9-2026",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: No class on Sep 24",
      "title": "No class on Sep 24",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Class on 24/9 is cancelled",
      "title": "Class on 24/9 is cancelled",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Nghỉ học ngày 24/9",
      "title": "Nghỉ học ngày 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Classes on 24/9 are cancelled",
      "title": "Classes on 24/9 are cancelled",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: Class cancellation on 24/9",
      "title": "Class cancellation on 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "cancelled: We are cancelling the lecture on 24/9",
      "title": "We are cancelling the lecture on 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "online: Lectures on 24/9 will be online",
      "title": "Lectures on 24/9 will be online",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "nothing (no class word): Submit your report online by 24/9",
      "title": "Submit your report online by 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (before the posting day): The class on September 10 was online",
      "title": "The class on September 10 was online",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (no date): Online class soon",
      "title": "Online class soon",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (times are not dates): Online class from 10:30-11:45 in A2.401",
      "title": "Online class from 10:30-11:45 in A2.401",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (dates inside links are ignored): Online class, see https://example.com/10-11/12",
      "title": "Online class, see https://example.com/10-11/12",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (classmates is not a class word): Tell your classmates to submit online by 24/9",
      "title": "Tell your classmates to submit online by 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (nor is classroom): The classroom booking system goes online on 24/9",
      "title": "The classroom booking system goes online on 24/9",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "nothing (a deadline, not a class): Nộp bài trực tuyến trước ngày 24/9 cho môn học",
      "title": "Nộp bài trực tuyến trước ngày 24/9 cho môn học",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": []
    },
    {
      "name": "range: No class on Thursday 24/9 (8-10)",
      "title": "No class on Thursday 24/9 (8-10)",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "range: online, 8-10am",
      "title": "The class on Thursday 24/9 will be online, 8-10am",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "range: Học bù ngày 3/10, tiết 10-12",
      "title": "Học bù ngày 3/10, tiết 10-12",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03"
        }
      ]
    },
    {
      "name": "paragraphs stay separate sentences",
      "title": "Notice",
      "text": "our class on 24/9 will be online via MS Teams\nReminder: Homework 2 is due in class on 1/10",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2026-09-24"
        }
      ]
    },
    {
      "name": "a date without a year is placed near the posting date",
      "title": "Online class on January 5",
      "text": "",
      "posted_at": "2026-12-28T02:00:00",
      "changes": [
        {
          "kind": "online",
          "day": "2027-01-05"
        }
      ]
    },
    {
      "name": "each date takes the nearest change word",
      "title": "The class on 26/9 is cancelled; make-up class on 3/10 from 8:00 to 9:40 in A2.401.",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-26"
        },
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "08:00",
          "end": "09:40",
          "room": "A2.401"
        }
      ]
    },
    {
      "name": "make-up: Học bù ngày 3/10",
      "title": "Học bù ngày 3/10",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03"
        }
      ]
    },
    {
      "name": "make-up: Make-up class on 3/10, 8g00-9g40",
      "title": "Make-up class on 3/10, 8g00-9g40",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "08:00",
          "end": "09:40"
        }
      ]
    },
    {
      "name": "make-up: Make up class on 3/10 at 13h15",
      "title": "Make up class on 3/10 at 13h15",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "13:15"
        }
      ]
    },
    {
      "name": "make-up: Makeup class online on 3/10, 1:15 PM",
      "title": "Makeup class online on 3/10, 1:15 PM",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "13:15",
          "room": "Online"
        }
      ]
    },
    {
      "name": "make-up: Make-up lecture on 3/10 from 8:00 AM to 9:40 AM, room R109",
      "title": "Make-up lecture on 3/10 from 8:00 AM to 9:40 AM, room R109",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "08:00",
          "end": "09:40",
          "room": "R109"
        }
      ]
    },
    {
      "name": "make-up: Make-up class at 8:00 on 3/10",
      "title": "Make-up class at 8:00 on 3/10",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "08:00"
        }
      ]
    },
    {
      "name": "make-up: Make-up class on 3/10 from 1:15 to 3:45 PM",
      "title": "Make-up class on 3/10 from 1:15 to 3:45 PM",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "13:15",
          "end": "15:45"
        }
      ]
    },
    {
      "name": "make-up: Make-up class on 3/10, 1:15-3:45pm",
      "title": "Make-up class on 3/10, 1:15-3:45pm",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "13:15",
          "end": "15:45"
        }
      ]
    },
    {
      "name": "make-up: Make-up class on 3/10 from 11:00 to 1:00 PM",
      "title": "Make-up class on 3/10 from 11:00 to 1:00 PM",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "11:00",
          "end": "13:00"
        }
      ]
    },
    {
      "name": "make-up: Thầy dạy bù ngày 3/10",
      "title": "Thầy dạy bù ngày 3/10",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-03"
        }
      ]
    },
    {
      "name": "a make-up takes the time and room next to its own date",
      "title": "Class on Thursday 24/9 13:15-15:45 cancelled, make-up class on Saturday 3/10 8:00-9:40 room A2.401",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "cancelled",
          "day": "2026-09-24"
        },
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "08:00",
          "end": "09:40",
          "room": "A2.401"
        }
      ]
    },
    {
      "name": "two make-ups in one sentence keep their own times",
      "title": "Make-up class on 1/10 at 8:00 and make-up class on 3/10 at 14:00 in R109",
      "text": "",
      "posted_at": "2026-09-20T02:00:00",
      "changes": [
        {
          "kind": "makeup",
          "day": "2026-10-01",
          "start": "08:00"
        },
        {
          "kind": "makeup",
          "day": "2026-10-03",
          "start": "14:00",
          "room": "R109"
        }
      ]
    }
  ]
}
```

`agent/tests/test_class_changes.py` (new):

```python
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


def test_dates_in_nothing():
    assert dates_in("", date(2026, 9, 24)) == []
    assert dates_in(None, date(2026, 9, 24)) == []
```


- [ ] **Step 2: Run it to see it fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_class_changes.py -q`
Expected: FAIL: `ModuleNotFoundError: No module named 'sla_agent.class_changes'`.

- [ ] **Step 3: Bring the reader back, with `dates_in`**

`agent/sla_agent/class_changes.py` (new):

```python
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
```


- [ ] **Step 4: Run it to see it pass**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_class_changes.py -q` → PASS (48 tests).

- [ ] **Step 5: The Java reader checks the same sentences**

It passes at once: the Java reader is already right. The test keeps the two in step from now on.

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`: 3 changes.

Change 1: replace

```java

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
```

with

```java

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
```

Change 2: replace

```java
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
```

with

```java
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
```

Change 3: replace

```java
                .containsOnly(expected);
    }

    @Test
    void onlyASentenceWithAChangeWordCounts() {
        // "in-person ... on 10/10" has no change word. "Starting the week of 12/10 ... online" is read as the
```

with

```java
                .containsOnly(expected);
    }

    /** contract/samples/class-changes/sentences.json: the laptop agent's Python reader checks the same cases. */
    static Stream<Arguments> sharedExamples() throws IOException {
        JsonNode cases = JsonMapper.builder().build()
                .readTree(Files.readString(Path.of("..", "contract", "samples", "class-changes", "sentences.json")))
                .get("cases");
        return cases.valueStream().map(c -> Arguments.of(c.get("name").asString(), c));
    }

    static LocalTime clock(JsonNode change, String field) {
        return change.hasNonNull(field) ? LocalTime.parse(change.get(field).asString()) : null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedExamples")
    void everySharedExample(String name, JsonNode example) {
        List<Announced> expected = example.get("changes").valueStream()
                .map(c -> new Announced(c.get("kind").asString(), LocalDate.parse(c.get("day").asString()),
                        clock(c, "start"), clock(c, "end"), c.hasNonNull("room") ? c.get("room").asString() : null))
                .toList();

        List<Announced> found = ClassChanges.readAnnouncement(example.get("title").asString(),
                example.get("text").asString(), LocalDateTime.parse(example.get("posted_at").asString()));

        // A title and a text that say the same thing give the same change twice; the file lists it once.
        assertThat(List.copyOf(new LinkedHashSet<>(found))).isEqualTo(expected);
    }

    @Test
    void onlyASentenceWithAChangeWordCounts() {
        // "in-person ... on 10/10" has no change word. "Starting the week of 12/10 ... online" is read as the
```


Run: `(cd web && ./mvnw -B test -Dtest=ClassChangesTest)` → `Tests run: 95, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add contract/samples/class-changes agent/sla_agent/class_changes.py agent/tests/test_class_changes.py web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java
git commit -m "feat(agent): the class-change reader for lecturers' emails, checked against sentences the Java reader checks too

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Sorting one email on the laptop

Pure functions (§5): who is a lecturer, the categories and the top two, dates, "loses points", the course a lecturer's email is about, the Blackboard title, and the class changes. `sort_email` never lets one broken email stop the others, and never logs anything the email said.

**Files:**
- Create: `agent/sla_agent/mail_rules.py`
- Test: `agent/tests/test_mail_rules.py`

**Interfaces:**
- Consumes: `MailItem`, `MailClassChange` (Task 1); `read_announcement`, `dates_in` (Task 2).
- Produces: `Email(key, entry_id, thread_id, received_at, sender_name, sender_address, subject, text)` (frozen dataclass; `received_at` aware); `Context(courses=(), bb_courses=())` with `courses` of `(code, name, lecturer)` and `bb_courses` of `(Blackboard name, code)`; `fold(text)`; `name_handles(name) -> set[str]`; `lecturer_handle(lecturer) -> str`; `is_microsoft_notice(email)`; `blackboard_lecturer(email) -> str | None`; `sender_handle(email) -> str | None`; `is_from_lecturer(email, context)`; `categories(email, from_lecturer) -> list[str]`; `course_of(email, context) -> str | None`; `sort_email(email, context) -> MailItem`.

- [ ] **Step 1: Write the failing tests**

`agent/tests/test_mail_rules.py` (new):

```python
"""Sorting one email on the laptop (spec 2026-09-28-outlook-mailbox-design.md, section 5)."""

import logging
from datetime import date, datetime, time, timezone

import pytest

from sla_agent import mail_rules
from sla_agent.mail_rules import Context, Email, categories, course_of, is_from_lecturer, name_handles, sort_email

ARRIVED = datetime(2026, 9, 21, 1, 5, tzinfo=timezone.utc)  # Mon 21/09 08:05 in Vietnam
CONTEXT = Context(
    courses=(("IT093IU", "Web Application Development", "P.Q.Hùng"),
             ("MA026IU", "Probability, Statistic & Random Process", "N.T.Hà"),
             ("PH012IU", "Physics 4", "Đ.V.Long")),
    bb_courses=(("Web Application Development_S1_2026-27_G02", "IT093IU"),
                ("Physics 4_S1_2026-27_G01", "PH012IU")),
)


def email(subject="Hello", text="", address="someone@example.com", name="Someone", received_at=ARRIVED):
    return Email(key="a" * 64, entry_id="00AB", thread_id="T1", received_at=received_at, sender_name=name,
                 sender_address=address, subject=subject, text=text)


# ---- who is a lecturer (5.1) --------------------------------------------------


@pytest.mark.parametrize("address, name", [
    ("bb@hcmiu.edu.vn", "Tran Van An - tvan@hcmiu.edu.vn"),  # a Blackboard announcement
    ("pqhung@hcmiu.edu.vn", "Web teacher"),  # P.Q.Hùng on the timetable
    ("vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"),  # initials + given name
    ("pqhung@hcmiu.edu.vn", "Hung Quoc Pham"),  # the name written the other way round
    ("buithanhnga@mp.hcmiu.edu.vn", "BUI THANH NGA"),  # the whole name, on an IU sub-domain
], ids=["blackboard", "timetable", "initials", "other-order", "whole-name-subdomain"])
def test_lecturers(address, name):
    assert is_from_lecturer(email(address=address, name=name), CONTEXT)


@pytest.mark.parametrize("address, name", [
    ("bb@hcmiu.edu.vn", "bb@hcmiu.edu.vn"),  # a "Submission received" receipt
    ("oss@hcmiu.edu.vn", "P.CTSV [OSS]"),
    ("hoisinhvien@hcmiu.edu.vn", "Hoi Sinh Vien IU"),
    ("iuyouth@hcmiu.edu.vn", "ĐOÀN TRƯỜNG ĐH QUỐC TẾ - IUYOUTH"),
    ("noreply.cis@hcmiu.edu.vn", "Reset Password"),
    ("iuoss@hcmiu.edu.vn", "IUOSS"),  # one word is not a person's name
    ("ttmai@student.hcmiu.edu.vn", "Tran Thi Mai"),  # a student
    ("vmkhoa@gmail.com", "Vo Minh Khoa"),  # not an IU address
], ids=["receipt", "oss", "union", "youth", "reset", "one-word", "student", "gmail"])
def test_not_lecturers(address, name):
    assert not is_from_lecturer(email(address=address, name=name), CONTEXT)


def test_a_teams_notice_from_a_lecturers_address_is_not_lecturer_mail():
    notice = email("Bạn đã được thêm vào một nhóm trong Microsoft Teams", address="dhtai@hcmiu.edu.vn",
                   name="Do Huu Tai")

    assert not is_from_lecturer(notice, CONTEXT)
    assert categories(notice, False) == ["class", "system_notice"]


def test_name_handles():
    assert name_handles("Võ Minh Khoa") == {"vmkhoa", "vominhkhoa", "kmvo", "khoaminhvo"}
    assert name_handles("Đặng Văn Long") >= {"dvlong"}
    assert name_handles("IUOSS") == set()


# ---- categories (5.2, 5.3) ------------------------------------------------------


@pytest.mark.parametrize("subject, text, address, expected", [
    ("[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", "", "noreply@kiemtrahoadon.com.vn", ["money"]),
    ("[Thông báo] Chương trình học bổng của Tập đoàn Điện lực Việt Nam năm học 2026", "", "oss@hcmiu.edu.vn",
     ["money"]),
    ("Thông báo về việc đăng ký tạm trú và cập nhật thông tin cư trú", "", "oss@hcmiu.edu.vn", ["school_task"]),
    ("THÔNG BÁO: CHƯƠNG TRÌNH SINH HOẠT CÔNG DÂN GIỮA KHÓA (2026 - 2027)", "", "oss@hcmiu.edu.vn", ["school_task"]),
    ('V/v thực hiện "Khảo sát sự hài lòng của người học"', "SV tham gia đánh giá sẽ được cộng điểm rèn luyện.",
     "oss@hcmiu.edu.vn", ["school_task", "training_points"]),
    ('[THƯ MỜI] sinh viên tham gia WORKSHOP “TỪ GIẢNG ĐƯỜNG TỚI CÔNG SỞ"', "✨ Tích lũy điểm rèn luyện.",
     "oss@hcmiu.edu.vn", ["event", "training_points"]),
    ("[HSV] - Thư mời đăng ký tham gia Chương trình “Job hay ‘bẫy’?”",
     "Được công nhận hoạt động theo Quy chế sinh viên.", "hoisinhvien@hcmiu.edu.vn", ["event"]),
    ("[Thông báo] TIẾP NHẬN SINH VIÊN THAM GIA WORKSHOP: HỌC IELTS HIỆU QUẢ",
     "Nhận các phần quà và ưu đãi học tập hấp dẫn từ DOL English. Nhận điểm rèn luyện.", "oss@hcmiu.edu.vn",
     ["event", "training_points"]),
    ("[Thông báo] Mời sinh viên ủng hộ trường theo chương trình [IU x beFood] Bứt phá Vòng Chung Kết",
     "mang về giải thưởng học bổng 25.000.000 VNĐ. Nhập mã trường để áp dụng ưu đãi giảm 30%.", "oss@hcmiu.edu.vn",
     ["promotion"]),
    ("THÔNG TIN VỀ CUỘC THI TIẾNG ANH STAR AWARD", "Điểm rèn luyện chỉ được ghi nhận 01 lần.",
     "iuyouth@hcmiu.edu.vn", ["event", "training_points"]),
    ("Cảm ơn bạn đã điền vào biểu mẫu này: CHECK-OUT: GEMINI ACADEMY FOR STUDENTS | WORKSHOP 3", "",
     "forms-receipts-noreply@google.com", ["event"]),
    ("[Ticket: 12345] Trần Thị Mai – Yêu cầu mới được tạo", "", "oss@hcmiu.edu.vn", ["requests_account"]),
    ("Reset your password", "", "noreply.cis@hcmiu.edu.vn", ["requests_account"]),
    ("Your Teams meeting recording has expired and is now deleted", "", "no-reply@sharepointonline.com",
     ["system_notice"]),
    ("Submission received", "", "bb@hcmiu.edu.vn", ["class"]),
    ("Hello", "Nothing to see.", "friend@example.com", []),
], ids=["invoice", "scholarship", "residence", "civic-education", "survey", "workshop", "job-talk-no-points",
        "ielts-top-two", "befood-prize-is-not-money", "contest", "form-receipt", "ticket", "password", "sharepoint",
        "receipt", "nothing"])
def test_categories(subject, text, address, expected):
    assert categories(email(subject, text, address, name=address), False) == expected


def test_a_lecturers_email_is_class_first():
    assert categories(email("Thư mời workshop", "điểm rèn luyện"), True) == ["class", "event"]


def test_loses_points():
    assert sort_email(email("Workshop", "Sinh viên đã đăng ký mà vắng sẽ bị trừ 05 điểm rèn luyện."), CONTEXT) \
        .loses_points
    assert not sort_email(email("Workshop", "Được cộng điểm rèn luyện."), CONTEXT).loses_points


# ---- dates (5.4) -------------------------------------------------------------------


def test_dates_come_from_the_subject_and_text_from_the_day_it_arrived():
    item = sort_email(email("Workshop ngày 29/09/2026", "Hạn đăng ký: 20/9. Check-in 13:00, 29/9."), CONTEXT)

    assert item.dates == [date(2026, 9, 29)]


# ---- class changes (5.5) -------------------------------------------------------------


def test_a_blackboard_copy_names_its_course_and_title():
    item = sort_email(email("Web Application Development_S1_2026-27_G02: Online class on 22/9", "",
                            "bb@hcmiu.edu.vn", "Tran Van An - tvan@hcmiu.edu.vn"), CONTEXT)

    assert (item.from_lecturer, item.blackboard_title) == (True, "Online class on 22/9")
    assert [(c.course_code, c.kind, c.day) for c in item.class_changes] == [("IT093IU", "online", date(2026, 9, 22))]


def test_an_email_naming_one_course_changes_that_course():
    item = sort_email(email("Physics 4: make-up class", "Make-up class on 3/10 from 1:15 to 3:45 PM in A2.401",
                            "vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"), CONTEXT)

    [change] = item.class_changes
    assert (change.course_code, change.kind, change.start, change.end, change.room) == (
        "PH012IU", "makeup", time(13, 15), time(15, 45), "A2.401")


def test_a_timetable_lecturer_changes_their_own_course():
    item = sort_email(email("Class cancelled", "No class on 24/9.", "pqhung@hcmiu.edu.vn", "Hung Quoc Pham"), CONTEXT)

    assert [(c.course_code, c.kind) for c in item.class_changes] == [("IT093IU", "cancelled")]


@pytest.mark.parametrize("subject, text, address, name", [
    ("Online class on 24/9", "", "oss@hcmiu.edu.vn", "P.CTSV [OSS]"),  # not a lecturer
    ("Online class on 24/9", "", "vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"),  # no course named, not on the timetable
    ("IT093IU and MA026IU: no class on 24/9", "", "vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"),  # two courses
], ids=["not-a-lecturer", "no-course", "two-courses"])
def test_no_class_change_without_a_lecturer_and_one_course(subject, text, address, name):
    assert sort_email(email(subject, text, address, name), CONTEXT).class_changes == []


def test_course_of_prefers_the_blackboard_course_name():
    copy = email("Physics 4_S1_2026-27_G01: Web Application Development notes", "", "bb@hcmiu.edu.vn",
                 "Đặng Văn Long - dvlong@hcmiu.edu.vn")

    assert course_of(copy, CONTEXT) == "PH012IU"


# ---- the whole email ------------------------------------------------------------------


def test_the_text_never_leaves_in_the_result():
    item = sort_email(email("Workshop", "UNIQUE-TEXT-7f3a only the laptop may read this"), CONTEXT)

    assert "UNIQUE-TEXT-7f3a" not in item.model_dump_json()


def test_an_email_the_rules_fail_on_is_uploaded_unsorted(monkeypatch, caplog):
    def broken(*args):
        raise ValueError("SECRET-SUBJECT-TEXT")

    monkeypatch.setattr(mail_rules, "categories", broken)
    caplog.set_level(logging.WARNING)

    item = sort_email(email("Tạm trú"), CONTEXT)

    assert (item.sorted, item.categories, item.subject) == (False, [], "Tạm trú")
    assert "SECRET-SUBJECT-TEXT" not in caplog.text
```


- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_mail_rules.py -q`
Expected: FAIL: `ModuleNotFoundError: No module named 'sla_agent.mail_rules'`.

- [ ] **Step 3: Write the rules**

`agent/sla_agent/mail_rules.py` (new):

```python
"""Sorting one email on the laptop: who sent it, its categories, its dates, and the class changes it
announces. Pure functions. The rules are in docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md,
section 5.

The email's text is only read here, in memory. What leaves this module is a MailItem, which has no
field for text."""

import logging
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sla_contract.schema import MailClassChange, MailItem

from sla_agent.class_changes import dates_in, read_announcement

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


def fold(text):
    """Lower case without accents: "Hóa Đơn" -> "hoa don"."""
    text = unicodedata.normalize("NFD", (text or "").replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


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
LOSES_POINTS = re.compile(r"\btru\b.{0,30}?\bdiem\s+ren\s+luyen\b", re.DOTALL)


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


def loses_points(email):
    """Spec 5.4: "trừ" followed within 30 characters by "điểm rèn luyện"."""
    return bool(LOSES_POINTS.search(fold(email.subject) + "\n" + fold(email.text)))


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
            loses_points=loses_points(email),
            blackboard_title=blackboard_title(email),
            class_changes=class_changes(email, code) if code else [],
        )
    except Exception as error:  # one email must never stop the others; the message could quote the email
        log.warning("Couldn't sort an email (%s); it is uploaded unsorted", error.__class__.__name__)
        return MailItem(**known, sorted=False)
```


- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_mail_rules.py -q` → PASS (43 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/mail_rules.py agent/tests/test_mail_rules.py
git commit -m "feat(agent): sort each email on the laptop: lecturers, categories, dates and class changes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Reading the Inbox through classic Outlook

`outlook_reader.py` reads the chosen account's Inbox through Outlook's Windows programming interface (COM) and hands every email to `sort_email`. It is read-only by construction (a test scans its source), waits for a freshly started Outlook to connect, gives up after 3 minutes, and turns Outlook's refusals into `OutlookBlocked`. pywin32 is imported only inside `open_outlook`; the tests use `outlook_fakes.py`, so they run anywhere.

**Files:**
- Create: `agent/sla_agent/outlook_reader.py`
- Modify: `agent/sla_agent/errors.py`
- Test: `agent/tests/outlook_fakes.py`, `agent/tests/test_outlook_reader.py`

**Interfaces:**
- Consumes: `Outlook` (Task 1); `Email`, `sort_email`, `Context` (Task 3).
- Produces:
  - `errors.py`: `OutlookNotSetUp` (code `outlook_not_set_up`), `OutlookBlocked` (code `outlook_blocked`), `EmailNotFound`.
  - `outlook_reader.py`: `semester_start(term_code, today) -> date`; `open_outlook()` (the running or a new hidden Outlook; Windows only); `accounts(app) -> list[str]`; `inbox_of(app, address) -> (namespace, inbox)`; `read_emails(inbox, since) -> (list[Email], skipped)`; `with_time_limit(work, limit=TIME_LIMIT)`; `read_outlook(address, since, context, *, open_outlook=…, sleep=…, limit=…) -> Outlook`; `entry_id_from_link(link) -> str | None`; `open_email(entry_id, open_outlook=…)`; constants `PR_DELIVERY_TIME`, `PR_SENDER_SMTP`, `PR_MESSAGE_ID`, `TIME_LIMIT` (3 minutes), `CONNECT_WAIT` (30 seconds), `MAX_EMAILS` (2000).

- [ ] **Step 1: Write the fake Outlook and the failing tests**

`agent/tests/outlook_fakes.py` (new):

```python
"""A stand-in for classic Outlook's COM objects, with only what the agent uses. Collections are plain lists;
properties that fail raise ComError with Outlook's error code, as pywin32's com_error does."""

from datetime import datetime, timedelta, timezone

from sla_agent.outlook_reader import PR_DELIVERY_TIME, PR_MESSAGE_ID, PR_SENDER_SMTP

NOT_FOUND = -2147221233  # MAPI_E_NOT_FOUND: the property isn't there
E_ABORT = -2147467260  # what Outlook answers when its security prompt is refused


class ComError(Exception):
    def __init__(self, hresult):
        super().__init__(f"COM error {hresult}")
        self.hresult = hresult


class FakeProperties:
    def __init__(self, values):
        self.values = values

    def GetProperty(self, tag):
        value = self.values.get(tag)
        if isinstance(value, Exception):
            raise value
        if value is None:
            raise ComError(NOT_FOUND)
        return value


class FakeMail:
    """One email. `received` is aware UTC; Outlook's ReceivedTime is the same moment as Vietnam wall time,
    wrongly labelled UTC, as pywin32 gives it."""

    Class = 43

    def __init__(self, subject, received, body="", address="someone@example.com", name="Someone",
                 message_id="<m@example.com>", entry_id="00AB", thread="T1", exchange_address=None, broken=None):
        self.Subject, self.SenderName, self.EntryID, self.ConversationID = subject, name, entry_id, thread
        self.SenderEmailType = "EX" if exchange_address else "SMTP"
        self.SenderEmailAddress = "/O=EXCHANGELABS/OU=EXCHANGE/CN=RECIPIENTS/CN=X" if exchange_address else address
        self.ReceivedTime = (received + timedelta(hours=7)).replace(tzinfo=timezone.utc)
        self.PropertyAccessor = FakeProperties({PR_DELIVERY_TIME: received, PR_MESSAGE_ID: message_id,
                                                PR_SENDER_SMTP: exchange_address})
        self._body, self.broken, self.displayed = body, broken, 0

    @property
    def Body(self):
        if self.broken:
            raise self.broken
        return self._body

    def Display(self):
        self.displayed += 1


class FakeMeeting(FakeMail):
    Class = 53  # olMeetingRequest


class FakeItems:
    def __init__(self, items):
        self.items, self.position = list(items), 0

    def Sort(self, field, descending):
        assert (field, descending) == ("[ReceivedTime]", True)
        self.items.sort(key=lambda item: item.ReceivedTime, reverse=True)

    def GetFirst(self):
        self.position = 0
        return self.GetNext()

    def GetNext(self):
        if self.position >= len(self.items):
            return None
        self.position += 1
        return self.items[self.position - 1]


class FakeFolder:
    def __init__(self, items):
        self.Items = FakeItems(items)


class FakeStore:
    def __init__(self, inbox):
        self.inbox = inbox

    def GetDefaultFolder(self, number):
        assert number == 6  # olFolderInbox
        return self.inbox


class FakeAccount:
    def __init__(self, address, mails=()):
        self.SmtpAddress = address
        self.DeliveryStore = FakeStore(FakeFolder(mails))


class FakeNamespace:
    def __init__(self, accounts, modes=(700,)):
        self.Accounts = list(accounts)
        self.modes = list(modes)

    @property
    def ExchangeConnectionMode(self):
        return self.modes.pop(0) if len(self.modes) > 1 else self.modes[0]

    def GetItemFromID(self, entry_id):
        for account in self.Accounts:
            for item in account.DeliveryStore.inbox.Items.items:
                if item.EntryID == entry_id:
                    return item
        raise ComError(NOT_FOUND)


class FakeOutlook:
    def __init__(self, *accounts, modes=(700,)):
        self.namespace = FakeNamespace(accounts, modes)

    def GetNamespace(self, name):
        assert name == "MAPI"
        return self.namespace


def at(day, hour=1, minute=0):
    """An aware UTC time on a day of September 2026 (hour 1 UTC is 08:00 in Vietnam)."""
    return datetime(2026, 9, day, hour, minute, tzinfo=timezone.utc)
```

`agent/tests/test_outlook_reader.py` (new):

```python
"""Reading the Inbox through classic Outlook, with a fake Outlook (no Windows needed)."""

import hashlib
import logging
import re
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from agent.tests.outlook_fakes import (
    E_ABORT,
    ComError,
    FakeAccount,
    FakeMail,
    FakeMeeting,
    FakeOutlook,
    at,
)
from sla_agent import outlook_reader
from sla_agent.errors import EmailNotFound, OutlookBlocked, OutlookNotSetUp
from sla_agent.mail_rules import Context
from sla_agent.outlook_reader import (
    accounts,
    entry_id_from_link,
    open_email,
    read_outlook,
    semester_start,
)

ME = "ititiu99001@student.hcmiu.edu.vn"
SINCE = date(2026, 8, 1)
CONTEXT = Context(courses=(("IT093IU", "Web Application Development", "P.Q.Hùng"),))


def read(outlook, address=ME, since=SINCE, sleeps=None, **options):
    return read_outlook(address, since, CONTEXT, open_outlook=lambda: outlook,
                        sleep=(sleeps.append if sleeps is not None else lambda seconds: None), **options)


@pytest.mark.parametrize("term, expected", [
    ("20261", date(2026, 8, 1)),
    ("20262", date(2027, 1, 1)),
    ("20263", date(2027, 6, 1)),
    (None, date(2026, 6, 30)),
    ("2026", date(2026, 6, 30)),
])
def test_semester_start(term, expected):
    assert semester_start(term, date(2026, 9, 28)) == expected


def test_reads_the_chosen_accounts_inbox_emails_from_the_semester_start():
    mine = FakeAccount(ME, [
        FakeMail("Workshop ngày 29/09/2026", at(24), "Tích lũy điểm rèn luyện.", "oss@hcmiu.edu.vn", "P.CTSV [OSS]",
                 entry_id="00A1"),
        FakeMeeting("Meeting", at(23), entry_id="00A2"),
        FakeMail("First day in Vietnam", datetime(2026, 7, 31, 17, 30, tzinfo=timezone.utc), entry_id="00A3"),
        FakeMail("Last day of July in Vietnam", datetime(2026, 7, 31, 16, 59, tzinfo=timezone.utc), entry_id="00A4"),
    ])
    other = FakeAccount("me@gmail.com", [FakeMail("Private", at(25), entry_id="00B1")])

    result = read(FakeOutlook(other, mine))

    assert (result.since, result.connected) == (SINCE, True)
    assert [e.subject for e in result.emails] == ["Workshop ngày 29/09/2026", "First day in Vietnam"]
    workshop = result.emails[0]
    assert workshop.categories == ["event", "training_points"]
    assert workshop.dates == [date(2026, 9, 29)]
    assert workshop.received_at == at(24)
    assert workshop.key == hashlib.sha256(b"<m@example.com>").hexdigest()
    assert (workshop.entry_id, workshop.thread_id, workshop.sender_address) == ("00A1", "T1", "oss@hcmiu.edu.vn")


def test_the_text_never_leaves_the_laptop():
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hello", at(24), "UNIQUE-TEXT-9d2e for the laptop only")]))

    assert "UNIQUE-TEXT-9d2e" not in read(outlook).model_dump_json()


def test_an_exchange_sender_gets_their_real_address():
    mail = FakeMail("Re: Slide", at(24), "", name="Vo Minh Khoa", exchange_address="vmkhoa@hcmiu.edu.vn")

    [email] = read(FakeOutlook(FakeAccount(ME, [mail]))).emails

    assert (email.sender_address, email.from_lecturer) == ("vmkhoa@hcmiu.edu.vn", True)


def test_without_a_message_id_the_key_comes_from_outlooks_id():
    [email] = read(FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24), message_id=None, entry_id="00C7")]))).emails

    assert email.key == hashlib.sha256(b"00C7").hexdigest()


def test_without_a_delivery_time_it_reads_outlooks_local_time():
    mail = FakeMail("Hi", at(24))
    mail.PropertyAccessor.values.pop(outlook_reader.PR_DELIVERY_TIME)

    [email] = read(FakeOutlook(FakeAccount(ME, [mail]))).emails

    assert email.received_at == datetime(2026, 9, 24, 8, 0).astimezone(timezone.utc)


def test_it_waits_for_outlook_to_connect():
    sleeps = []

    result = read(FakeOutlook(FakeAccount(ME), modes=(400, 400, 700)), sleeps=sleeps)

    assert (result.connected, sleeps) == (True, [2, 2])


def test_an_offline_outlook_still_gives_what_it_has():
    sleeps = []
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24))]), modes=(400,))

    result = read(outlook, sleeps=sleeps)

    assert (result.connected, len(result.emails), sum(sleeps)) == (False, 1, 30)


def test_an_account_that_is_gone_means_outlook_is_not_set_up():
    with pytest.raises(OutlookNotSetUp, match="no longer in Outlook"):
        read(FakeOutlook(FakeAccount("someone@else.com")))


def test_no_outlook_means_not_set_up():
    def missing():
        raise OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")

    with pytest.raises(OutlookNotSetUp):
        read_outlook(ME, SINCE, CONTEXT, open_outlook=missing)


def test_outlook_refusing_the_read_blocks_it():
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Hi", at(24), broken=ComError(E_ABORT))]))

    with pytest.raises(OutlookBlocked, match="didn't let the agent"):
        read(outlook)


def test_any_other_com_error_from_outlook_blocks_it():
    class Broken:
        def GetNamespace(self, name):
            raise ComError(-2147023174)  # RPC server unavailable

    with pytest.raises(OutlookBlocked):
        read(Broken())


def test_one_unreadable_email_is_skipped(caplog):
    caplog.set_level(logging.WARNING)
    outlook = FakeOutlook(FakeAccount(ME, [FakeMail("Broken", at(25), broken=ComError(-2147221233), entry_id="00D1"),
                                           FakeMail("Fine", at(24), entry_id="00D2")]))

    assert [e.subject for e in read(outlook).emails] == ["Fine"]
    assert "Skipped 1 emails" in caplog.text


def test_an_outlook_that_does_not_answer_is_given_up_after_the_time_limit():
    stuck = threading.Event()

    def never_answers():
        stuck.wait(5)
        return FakeOutlook(FakeAccount(ME))

    with pytest.raises(OutlookBlocked, match="within 3 minutes"):
        read_outlook(ME, SINCE, CONTEXT, open_outlook=never_answers, limit=timedelta(seconds=0.2))
    stuck.set()


def test_at_most_2000_emails_newest_first():
    mails = [FakeMail(f"Mail {i}", at(1) + timedelta(minutes=i), message_id=f"<{i}@x>", entry_id=f"{i:04X}")
             for i in range(2005)]

    result = read(FakeOutlook(FakeAccount(ME, mails)))

    assert (len(result.emails), result.emails[0].subject) == (2000, "Mail 2004")


def test_accounts():
    assert accounts(FakeOutlook(FakeAccount(ME), FakeAccount("me@gmail.com"))) == [ME, "me@gmail.com"]


@pytest.mark.parametrize("link, expected", [
    ("sla-mail:00AB12", "00AB12"),
    ("sla-mail:00AB12/", "00AB12"),
    ("sla-mail:00ab12", None),
    ("javascript:alert(1)", None),
    ("sla-mail:" + "A" * 513, None),
    ("", None),
])
def test_entry_id_from_link(link, expected):
    assert entry_id_from_link(link) == expected


def test_open_email_shows_it_in_outlook():
    mail = FakeMail("Hi", at(24), entry_id="00E1")

    open_email("00E1", open_outlook=lambda: FakeOutlook(FakeAccount(ME, [mail])))

    assert mail.displayed == 1


def test_open_email_that_is_gone():
    with pytest.raises(EmailNotFound):
        open_email("00E9", open_outlook=lambda: FakeOutlook(FakeAccount(ME)))


def test_the_reader_only_reads():
    source = Path(outlook_reader.__file__).read_text(encoding="utf-8")
    code = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
    code = re.sub(r'"""[\s\S]*?"""', "", code)

    assert not re.findall(r"\.(Send|Delete|Move|Copy|Save|SaveAs|UnRead|MarkAsRead)\b", code)
```


- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_outlook_reader.py -q`
Expected: FAIL: `ModuleNotFoundError: No module named 'sla_agent.outlook_reader'` (from `outlook_fakes.py`).

- [ ] **Step 3: Add the errors and the reader**

`agent/sla_agent/errors.py`: one change.

Replace

```python

class RunInProgress(ServerError):
    pass
```

with

```python

class RunInProgress(ServerError):
    pass


class OutlookNotSetUp(AgentError):
    """Classic Outlook is missing, has no account, or the account chosen at setup is gone."""

    code = "outlook_not_set_up"


class OutlookBlocked(AgentError):
    """Outlook refused to be read, or didn't answer in time (e.g. a security prompt nobody answered).
    The agent never answers Outlook's prompts."""

    code = "outlook_blocked"


class EmailNotFound(AgentError):
    """The email a link points to is no longer in Outlook."""
```

`agent/sla_agent/outlook_reader.py` (new):

```python
"""Reading the Inbox through classic Outlook on this laptop (Windows COM), and opening one email.

Read only: this module reads item properties and, to show an email to the student, calls Display().
It never sends, deletes, moves, copies or saves anything, and never marks mail as read; a test
checks its source for those names. It also never answers Outlook's security prompts: if Outlook
blocks the read, or doesn't answer within TIME_LIMIT, the Outlook part fails with a message.

Outlook closes by itself when the agent lets go of it and no Outlook window is open (checked
2026-09-28), so the agent never closes it and can't close a window the student opened.

pywin32 is imported only when Outlook is really used, so the tests run anywhere with fakes."""

import hashlib
import logging
import re
import threading
import time as clock
from datetime import date, datetime, time, timedelta, timezone

from sla_contract.schema import Outlook

from sla_agent import mail_rules
from sla_agent.errors import EmailNotFound, OutlookBlocked, OutlookNotSetUp
from sla_agent.mail_rules import Email

log = logging.getLogger(__name__)

OL_FOLDER_INBOX = 6
OL_MAIL = 43  # olMail: emails only, not meeting requests or delivery reports
CONNECTED_MODES = (500, 600, 700, 800)  # olCachedConnectedHeaders/Drizzle/Full, olOnline
PR_DELIVERY_TIME = "http://schemas.microsoft.com/mapi/proptag/0x0E060040"  # UTC; ReceivedTime is local
PR_SENDER_SMTP = "http://schemas.microsoft.com/mapi/proptag/0x5D01001F"
PR_MESSAGE_ID = "http://schemas.microsoft.com/mapi/proptag/0x1035001F"
REFUSED = (-2147467260, -2147024891, -2147418111)  # E_ABORT, E_ACCESSDENIED, RPC_E_CALL_REJECTED
TIME_LIMIT = timedelta(minutes=3)
CONNECT_WAIT = timedelta(seconds=30)  # a hidden Outlook needs ~5 s to connect after it starts
MAX_EMAILS = 2000
VIETNAM_OFFSET = timedelta(hours=7)
LINK = re.compile(r"sla-mail:([0-9A-F]{2,512})/?")

NOT_SET_UP = "Classic Outlook isn't set up on this laptop."
BLOCKED = "Outlook didn't let the agent read your mail."
TOO_SLOW = "Outlook didn't answer within 3 minutes (a security prompt may be waiting)."


def semester_start(term_code, today):
    """The first day to read mail from (spec 4.2): EduSoft's term code YYYYS gives 1 August of YYYY for
    semester 1, 1 January of YYYY+1 for semester 2 and 1 June of YYYY+1 for semester 3; otherwise 90 days ago."""
    if term_code and re.fullmatch(r"\d{4}[123]", term_code):
        year, part = int(term_code[:4]), term_code[4]
        return {"1": date(year, 8, 1), "2": date(year + 1, 1, 1), "3": date(year + 1, 6, 1)}[part]
    return today - timedelta(days=90)


def open_outlook():
    """The running Outlook, or a new hidden one. Windows only."""
    try:
        import pythoncom
        import pywintypes
        import win32com.client
    except ImportError as error:
        raise OutlookNotSetUp(NOT_SET_UP + " (It works only on Windows with classic Outlook.)") from error
    pythoncom.CoInitialize()
    try:
        return win32com.client.GetActiveObject("Outlook.Application")
    except pywintypes.com_error:
        pass
    try:
        return win32com.client.Dispatch("Outlook.Application")
    except pywintypes.com_error as error:
        raise OutlookNotSetUp(NOT_SET_UP) from error


def _refused(error):
    return getattr(error, "hresult", None) in REFUSED


def accounts(app):
    """The email addresses of Outlook's accounts."""
    return [account.SmtpAddress for account in app.GetNamespace("MAPI").Accounts if account.SmtpAddress]


def inbox_of(app, address):
    """(namespace, Inbox folder) of the account with this address."""
    namespace = app.GetNamespace("MAPI")
    for account in namespace.Accounts:
        if (account.SmtpAddress or "").lower() == address.lower():
            return namespace, account.DeliveryStore.GetDefaultFolder(OL_FOLDER_INBOX)
    raise OutlookNotSetUp(f"{NOT_SET_UP} Its account {address} is no longer in Outlook.")


def wait_until_connected(namespace, sleep=clock.sleep, wait=CONNECT_WAIT):
    """True once Outlook is connected to the mail server; False if it stays offline for `wait`."""
    waited = 0.0
    while namespace.ExchangeConnectionMode not in CONNECTED_MODES:
        if waited >= wait.total_seconds():
            return False
        sleep(2)
        waited += 2
    return True


def _plain(moment):
    return datetime(*moment.timetuple()[:6], moment.microsecond)


def _received(item):
    """When the email arrived, as aware UTC. PR_MESSAGE_DELIVERY_TIME is UTC; pywin32 labels Outlook's
    ReceivedTime as UTC although it is local time, so that is only the fallback."""
    try:
        return _plain(item.PropertyAccessor.GetProperty(PR_DELIVERY_TIME)).replace(tzinfo=timezone.utc)
    except Exception as error:
        if _refused(error):
            raise
        return _plain(item.ReceivedTime).astimezone(timezone.utc)


def _sender_address(item):
    if item.SenderEmailType == "EX":  # an IU colleague stored in Exchange's own format
        try:
            return item.PropertyAccessor.GetProperty(PR_SENDER_SMTP)
        except Exception as error:
            if _refused(error):
                raise
            try:
                return item.Sender.GetExchangeUser().PrimarySmtpAddress
            except Exception:
                return ""
    return item.SenderEmailAddress or ""


def _key(item):
    """SHA-256 of the internet message ID (the same on every device), else of Outlook's ID."""
    try:
        message_id = item.PropertyAccessor.GetProperty(PR_MESSAGE_ID)
    except Exception as error:
        if _refused(error):
            raise
        message_id = ""
    return hashlib.sha256((message_id or item.EntryID).encode("utf-8")).hexdigest()


def _email(item):
    return Email(key=_key(item), entry_id=item.EntryID.upper(), thread_id=item.ConversationID or None,
                 received_at=_received(item), sender_name=item.SenderName or "",
                 sender_address=_sender_address(item), subject=item.Subject or "", text=item.Body or "")


def read_emails(inbox, since):
    """(emails, skipped): the Inbox's emails received from `since` (a Vietnam date) on, newest first.
    An email that can't be read is skipped; Outlook refusing access raises OutlookBlocked."""
    start = datetime.combine(since, time(), tzinfo=timezone.utc) - VIETNAM_OFFSET
    items = inbox.Items
    items.Sort("[ReceivedTime]", True)
    emails, skipped = [], 0
    item = items.GetFirst()
    while item is not None:
        try:
            if item.Class == OL_MAIL:
                email = _email(item)
                if email.received_at < start:
                    break
                emails.append(email)
        except Exception as error:
            if _refused(error):
                raise OutlookBlocked(BLOCKED) from error
            skipped += 1
        item = items.GetNext()
    return emails, skipped


def with_time_limit(work, limit=TIME_LIMIT):
    """work()'s result, or OutlookBlocked when it takes longer than `limit`. A stuck Outlook call can't be
    stopped, so it is left in a background thread that ends with the program."""
    result = {}

    def run():
        try:
            result["value"] = work()
        except BaseException as error:  # handed to the caller below
            result["error"] = error

    worker = threading.Thread(target=run, name="outlook-read", daemon=True)
    worker.start()
    worker.join(limit.total_seconds())
    if worker.is_alive():
        raise OutlookBlocked(TOO_SLOW)
    if "error" in result:
        raise result["error"]
    return result["value"]


def read_outlook(address, since, context, *, open_outlook=open_outlook, sleep=clock.sleep, limit=TIME_LIMIT):
    """The Outlook part of a sync: every Inbox email from `since` on, sorted on this laptop. No text leaves."""

    def work():
        try:
            namespace, inbox = inbox_of(open_outlook(), address)
            connected = wait_until_connected(namespace, sleep)
            emails, skipped = read_emails(inbox, since)
        except (OutlookNotSetUp, OutlookBlocked):
            raise
        except Exception as error:
            if hasattr(error, "hresult"):  # any other COM error: Outlook refused
                raise OutlookBlocked(BLOCKED) from error
            raise
        return connected, emails, skipped

    connected, emails, skipped = with_time_limit(work, limit)
    if skipped:
        log.warning("Skipped %d emails Outlook couldn't read", skipped)
    items = [mail_rules.sort_email(email, context) for email in emails[:MAX_EMAILS]]
    return Outlook(since=since, connected=connected, emails=items)


def entry_id_from_link(link):
    """The Outlook ID in an "sla-mail:<hex>" link (one trailing / allowed), else None."""
    match = LINK.fullmatch((link or "").strip())
    return match.group(1) if match else None


def open_email(entry_id, open_outlook=open_outlook):
    """Shows one email in classic Outlook, for the student to read."""
    namespace = open_outlook().GetNamespace("MAPI")
    try:
        item = namespace.GetItemFromID(entry_id)
    except Exception as error:
        raise EmailNotFound("This email is no longer in your Outlook Inbox.") from error
    item.Display()
```


- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_outlook_reader.py -q` → PASS (29 tests). The time-limit test takes about 0.2 seconds.

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/errors.py agent/sla_agent/outlook_reader.py agent/tests/outlook_fakes.py agent/tests/test_outlook_reader.py
git commit -m "feat(agent): read the Inbox through classic Outlook, read-only, with a time limit and no text leaving

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Outlook in each sync, and the Outlook commands

Each sync reads Outlook after EduSoft and Blackboard, using this sync's timetable and Blackboard course names (kept in `state.json` for syncs where those fail). `sla-agent setup --outlook` chooses the account and registers the `sla-mail:` link type; `sla-agent open-mail` is what that link runs; `status` and `forget` know about Outlook. `conftest.py` gives every test a fake registry.

**Files:**
- Create: `agent/sla_agent/mail_link.py`
- Modify: `agent/pyproject.toml`, `requirements-dev.txt`, `agent/sla_agent/state.py`, `agent/sla_agent/sync.py`, `agent/sla_agent/cli.py`
- Test: `agent/tests/conftest.py`, `agent/tests/test_mail_link.py`, `agent/tests/test_sync.py`, `agent/tests/test_cli.py`

**Interfaces:**
- Consumes: `semester_start`, `read_outlook`, `accounts`, `open_outlook`, `with_time_limit`, `entry_id_from_link`, `open_email` (Task 4); `Context` (Task 3); the errors of Task 4.
- Produces:
  - `State` gains `outlook_account`, `term_code`, `courses` (`[[code, name, lecturer], …]`), `bb_courses` (`[[Blackboard name, code], …]`).
  - `run_sync(…, read_outlook=None)`: reads Outlook when it is given and `state.outlook_account` is set; `everything_paused(state)` is false while Outlook is on.
  - `mail_link.register(python_exe)`, `mail_link.unregister()`, `mail_link.command(python_exe)`, `mail_link.KEY` (`Software\Classes\sla-mail`).
  - `cli`: `setup --outlook`, `open-mail LINK`; `find_outlook_accounts()` and `show_message(text)` (replaced in tests).
  - `conftest.MemoryRegistry`; the `isolated_agent` fixture's value has `.registry`.

- [ ] **Step 1: Write the failing tests (and the fake registry)**

`agent/tests/conftest.py`: one change.

Replace

```python
        del self.entries[(service, username)]


@pytest.fixture(autouse=True)
def isolated_agent(tmp_path, monkeypatch):
    """Every agent test gets a fake keyring and its own agent folder:
    tests never touch the real Credential Manager or %LOCALAPPDATA%."""
    fake = MemoryKeyring()
    previous = keyring.get_keyring()
    keyring.set_keyring(fake)
    monkeypatch.setenv("SLA_AGENT_HOME", str(tmp_path / "agent-home"))
    yield fake
    keyring.set_keyring(previous)
```

with

```python
        del self.entries[(service, username)]


class MemoryRegistry:
    """Stands in for winreg (the Windows registry) during tests: keys are paths, values a dict per key."""

    HKEY_CURRENT_USER = "HKCU"
    REG_SZ = 1

    def __init__(self):
        self.keys = {}

    class Key:
        def __init__(self, path):
            self.path = path

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def CreateKey(self, root, path):
        parts = path.split("\\")
        for end in range(1, len(parts) + 1):
            self.keys.setdefault((root, "\\".join(parts[:end])), {})
        return self.Key((root, path))

    def SetValueEx(self, key, name, reserved, kind, value):
        self.keys[key.path][name] = value

    def DeleteKey(self, root, path):
        if (root, path) not in self.keys:
            raise FileNotFoundError(path)
        if any(other[0] == root and other[1].startswith(path + "\\") for other in self.keys):
            raise OSError("has subkeys")
        del self.keys[(root, path)]


@pytest.fixture(autouse=True)
def isolated_agent(tmp_path, monkeypatch):
    """Every agent test gets a fake keyring, a fake registry and its own agent folder:
    tests never touch the real Credential Manager, registry or %LOCALAPPDATA%."""
    from sla_agent import mail_link

    fake = MemoryKeyring()
    previous = keyring.get_keyring()
    keyring.set_keyring(fake)
    registry = MemoryRegistry()
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
    monkeypatch.setenv("SLA_AGENT_HOME", str(tmp_path / "agent-home"))
    fake.registry = registry
    yield fake
    keyring.set_keyring(previous)
```

`agent/tests/test_mail_link.py` (new):

```python
"""The sla-mail: link type, in a fake registry (conftest.py): tests never touch the real one."""

from sla_agent import mail_link

ROOT = r"Software\Classes\sla-mail"


def test_register_adds_the_link_type_for_this_user(isolated_agent):
    mail_link.register(r"C:\Python\pythonw.exe")

    keys = isolated_agent.registry.keys
    assert keys[("HKCU", ROOT)] == {"": "URL:School-Life-Assistant email", "URL Protocol": ""}
    assert keys[("HKCU", ROOT + r"\shell\open\command")] == {
        "": r'"C:\Python\pythonw.exe" -m sla_agent open-mail "%1"'}


def test_unregister_removes_it_and_is_fine_when_it_is_gone(isolated_agent):
    mail_link.register("pythonw.exe")

    mail_link.unregister()
    mail_link.unregister()

    assert not [path for _, path in isolated_agent.registry.keys if "sla-mail" in path]
```

`agent/tests/test_sync.py`: one change.

Replace

```python

    assert ["CS999IU", "05"] in bb_state.registered_courses
    assert len(bb_state.registered_courses) == 9  # the 8 registered, plus CS999IU; IT093IU only once
```

with

```python

    assert ["CS999IU", "05"] in bb_state.registered_courses
    assert len(bb_state.registered_courses) == 9  # the 8 registered, plus CS999IU; IT093IU only once


# ---- Outlook ------------------------------------------------------------------------


def outlook_result(since):
    from sla_contract.schema import Outlook

    return Outlook(since=since, connected=True, emails=[])


@pytest.fixture
def mail_state(state):
    state.outlook_account = "ititiu99001@student.hcmiu.edu.vn"
    return state


def sync_with_outlook(state, edusoft, read_outlook, parsers=PARSERS, **options):
    server = FakeServer()
    outcome = run_sync("scheduled", state=state, edusoft=edusoft, server=server, parsers=parsers,
                       password=PASSWORD, now=NOW, read_outlook=read_outlook, **options)
    return outcome, server


def test_outlook_syncs_after_the_others_with_this_syncs_courses(mail_state):
    from sla_contract.schema import Course

    seen = []

    def read(address, since, context):
        seen.append((address, since, context))
        return outlook_result(since)

    parsers = {**PARSERS, "timetable": lambda html: Timetable(term_code="20261", courses=[
        Course(course_code="IT093IU", course_name="Web Application Development", lecturer="P.Q.Hùng")])}
    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), read, parsers=parsers)

    assert list(only_finish(server).sections()) == ["timetable", "exams", "tuition", "outlook"]
    [(address, since, context)] = seen
    assert (address, since) == ("ititiu99001@student.hcmiu.edu.vn", date(2026, 8, 1))
    assert context.courses == (("IT093IU", "Web Application Development", "P.Q.Hùng"),)
    assert (mail_state.term_code, mail_state.courses) == (
        "20261", [["IT093IU", "Web Application Development", "P.Q.Hùng"]])
    assert outcome.status == "success"


def test_outlook_uses_the_blackboard_course_names_read_in_the_same_sync(mail_state):
    from sla_contract.schema import BbCourse

    mail_state.blackboard_username, mail_state.registered_courses = "bbuser", [["IT093IU", "02"]]
    seen = []
    course = BbCourse(bb_id="_1_1", course_code="IT093IU", name="Web Application Development_S1_2026-27_G02",
                      url="https://blackboard.hcmiu.edu.vn/x")

    sync_with_outlook(mail_state, FakeEduSoft(), lambda a, s, c: seen.append(c) or outlook_result(s),
                      blackboard=FakeBlackboard(), blackboard_password=BB_PASSWORD,
                      read_blackboard=lambda client, registered: Blackboard(courses=[course]))

    assert seen[0].bb_courses == (("Web Application Development_S1_2026-27_G02", "IT093IU"),)


def test_a_failed_edusoft_keeps_the_courses_from_the_last_sync(mail_state):
    mail_state.term_code, mail_state.courses = "20261", [["IT093IU", "Web Application Development", "P.Q.Hùng"]]
    seen = []

    sync_with_outlook(mail_state, FakeEduSoft(login_error=NetworkError("timed out")),
                      lambda a, s, c: seen.append(c) or outlook_result(s))

    assert seen[0].courses == (("IT093IU", "Web Application Development", "P.Q.Hùng"),)


def test_an_outlook_problem_fails_only_outlook_and_pauses_nothing(mail_state):
    from sla_agent.errors import OutlookBlocked

    def blocked(address, since, context):
        raise OutlookBlocked("Outlook didn't let the agent read your mail.")

    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), blocked)

    result = only_finish(server)
    assert (result.timetable.status, result.outlook.error_code) == ("ok", "outlook_blocked")
    assert (outcome.status, mail_state.paused) == ("partial", None)


def test_an_unexpected_outlook_crash_still_finishes_the_run(mail_state):
    def crashing(address, since, context):
        raise KeyError("x")

    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), crashing)

    assert only_finish(server).outlook.error_code == "unknown"


def test_outlook_still_syncs_while_edusoft_is_paused(mail_state):
    mail_state.paused = "bad_credentials"
    edusoft = FakeEduSoft()

    outcome, server = sync_with_outlook(mail_state, edusoft, lambda a, s, c: outlook_result(s))

    result = only_finish(server)
    assert edusoft.logins == []
    assert (result.timetable.error_code, result.outlook.status) == ("bad_credentials", "ok")


def test_outlook_is_not_read_until_it_is_set_up(state):
    seen = []

    outcome, server = sync_with_outlook(state, FakeEduSoft(), lambda a, s, c: seen.append(a))

    assert seen == []
    assert "outlook" not in only_finish(server).sections()
```

`agent/tests/test_cli.py`: one change.

Replace

```python
    out = capsys.readouterr().out
    assert out.index("Reading EduSoft") < out.index("Reading Blackboard") < out.index("Saved ")
    assert "few minutes" in out
```

with

```python
    out = capsys.readouterr().out
    assert out.index("Reading EduSoft") < out.index("Reading Blackboard") < out.index("Saved ")
    assert "few minutes" in out


# ---- Outlook ------------------------------------------------------------------------

ME = "ititiu99001@student.hcmiu.edu.vn"


def test_setup_outlook_needs_edusoft_setup_first(world, capsys):
    assert cli.main(["setup", "--outlook"]) == 1
    assert "isn't set up" in capsys.readouterr().out


def test_setup_outlook_reads_the_only_account_after_asking(world, isolated_agent, monkeypatch, capsys):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: [ME])
    world.answers = [""]

    assert cli.main(["setup", "--outlook"]) == 0

    assert load_state().outlook_account == ME
    command = isolated_agent.registry.keys[("HKCU", r"Software\Classes\sla-mail\shell\open\command")][""]
    assert command.endswith('-m sla_agent open-mail "%1"')
    assert "never the text" in capsys.readouterr().out


def test_setup_outlook_asks_which_account_when_there_are_several(world, monkeypatch):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: ["me@gmail.com", ME])
    world.answers = ["2"]

    assert cli.main(["setup", "--outlook"]) == 0

    assert load_state().outlook_account == ME


@pytest.mark.parametrize("found, answers", [([ME], ["n"]), (["me@gmail.com", ME], ["3"]), ([], [])],
                         ids=["said-no", "bad-number", "no-account"])
def test_setup_outlook_changes_nothing_without_a_clear_answer(world, monkeypatch, found, answers):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: found)
    world.answers = answers

    assert cli.main(["setup", "--outlook"]) == 1

    assert load_state().outlook_account is None


def test_setup_outlook_without_classic_outlook_explains_what_to_do(world, monkeypatch, capsys):
    from sla_agent.errors import OutlookNotSetUp

    configure()

    def missing():
        raise OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")

    monkeypatch.setattr(cli, "find_outlook_accounts", missing)

    assert cli.main(["setup", "--outlook"]) == 1
    assert "Open Outlook (classic)" in capsys.readouterr().out


def test_run_syncs_outlook_when_it_is_set_up(world, monkeypatch):
    from sla_contract.schema import Outlook

    configure()
    state = load_state()
    state.outlook_account = ME
    save_state(state)
    monkeypatch.setattr(cli, "read_outlook",
                        lambda address, since, context: Outlook(since=since, connected=True, emails=[]))

    assert cli.main(["run"]) == 0

    assert "outlook" in world.server.finishes[0][1].sections()


def test_status_shows_outlook(world, capsys):
    configure()
    state = load_state()
    state.outlook_account = ME
    save_state(state)

    cli.main(["status"])

    assert f"Outlook:     on ({ME})" in capsys.readouterr().out


def test_forget_removes_the_link_type(world, isolated_agent):
    from sla_agent import mail_link

    configure()
    mail_link.register("pythonw.exe")

    cli.main(["forget"])

    assert not [path for _, path in isolated_agent.registry.keys if "sla-mail" in path]


@pytest.fixture
def messages(monkeypatch):
    shown = []
    monkeypatch.setattr(cli, "show_message", shown.append)
    return shown


def test_open_mail_opens_the_email_in_outlook(monkeypatch, messages):
    opened = []
    monkeypatch.setattr(cli, "open_email", opened.append)

    assert cli.main(["open-mail", "sla-mail:00AB12/"]) == 0

    assert (opened, messages) == (["00AB12"], [])


def test_open_mail_refuses_anything_else(monkeypatch, messages):
    monkeypatch.setattr(cli, "open_email", lambda entry_id: pytest.fail("must not open anything"))

    assert cli.main(["open-mail", "javascript:alert(1)"]) == 1

    assert messages == ["This isn't a School-Life-Assistant email link."]


def test_open_mail_for_an_email_that_is_gone(monkeypatch, messages):
    from sla_agent.errors import EmailNotFound

    def gone(entry_id):
        raise EmailNotFound("This email is no longer in your Outlook Inbox.")

    monkeypatch.setattr(cli, "open_email", gone)

    assert cli.main(["open-mail", "sla-mail:00AB12"]) == 1

    assert messages == ["This email is no longer in your Outlook Inbox."]
```


- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent -q`
Expected: FAIL: every test errors at setup with `ImportError: cannot import name 'mail_link' from 'sla_agent'` (the conftest needs it).

- [ ] **Step 3: pywin32 on Windows, the state, the link type, the sync and the commands**

`agent/pyproject.toml`: one change.

Replace

```toml
    "requests>=2.32,<3",
    "beautifulsoup4>=4.12,<5",
    "keyring>=25,<26",
]

[project.scripts]
```

with

```toml
    "requests>=2.32,<3",
    "beautifulsoup4>=4.12,<5",
    "keyring>=25,<26",
    "pywin32>=312; sys_platform == 'win32'",  # reads classic Outlook (Windows only)
]

[project.scripts]
```

`requirements-dev.txt`: one change.

Replace

```text
requests==2.34.2
beautifulsoup4==4.15.0
keyring==25.7.0

# Tests
pytest==9.1.1
```

with

```text
requests==2.34.2
beautifulsoup4==4.15.0
keyring==25.7.0
pywin32==312; sys_platform == "win32"

# Tests
pytest==9.1.1
```

`agent/sla_agent/state.py`: one change.

Replace

```python
    blackboard_username: str | None = None
    blackboard_paused: str | None = None  # error code that paused Blackboard sync
    registered_courses: list | None = None  # [[course code, group], ...] from EduSoft's registration page


def load_state():
```

with

```python
    blackboard_username: str | None = None
    blackboard_paused: str | None = None  # error code that paused Blackboard sync
    registered_courses: list | None = None  # [[course code, group], ...] from EduSoft's registration page
    outlook_account: str | None = None  # the classic Outlook account whose Inbox is read
    term_code: str | None = None  # EduSoft's semester, from the last timetable read, e.g. "20261"
    courses: list | None = None  # [[course code, course name, lecturer], ...] from the last timetable read
    bb_courses: list | None = None  # [[Blackboard course name, course code], ...] from the last Blackboard read


def load_state():
```

`agent/sla_agent/mail_link.py` (new):

```python
r"""The sla-mail: link type, so Mailbox's "Open in Outlook" button opens an email on this laptop.

It is registered for this Windows user only (HKEY_CURRENT_USER\Software\Classes\sla-mail), so no admin
rights are needed. A link runs `sla-agent open-mail "sla-mail:<Outlook ID>"`, which only shows that email
(see outlook_reader.open_email)."""

KEY = r"Software\Classes\sla-mail"
SUBKEYS = (r"\shell\open\command", r"\shell\open", r"\shell", "")  # deepest first, for deleting


def _winreg():
    import winreg

    return winreg


def command(python_exe):
    return f'"{python_exe}" -m sla_agent open-mail "%1"'


def register(python_exe):
    registry = _winreg()
    with registry.CreateKey(registry.HKEY_CURRENT_USER, KEY) as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, "URL:School-Life-Assistant email")
        registry.SetValueEx(key, "URL Protocol", 0, registry.REG_SZ, "")
    with registry.CreateKey(registry.HKEY_CURRENT_USER, KEY + r"\shell\open\command") as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, command(python_exe))


def unregister():
    registry = _winreg()
    for subkey in SUBKEYS:
        try:
            registry.DeleteKey(registry.HKEY_CURRENT_USER, KEY + subkey)
        except FileNotFoundError:
            pass
```

`agent/sla_agent/sync.py`: 5 changes.

Change 1: replace

```python
"""One sync: EduSoft (timetable, exams, tuition) then Blackboard, each on its own.

Stop-don't-retry rules: a rejected password or an extra-verification request pauses
that system only (no second login attempt). An expired session gets one re-login and
one retry. One system failing never stops the other from uploading.
"""

import logging
from dataclasses import dataclass

from sla_contract.schema import EDUSOFT_SECTIONS, FinishRun

from sla_agent.blackboard_reader import read_blackboard as default_read_blackboard
from sla_agent.errors import AgentError, BadCredentials, ExtraVerification, SessionExpired
from sla_agent.log import protect
from sla_agent.parsers.registration import RegisteredCourse, parse_registered_courses

log = logging.getLogger(__name__)
```

with

```python
"""One sync: EduSoft (timetable, exams, tuition), then Blackboard, then Outlook, each on its own.

Stop-don't-retry rules: a rejected password or an extra-verification request pauses
that system only (no second login attempt). An expired session gets one re-login and
one retry. One system failing never stops the others from uploading. Outlook has no
password to lock out, so an Outlook problem never pauses anything: every sync tries again.
"""

import logging
from dataclasses import dataclass
from datetime import timedelta

from sla_contract.schema import EDUSOFT_SECTIONS, FinishRun

from sla_agent.blackboard_reader import read_blackboard as default_read_blackboard
from sla_agent.errors import AgentError, BadCredentials, ExtraVerification, SessionExpired
from sla_agent.log import protect
from sla_agent.mail_rules import Context
from sla_agent.outlook_reader import semester_start
from sla_agent.parsers.registration import RegisteredCourse, parse_registered_courses

log = logging.getLogger(__name__)
```

Change 2: replace

```python


def everything_paused(state):
    return bool(state.paused) and not blackboard_ready(state)


def _read_section(name, edusoft, parsers, student_id, password):
```

with

```python


def everything_paused(state):
    return bool(state.paused) and not blackboard_ready(state) and not state.outlook_account


def _read_section(name, edusoft, parsers, student_id, password):
```

Change 3: replace

```python
        blackboard.logout()


def _paused_sections(state):
    """A paused system is reported as failed in every run, so the web page keeps showing the pause."""
    sections = {}
```

with

```python
        blackboard.logout()


def _remember_context(state, sections):
    """What sorting mail needs, from this sync's timetable and Blackboard (kept for syncs where they fail)."""
    timetable = sections.get("timetable") or {}
    if timetable.get("status") == "ok":
        data = timetable["data"]
        state.term_code = data.term_code
        state.courses = [[c.course_code, c.course_name, c.lecturer] for c in data.courses]
    blackboard = sections.get("blackboard") or {}
    if blackboard.get("status") == "ok":
        state.bb_courses = [[c.name, c.course_code] for c in blackboard["data"].courses if c.course_code]


def _collect_outlook(state, read, now):
    today = (now + timedelta(hours=7)).date()  # in Vietnam
    context = Context(courses=tuple(tuple(c) for c in state.courses or []),
                      bb_courses=tuple(tuple(c) for c in state.bb_courses or []))
    try:
        return {"status": "ok", "data": read(state.outlook_account, semester_start(state.term_code, today), context)}
    except AgentError as error:
        log.warning("Outlook sync failed: %s", error)
        return _failed(error)
    except Exception as error:
        return _unexpected("Outlook", error)


def _paused_sections(state):
    """A paused system is reported as failed in every run, so the web page keeps showing the pause."""
    sections = {}
```

Change 4: replace

```python


def run_sync(trigger, *, state, edusoft, server, parsers, password, now,
             blackboard=None, blackboard_password=None, read_blackboard=default_read_blackboard):
    """Run one sync and update `state` (the caller saves it). Server errors are raised."""
    protect(password)
    protect(blackboard_password)
    use_edusoft = not state.paused
    use_blackboard = blackboard is not None and bool(blackboard_password) and blackboard_ready(state)
    if not use_edusoft and not use_blackboard:
        return Outcome("paused", _paused_message(state))

    run_id = server.start(trigger)
```

with

```python


def run_sync(trigger, *, state, edusoft, server, parsers, password, now,
             blackboard=None, blackboard_password=None, read_blackboard=default_read_blackboard, read_outlook=None):
    """Run one sync and update `state` (the caller saves it). Server errors are raised."""
    protect(password)
    protect(blackboard_password)
    use_edusoft = not state.paused
    use_blackboard = blackboard is not None and bool(blackboard_password) and blackboard_ready(state)
    use_outlook = read_outlook is not None and bool(state.outlook_account)
    if not use_edusoft and not use_blackboard and not use_outlook:
        return Outcome("paused", _paused_message(state))

    run_id = server.start(trigger)
```

Change 5: replace

```python
        sections.update(_collect_edusoft(state, edusoft, parsers, password))
    if use_blackboard:
        sections["blackboard"] = _collect_blackboard(state, blackboard, blackboard_password, read_blackboard)
    result = FinishRun.model_validate(sections)
    status = server.finish(run_id, result)
```

with

```python
        sections.update(_collect_edusoft(state, edusoft, parsers, password))
    if use_blackboard:
        sections["blackboard"] = _collect_blackboard(state, blackboard, blackboard_password, read_blackboard)
    _remember_context(state, sections)
    if use_outlook:
        sections["outlook"] = _collect_outlook(state, read_outlook, now)
    result = FinishRun.model_validate(sections)
    status = server.finish(run_id, result)
```

`agent/sla_agent/cli.py`: 11 changes.

Change 1: replace

```python
"""sla-agent: the laptop side of School-Life-Assistant.

    sla-agent setup                  enter your details once; schedules automatic sync
    sla-agent run                    what the scheduled task calls every 15 minutes
    sla-agent sync-now               sync right away
    sla-agent status                 show the last result and whether sync is paused
    sla-agent fetch --save-html DIR  save your EduSoft pages on this laptop (for building the readers)
    sla-agent import FOLDER          read pages saved by `fetch` (or your browser) and upload them
    sla-agent forget                 delete the saved secrets and the scheduled task
"""

import argparse
```

with

```python
"""sla-agent: the laptop side of School-Life-Assistant.

    sla-agent setup                  enter your details once; schedules automatic sync
    sla-agent setup --outlook        read your Inbox through classic Outlook at each sync
    sla-agent run                    what the scheduled task calls every 15 minutes
    sla-agent sync-now               sync right away
    sla-agent status                 show the last result and whether sync is paused
    sla-agent fetch --save-html DIR  save your EduSoft pages on this laptop (for building the readers)
    sla-agent import FOLDER          read pages saved by `fetch` (or your browser) and upload them
    sla-agent forget                 delete the saved secrets and the scheduled task
    sla-agent open-mail LINK         what Mailbox's "Open in Outlook" button runs: shows one email
"""

import argparse
```

Change 2: replace

```python

from sla_contract.schema import EDUSOFT_SECTIONS, FinishRun

from sla_agent import credentials
from sla_agent.blackboard_client import BlackboardClient
from sla_agent.blackboard_reader import read_blackboard
from sla_agent.edusoft_client import EduSoftClient
```

with

```python

from sla_contract.schema import EDUSOFT_SECTIONS, FinishRun

from sla_agent import credentials, mail_link
from sla_agent.blackboard_client import BlackboardClient
from sla_agent.blackboard_reader import read_blackboard
from sla_agent.edusoft_client import EduSoftClient
```

Change 3: replace

```python
    AgentError,
    BadCredentials,
    DeviceKeyRejected,
    ExtraVerification,
    ParseError,
    RunInProgress,
    ServerError,
)
from sla_agent.log import protect, setup_logging
from sla_agent.parsers import PARSERS
from sla_agent.parsers.registration import RegisteredCourse, parse_registered_courses
from sla_agent.scheduler import SchedulerError, current_user, install_task, remove_task, windowless_python
```

with

```python
    AgentError,
    BadCredentials,
    DeviceKeyRejected,
    EmailNotFound,
    ExtraVerification,
    ParseError,
    RunInProgress,
    ServerError,
)
from sla_agent.log import protect, setup_logging
from sla_agent.outlook_reader import (
    accounts,
    entry_id_from_link,
    open_email,
    open_outlook,
    read_outlook,
    with_time_limit,
)
from sla_agent.parsers import PARSERS
from sla_agent.parsers.registration import RegisteredCourse, parse_registered_courses
from sla_agent.scheduler import SchedulerError, current_user, install_task, remove_task, windowless_python
```

Change 4: replace

```python
    return BlackboardClient()


def say(message):
    print(message)


def _now():
    return datetime.now(timezone.utc)
```

with

```python
    return BlackboardClient()


def find_outlook_accounts():
    return with_time_limit(lambda: accounts(open_outlook()))


def say(message):
    print(message)


def show_message(message):
    """A Windows message box: `open-mail` runs from the browser, without a console."""
    import ctypes

    ctypes.windll.user32.MessageBoxW(None, message, "School-Life-Assistant", 0x40)  # MB_ICONINFORMATION


def _now():
    return datetime.now(timezone.utc)
```

Change 5: replace

```python
    return 0


def cmd_setup(args):
    state = load_state()
    if args.blackboard:
        if not state.server_url or not state.student_id:
            say(NOT_SET_UP)
            return 1
        return _setup_blackboard(state)
    server_url = ask(f"Web app address [{state.server_url or 'https://...'}]: ").strip() or state.server_url or ""
    try:
        check_server_url(server_url)
```

with

```python
    return 0


OUTLOOK_HOW_TO = ("Open Outlook (classic), sign in with your IU account, wait until it says "
                  "'All folders are up to date', then run `sla-agent setup --outlook` again.")


def _setup_outlook(state):
    """Choose the Outlook account whose Inbox each sync reads, and add the sla-mail: link type."""
    say("Looking for classic Outlook on this laptop...")
    try:
        found = find_outlook_accounts()
    except AgentError as error:
        say(f"{error} {OUTLOOK_HOW_TO}")
        return 1
    if not found:
        say(f"Classic Outlook has no account yet. {OUTLOOK_HOW_TO}")
        return 1
    if len(found) == 1:
        if ask(f"Read the Inbox of {found[0]}? [Y/n]: ").strip().lower() not in ("", "y", "yes"):
            say("Nothing was changed.")
            return 1
        address = found[0]
    else:
        for number, candidate in enumerate(found, 1):
            say(f"  {number}. {candidate}")
        answer = ask("Which account's Inbox should be read? Number: ").strip()
        if not answer.isdigit() or not 1 <= int(answer) <= len(found):
            say("Nothing was changed.")
            return 1
        address = found[int(answer) - 1]
    state.outlook_account = address
    save_state(state)
    try:
        mail_link.register(windowless_python())
    except OSError as error:
        say(f"Couldn't add the sla-mail: link type ({error}). Mailbox's \"Open in Outlook\" won't open emails "
            "on this laptop; use \"Outlook on the web\" instead.")
    say(f"Outlook is on: each sync reads the Inbox of {address}, sorts it on this laptop and uploads only the "
        "results, never the text.")
    return 0


def cmd_setup(args):
    state = load_state()
    if args.blackboard or args.outlook:
        if not state.server_url or not state.student_id:
            say(NOT_SET_UP)
            return 1
        return _setup_blackboard(state) if args.blackboard else _setup_outlook(state)
    server_url = ask(f"Web app address [{state.server_url or 'https://...'}]: ").strip() or state.server_url or ""
    try:
        check_server_url(server_url)
```

Change 6: replace

```python
    try:
        outcome = run_sync(trigger, state=state, edusoft=make_edusoft(), server=server, parsers=PARSERS,
                           password=password, now=_now(), blackboard=blackboard,
                           blackboard_password=blackboard_password, read_blackboard=read_blackboard)
    except RunInProgress:
        say("A sync is already running.")
        return 0
```

with

```python
    try:
        outcome = run_sync(trigger, state=state, edusoft=make_edusoft(), server=server, parsers=PARSERS,
                           password=password, now=_now(), blackboard=blackboard,
                           blackboard_password=blackboard_password, read_blackboard=read_blackboard,
                           read_outlook=read_outlook)
    except RunInProgress:
        say("A sync is already running.")
        return 0
```

Change 7: replace

```python
        say(f"Blackboard:  PAUSED ({state.blackboard_paused}). Run `sla-agent setup --blackboard`.")
    else:
        say("Blackboard:  on")
    if state.last_result:
        say(f"Last sync:   {state.last_result['status']} at {state.last_result['at']}: {state.last_result['message']}")
    else:
```

with

```python
        say(f"Blackboard:  PAUSED ({state.blackboard_paused}). Run `sla-agent setup --blackboard`.")
    else:
        say("Blackboard:  on")
    if state.outlook_account:
        say(f"Outlook:     on ({state.outlook_account})")
    else:
        say("Outlook:     not set up (run `sla-agent setup --outlook`)")
    if state.last_result:
        say(f"Last sync:   {state.last_result['status']} at {state.last_result['at']}: {state.last_result['message']}")
    else:
```

Change 8: replace

```python
    state = load_state()
    credentials.forget(state.student_id, state.server_url, state.blackboard_username)
    remove_task()
    (agent_home() / "state.json").unlink(missing_ok=True)
    say("Removed your saved EduSoft password, the device key and the scheduled task from this laptop.")
    return 0
```

with

```python
    state = load_state()
    credentials.forget(state.student_id, state.server_url, state.blackboard_username)
    remove_task()
    try:
        mail_link.unregister()
    except (ImportError, OSError):  # not Windows, or already gone
        pass
    (agent_home() / "state.json").unlink(missing_ok=True)
    say("Removed your saved EduSoft password, the device key, the scheduled task and the sla-mail: link type "
        "from this laptop.")
    return 0


def cmd_open_mail(args):
    entry_id = entry_id_from_link(args.link)
    if entry_id is None:
        show_message("This isn't a School-Life-Assistant email link.")
        return 1
    try:
        open_email(entry_id)
    except EmailNotFound as error:
        show_message(str(error))
        return 1
    except AgentError as error:
        show_message(f"{error} Open Outlook (classic) and sign in, then try again.")
        return 1
    return 0
```

Change 9: replace

```python
    "fetch": cmd_fetch,
    "import": cmd_import,
    "forget": cmd_forget,
}
```

with

```python
    "fetch": cmd_fetch,
    "import": cmd_import,
    "forget": cmd_forget,
    "open-mail": cmd_open_mail,
}
```

Change 10: replace

```python
    setup = commands.add_parser("setup", help="enter your details once; schedules automatic sync")
    setup.add_argument("--no-schedule", action="store_true", help="don't create the scheduled task")
    setup.add_argument("--blackboard", action="store_true", help="set or change only the Blackboard login")
    commands.add_parser("run", help="scheduled check-in: sync if the web app says it's due")
    commands.add_parser("sync-now", help="sync right away")
    commands.add_parser("status", help="show the last result and whether sync is paused")
```

with

```python
    setup = commands.add_parser("setup", help="enter your details once; schedules automatic sync")
    setup.add_argument("--no-schedule", action="store_true", help="don't create the scheduled task")
    setup.add_argument("--blackboard", action="store_true", help="set or change only the Blackboard login")
    setup.add_argument("--outlook", action="store_true", help="read your Inbox through classic Outlook")
    commands.add_parser("run", help="scheduled check-in: sync if the web app says it's due")
    commands.add_parser("sync-now", help="sync right away")
    commands.add_parser("status", help="show the last result and whether sync is paused")
```

Change 11: replace

```python
    importer.add_argument("folder")
    importer.add_argument("--term", help="semester code for exam pages, e.g. 20261")
    commands.add_parser("forget", help="delete saved secrets and the scheduled task")

    args = parser.parse_args(argv)
    # Output piped or redirected on Windows uses cp1252; never crash on Vietnamese text.
```

with

```python
    importer.add_argument("folder")
    importer.add_argument("--term", help="semester code for exam pages, e.g. 20261")
    commands.add_parser("forget", help="delete saved secrets and the scheduled task")
    open_mail = commands.add_parser("open-mail", help="show one email in Outlook (run by Mailbox's links)")
    open_mail.add_argument("link")

    args = parser.parse_args(argv)
    # Output piped or redirected on Windows uses cp1252; never crash on Vietnamese text.
```


Then install pywin32 into the project's environment (Windows only; nothing is installed on Linux):

Run: `.venv/Scripts/python.exe -m pip install -r requirements-dev.txt`
Expected: `Successfully installed pywin32-312` (or "Requirement already satisfied").

- [ ] **Step 4: Run them to see them pass**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (365 tests: agent and data format).

- [ ] **Step 5: Commit**

```bash
git add agent/pyproject.toml requirements-dev.txt agent/sla_agent/state.py agent/sla_agent/mail_link.py agent/sla_agent/sync.py agent/sla_agent/cli.py agent/tests/conftest.py agent/tests/test_mail_link.py agent/tests/test_sync.py agent/tests/test_cli.py
git commit -m "feat(agent): Outlook in each sync; setup --outlook, open-mail and the sla-mail: link type

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The website saves mail

Four tables (§6.1), their entities and repositories, saving an `outlook` part (§6.2), and an Outlook line in the sync status with Outlook's own problems (§6.5). `SyncStatus.mailProblem` finds the Outlook problem for the top of Mailbox (Task 7).

**Files:**
- Create: `web/src/main/resources/db/migration/V20260928_1_1__school_mail.sql`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/CommaLists.java`, `SchoolMail.java`, `SchoolMailChange.java`, `SchoolMailChoice.java`, `SchoolMailStatus.java`, `SchoolMailRepository.java`, `SchoolMailChangeRepository.java`, `SchoolMailChoiceRepository.java`, `SchoolMailStatusRepository.java` (all in `school/model/`)
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/pages/SyncStatusTest.java`

**Interfaces:**
- Consumes: `SyncContract.Outlook`, `MailItem`, `MailClassChange` (Task 1); `Payloads.outlookPayload()` (Task 1).
- Produces:
  - `SchoolMail(userId, mailKey, entryId, threadId, receivedAt, senderName, senderAddress, subject, categories, fromLecturer, dates, losesPoints, sorted, blackboardTitle)` with getters (`isFromLecturer`, `isLosesPoints`, `isSorted`) and `getChanges()`.
  - `SchoolMailChange(mail, courseCode, kind, day, start, end, room)` with getters.
  - `SchoolMailChoice(userId, mailKey, updatedAt)`: `isDone()`, `getCategories()` (null = not moved), `getFromLecturer()` (Boolean), `isMoved()`, `setDone(done, now)`, `move(categories, fromLecturer, now)`, `backToAutomatic(now)`.
  - `SchoolMailStatus(userId, since, connected, syncedAt)` with getters.
  - Repositories: `SchoolMailRepository.findByUserIdOrderByReceivedAtDescIdDesc(userId)`, `deleteAllOfUser`; `SchoolMailChangeRepository.findOfUser(userId)` (with the email), `deleteAllOfUser`; `SchoolMailChoiceRepository.findByUserId`, `findByUserIdAndMailKey`, `deleteOthers(userId, keep)`, `deleteAllOfUser`; `SchoolMailStatusRepository` (id = user id).
  - `SyncStatus.OUTLOOK_PROBLEMS`, `SyncStatus.CHECK_OUTLOOK`, `record MailProblem(String headline, String detail, LocalDateTime at)`, `static MailProblem mailProblem(List<RunInfo> runs)` (runs newest first; null when the newest run that read Outlook was fine, or none did).

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`: one change.

Replace

```java
        assertThat(again.getAnnouncements().get(0).getText()).isEqualTo(text);
        assertThat(again.getAssignments().get(0).getScore()).isEqualTo(6.666666667);
    }
}
```

with

```java
        assertThat(again.getAnnouncements().get(0).getText()).isEqualTo(text);
        assertThat(again.getAssignments().get(0).getScore()).isEqualTo(6.666666667);
    }

    @Test
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

        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        SchoolMail emptyAgain = db.find(SchoolMail.class, empty.getId());
        assertThat(List.of(emptyAgain.getCategories(), emptyAgain.getDates())).containsExactly(List.of(), List.of());
    }

    @Test
    void aChoiceWithoutMoveToKeepsNoCategories() {
        SchoolMailChoice choice = new SchoolMailChoice(userId, "a".repeat(64), SEPT_28);
        choice.setDone(true, SEPT_28);
        db.persist(choice);

        SchoolMailChoice again = reloaded(choice, choice.getId());

        assertThat(List.of(again.isDone(), again.isMoved())).containsExactly(true, false);
        assertThat(again.getCategories()).isNull();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`: one change.

Replace

```java
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "flyway_schema_history");
    }
```

with

```java
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "school_mail", "school_mail_changes", "school_mail_choices", "school_mail_status",
                "flyway_schema_history");
    }
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`: 4 changes.

Change 1: replace

```java
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.list;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
```

with

```java
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.list;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;
import static vn.edu.hcmiu.sla.school.sync.Payloads.outlookPayload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
```

Change 3: replace

```java
    @Autowired
    SchoolBbMaterialRepository bbMaterials;

    Integer userId;

    Integer makeUser(String email) {
```

with

```java
    @Autowired
    SchoolBbMaterialRepository bbMaterials;

    @Autowired
    SchoolMailRepository mails;

    @Autowired
    SchoolMailChangeRepository mailChanges;

    @Autowired
    SchoolMailChoiceRepository mailChoices;

    @Autowired
    SchoolMailStatusRepository mailStatus;

    Integer userId;

    Integer makeUser(String email) {
```

Change 4: replace

```java

        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId())).isEmpty();
    }
}
```

with

```java

        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId())).isEmpty();
    }

    // ---- Outlook ------------------------------------------------------------------

    static final String KEY_1 = "e59f2b527cc57265e4719faaa2bed978ce0cb5468cc8fb109fb90b137e85b860";
    static final String KEY_2 = "51e77916873ea9f29962ffb91e14c24907e439a41fc6bfdfad52010416af1631";

    String syncOutlook(Integer userId, Object part) {
        Map<String, Object> payload = fullPayload();
        payload.put("outlook", part);
        return sync(userId, payload);
    }

    void choose(Integer userId, String key) {
        SchoolMailChoice choice = new SchoolMailChoice(userId, key, LocalDateTime.of(2026, 9, 28, 1, 0));
        choice.setDone(true, LocalDateTime.of(2026, 9, 28, 1, 0));
        mailChoices.save(choice);
    }

    @Test
    void anOutlookPartSavesEachEmailsResultsInUtc() {
        assertThat(syncOutlook(userId, ok(outlookPayload()))).isEqualTo("success");

        List<SchoolMail> saved = mails.findByUserIdOrderByReceivedAtDescIdDesc(userId);
        assertThat(saved).extracting(SchoolMail::getSubject).containsExactly("Make-up class",
                "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                "Web Application Development_S1_2026-27_G02: Online class on 22/9");
        SchoolMail workshop = saved.get(1);
        assertThat(workshop.getCategories()).containsExactly("event", "training_points");
        assertThat(workshop.getDates()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(List.of(workshop.isLosesPoints(), workshop.isFromLecturer(), workshop.isSorted()))
                .containsExactly(true, false, true);
        assertThat(workshop.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 3, 30));
        assertThat(saved.get(0).isSorted()).isFalse();
        assertThat(saved.get(2).getBlackboardTitle()).isEqualTo("Online class on 22/9");
        SchoolMailChange makeup = mailChanges.findOfUser(userId).stream()
                .filter(c -> c.getKind().equals("makeup")).findFirst().orElseThrow();
        assertThat(List.of(makeup.getCourseCode(), makeup.getDay(), makeup.getStart(), makeup.getEnd(), makeup.getRoom()))
                .containsExactly("MA026IU", LocalDate.of(2026, 10, 3), LocalTime.of(13, 15), LocalTime.of(15, 45),
                        "A2.401");
        SchoolMailStatus status = mailStatus.findById(userId).orElseThrow();
        assertThat(List.of(status.getSince(), status.isConnected())).containsExactly(LocalDate.of(2026, 8, 1), true);
        assertThat(lastRun().getSections().get("outlook")).isEqualTo(Map.of("status", "ok"));
    }

    @Test
    void mailNeverReachesTheWhatChangedFeed() {
        syncOutlook(userId, ok(outlookPayload()));

        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId()))
                .noneMatch(change -> change.getSection().equals("outlook"));
    }

    @Test
    void aNewSyncReplacesTheMailAndKeepsChoicesForEmailsStillThere() {
        syncOutlook(userId, ok(outlookPayload()));
        choose(userId, KEY_1);
        choose(userId, KEY_2);
        Map<String, Object> data = outlookPayload();
        list(data, "emails").subList(1, 3).clear();

        syncOutlook(userId, ok(data));

        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId)).extracting(SchoolMail::getMailKey)
                .containsExactly(KEY_1);
        assertThat(mailChanges.findOfUser(userId)).hasSize(1);
        assertThat(mailChoices.findByUserId(userId)).extracting(SchoolMailChoice::getMailKey).containsExactly(KEY_1);
    }

    @Test
    void anEmptyInboxRemovesEveryChoice() {
        syncOutlook(userId, ok(outlookPayload()));
        choose(userId, KEY_1);
        Map<String, Object> data = outlookPayload();
        data.put("emails", List.of());

        syncOutlook(userId, ok(data));

        assertThat(mails.count()).isZero();
        assertThat(mailChoices.findByUserId(userId)).isEmpty();
    }

    @Test
    void aFailedOutlookPartKeepsTheMailItHad() {
        syncOutlook(userId, ok(outlookPayload()));

        assertThat(syncOutlook(userId, failed("outlook_blocked", "Outlook didn't let the agent read your mail.")))
                .isEqualTo("partial");

        assertThat(mails.count()).isEqualTo(3);
        assertThat(lastRun().getSections().get("outlook")).containsEntry("error_code", "outlook_blocked");
    }

    @Test
    void anEmailSentTwiceWithTheSameKeyIsKeptOnce() {
        Map<String, Object> data = outlookPayload();
        list(data, "emails").add(list(data, "emails").get(0));

        assertThat(syncOutlook(userId, ok(data))).isEqualTo("success");

        assertThat(mails.count()).isEqualTo(3);
    }

    @Test
    void anotherUsersMailIsLeftAlone() {
        Integer other = makeUser("binh@example.com");
        syncOutlook(other, ok(outlookPayload()));
        choose(other, KEY_1);

        syncOutlook(userId, ok(outlookPayload()));

        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(other)).hasSize(3);
        assertThat(mailChoices.findByUserId(other)).hasSize(1);
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/SyncStatusTest.java`: 2 changes.

Change 1: replace

```java

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.Status;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.SystemLine;
```

with

```java

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.pages.SyncStatus.MailProblem;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.Status;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.SystemLine;
```

Change 2: replace

```java
        assertThat(result.headline()).contains("Blackboard");
        assertThat(result.detail()).contains("sla-agent setup --blackboard");
    }
}
```

with

```java
        assertThat(result.headline()).contains("Blackboard");
        assertThat(result.detail()).contains("sla-agent setup --blackboard");
    }

    // ---- Outlook ------------------------------------------------------------------

    @Test
    void outlookHasItsOwnLineAndSaysWhatToDo() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("outlook", bad("outlook_blocked"));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"), tuple("Outlook", "failed"));
        assertThat(lines.get(1).text()).isEqualTo(
                "Outlook didn't let the agent read your mail; check Outlook, then press Sync now.");
    }

    @Test
    void outlookFailureHeadlineNamesOutlook() {
        Status result = status(run("failed", "07:00", "07:05", null,
                parts(Map.entry("outlook", bad("outlook_not_set_up")))));

        assertThat(result.headline()).isEqualTo("Sync failed: classic Outlook isn't set up on your laptop");
        assertThat(result.detail()).contains("Open Outlook (classic)");
    }

    @Test
    void aPartlyFailedOutlookIsNamedAmongThePartsThatFailed() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("outlook", bad("outlook_blocked"));

        assertThat(status(run("partial", "07:00", "07:05", null, sections)).detail())
                .isEqualTo("Couldn't read: Outlook. The previous data for it is still shown.");
    }

    @Test
    void mailProblemComesFromTheNewestRunThatReadOutlook() {
        RunInfo edusoftOnly = run("success", "07:00", "07:05", null, edu());
        RunInfo blocked = run("partial", "05:00", "05:05", null, parts(Map.entry("timetable", OK),
                Map.entry("outlook", bad("outlook_blocked"))));

        MailProblem problem = SyncStatus.mailProblem(List.of(edusoftOnly, blocked));

        assertThat(problem).isEqualTo(new MailProblem("Sync failed: Outlook didn't let the agent read your mail",
                SyncStatus.CHECK_OUTLOOK, at("05:05")));
    }

    @Test
    void noMailProblemWhenOutlookWasReadOrNeverTried() {
        RunInfo read = run("success", "07:00", "07:05", null, parts(Map.entry("outlook", OK)));

        assertThat(SyncStatus.mailProblem(List.of(read))).isNull();
        assertThat(SyncStatus.mailProblem(List.of(run("success", "07:00", "07:05", null, edu())))).isNull();
        assertThat(SyncStatus.mailProblem(List.of())).isNull();
    }

    @Test
    void anUnknownOutlookProblemStillSaysSomething() {
        RunInfo crashed = run("partial", "07:00", "07:05", null, parts(Map.entry("outlook", bad("unknown"))));

        assertThat(SyncStatus.mailProblem(List.of(crashed)).headline()).isEqualTo("Sync failed");
    }
}
```


- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='SchoolTablesTest,MigrationTest,IngestTest,SyncStatusTest')`
Expected: compilation fails: `cannot find symbol` for `SchoolMail`, `SchoolMailChoice`, `MailProblem` and the new repositories.

- [ ] **Step 3: The tables, entities and repositories; saving; the status**

`web/src/main/resources/db/migration/V20260928_1_1__school_mail.sql` (new):

```sql
-- Mailbox: what the laptop found in each email, never its text
-- (docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.1).
CREATE TABLE school_mail (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    entry_id VARCHAR(512) NOT NULL,
    thread_id VARCHAR(64) NULL,
    received_at DATETIME NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    sender_address VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    categories VARCHAR(100) NOT NULL,
    from_lecturer BOOLEAN NOT NULL,
    dates VARCHAR(400) NOT NULL,
    loses_points BOOLEAN NOT NULL,
    is_sorted BOOLEAN NOT NULL,
    blackboard_title VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key),
    CONSTRAINT fk_school_mail_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_mail_changes (
    id INT NOT NULL AUTO_INCREMENT,
    mail_id INT NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    change_day DATE NOT NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    room VARCHAR(50) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_mail_changes_mail FOREIGN KEY (mail_id) REFERENCES school_mail (id) ON DELETE CASCADE
);

-- The student's Done and Move to... choices. They outlive a sync, which replaces school_mail.
CREATE TABLE school_mail_choices (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    done BOOLEAN NOT NULL,
    categories VARCHAR(100) NULL,
    from_lecturer BOOLEAN NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key),
    CONSTRAINT fk_school_mail_choices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_mail_status (
    user_id INT NOT NULL,
    since DATE NOT NULL,
    connected BOOLEAN NOT NULL,
    synced_at DATETIME NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_mail_status_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/CommaLists.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Short lists kept in one column as comma-separated text: "event,training_points", "2026-09-29,2026-10-02". */
public final class CommaLists {

    private CommaLists() {
    }

    /** Words without commas (the mail categories). An empty list is "", a missing one NULL. */
    @Converter
    public static class Words implements AttributeConverter<List<String>, String> {

        @Override
        public String convertToDatabaseColumn(List<String> words) {
            return words == null ? null : String.join(",", words);
        }

        @Override
        public List<String> convertToEntityAttribute(String column) {
            if (column == null) {
                return null;
            }
            return column.isEmpty() ? List.of() : List.of(column.split(","));
        }
    }

    /** ISO dates. */
    @Converter
    public static class Dates implements AttributeConverter<List<LocalDate>, String> {

        @Override
        public String convertToDatabaseColumn(List<LocalDate> dates) {
            return dates == null ? null : String.join(",", dates.stream().map(LocalDate::toString).toList());
        }

        @Override
        public List<LocalDate> convertToEntityAttribute(String column) {
            if (column == null || column.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(column.split(",")).map(LocalDate::parse).toList();
        }
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMail.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * One email in the student's Inbox, as the laptop sorted it: never its text. Each sync replaces all of a
 * user's rows; the student's choices are kept apart, in {@link SchoolMailChoice}.
 */
@Entity
@Table(name = "school_mail")
public class SchoolMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(name = "entry_id", nullable = false, length = 512)
    private String entryId;

    @Column(name = "thread_id", length = 64)
    private String threadId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt; // UTC

    @Column(name = "sender_name", nullable = false, length = 255)
    private String senderName;

    @Column(name = "sender_address", nullable = false, length = 255)
    private String senderAddress;

    @Column(nullable = false, length = 500)
    private String subject;

    @Convert(converter = CommaLists.Words.class)
    @Column(nullable = false, length = 100)
    private List<String> categories;

    @Column(name = "from_lecturer", nullable = false)
    private boolean fromLecturer;

    @Convert(converter = CommaLists.Dates.class)
    @Column(nullable = false, length = 400)
    private List<LocalDate> dates;

    @Column(name = "loses_points", nullable = false)
    private boolean losesPoints;

    @Column(name = "is_sorted", nullable = false)
    private boolean sorted;

    @Column(name = "blackboard_title", length = 255)
    private String blackboardTitle;

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
        this.threadId = threadId;
        this.receivedAt = receivedAt;
        this.senderName = senderName;
        this.senderAddress = senderAddress;
        this.subject = subject;
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.dates = dates;
        this.losesPoints = losesPoints;
        this.sorted = sorted;
        this.blackboardTitle = blackboardTitle;
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

    public String getEntryId() {
        return entryId;
    }

    public String getThreadId() {
        return threadId;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public String getSubject() {
        return subject;
    }

    public List<String> getCategories() {
        return categories;
    }

    public boolean isFromLecturer() {
        return fromLecturer;
    }

    public List<LocalDate> getDates() {
        return dates;
    }

    public boolean isLosesPoints() {
        return losesPoints;
    }

    public boolean isSorted() {
        return sorted;
    }

    public String getBlackboardTitle() {
        return blackboardTitle;
    }

    public List<SchoolMailChange> getChanges() {
        return changes;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailChange.java` (new):

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

/** A class change a lecturer's email announces: online, cancelled or make-up. Day and times are Vietnam time. */
@Entity
@Table(name = "school_mail_changes")
public class SchoolMailChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_id", nullable = false)
    private SchoolMail mail;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "change_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time")
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    @Column(length = 50)
    private String room;

    protected SchoolMailChange() {
    }

    public SchoolMailChange(SchoolMail mail, String courseCode, String kind, LocalDate day, LocalTime start,
            LocalTime end, String room) {
        this.mail = mail;
        this.courseCode = courseCode;
        this.kind = kind;
        this.day = day;
        this.start = start;
        this.end = end;
        this.room = room;
    }

    public Integer getId() {
        return id;
    }

    public SchoolMail getMail() {
        return mail;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getKind() {
        return kind;
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

    public String getRoom() {
        return room;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailChoice.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What the student chose for one email in Mailbox: Done, and Move to… (categories and "from a lecturer";
 * null means the laptop's sorting stands). Only the app changes; the real mailbox never does.
 */
@Entity
@Table(name = "school_mail_choices")
public class SchoolMailChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(nullable = false)
    private boolean done;

    @Convert(converter = CommaLists.Words.class)
    @Column(length = 100)
    private List<String> categories;

    @Column(name = "from_lecturer")
    private Boolean fromLecturer;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt; // UTC

    protected SchoolMailChoice() {
    }

    public SchoolMailChoice(Integer userId, String mailKey, LocalDateTime updatedAt) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.updatedAt = updatedAt;
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

    public boolean isDone() {
        return done;
    }

    public List<String> getCategories() {
        return categories;
    }

    public Boolean getFromLecturer() {
        return fromLecturer;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Whether Move to… chose anything for this email. */
    public boolean isMoved() {
        return categories != null || fromLecturer != null;
    }

    public void setDone(boolean done, LocalDateTime now) {
        this.done = done;
        this.updatedAt = now;
    }

    public void move(List<String> categories, boolean fromLecturer, LocalDateTime now) {
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.updatedAt = now;
    }

    public void backToAutomatic(LocalDateTime now) {
        this.categories = null;
        this.fromLecturer = null;
        this.updatedAt = now;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailStatus.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** When the laptop last read the Inbox, from which day, and whether Outlook was online. One row per user. */
@Entity
@Table(name = "school_mail_status")
public class SchoolMailStatus {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false)
    private LocalDate since;

    @Column(nullable = false)
    private boolean connected;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt; // UTC

    protected SchoolMailStatus() {
    }

    public SchoolMailStatus(Integer userId, LocalDate since, boolean connected, LocalDateTime syncedAt) {
        this.userId = userId;
        this.since = since;
        this.connected = connected;
        this.syncedAt = syncedAt;
    }

    public Integer getUserId() {
        return userId;
    }

    public LocalDate getSince() {
        return since;
    }

    public boolean isConnected() {
        return connected;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailRepository extends JpaRepository<SchoolMail, Integer> {

    /** Newest first. */
    List<SchoolMail> findByUserIdOrderByReceivedAtDescIdDesc(Integer userId);

    @Modifying
    @Query("delete from SchoolMail m where m.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailChangeRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailChangeRepository extends JpaRepository<SchoolMailChange, Integer> {

    /** A user's class changes from email, with their email. */
    @Query("select c from SchoolMailChange c join fetch c.mail m where m.userId = :userId")
    List<SchoolMailChange> findOfUser(Integer userId);

    @Modifying
    @Query("delete from SchoolMailChange c where c.mail.id in (select m.id from SchoolMail m where m.userId = :userId)")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailChoiceRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailChoiceRepository extends JpaRepository<SchoolMailChoice, Integer> {

    List<SchoolMailChoice> findByUserId(Integer userId);

    Optional<SchoolMailChoice> findByUserIdAndMailKey(Integer userId, String mailKey);

    /** Choices for emails that are no longer in the Inbox. */
    @Modifying
    @Query("delete from SchoolMailChoice c where c.userId = :userId and c.mailKey not in :keep")
    void deleteOthers(Integer userId, Collection<String> keep);

    @Modifying
    @Query("delete from SchoolMailChoice c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolMailStatusRepository.java` (new):

```java
package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMailStatusRepository extends JpaRepository<SchoolMailStatus, Integer> {
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`: 7 changes.

Change 1: replace

```java
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
```

with

```java
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
```

Change 3: replace

```java
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exam;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exams;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Section;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Timetable;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Tuition;

/**
 * Saves the result of a sync run, in one transaction. Each part that arrived correctly replaces that
 * user's rows for that term (Blackboard: all of them), after comparing old and new rows for the
 * "What changed" feed. A part that failed keeps its old rows. The Java twin of ingest.py.
 */
@Service
public class Ingest {
```

with

```java
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exam;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exams;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailClassChange;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailItem;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Outlook;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Section;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Timetable;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Tuition;

/**
 * Saves the result of a sync run, in one transaction. Each part that arrived correctly replaces that
 * user's rows for that term (Blackboard and Outlook: all of them), after comparing old and new rows for the
 * "What changed" feed (mail never adds to it). A part that failed keeps its old rows. The Java twin of
 * ingest.py.
 */
@Service
public class Ingest {
```

Change 4: replace

```java
    private final SchoolBbAnnouncementRepository bbAnnouncements;
    private final SchoolBbAssignmentRepository bbAssignments;
    private final SchoolBbMaterialRepository bbMaterials;

    public Ingest(SchoolSyncRunRepository runs, SchoolChangeRepository changes, SchoolCourseRepository courses,
            SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolTuitionRepository tuition,
            SchoolBbCourseRepository bbCourses, SchoolBbAnnouncementRepository bbAnnouncements,
            SchoolBbAssignmentRepository bbAssignments, SchoolBbMaterialRepository bbMaterials) {
        this.runs = runs;
        this.changes = changes;
        this.courses = courses;
```

with

```java
    private final SchoolBbAnnouncementRepository bbAnnouncements;
    private final SchoolBbAssignmentRepository bbAssignments;
    private final SchoolBbMaterialRepository bbMaterials;
    private final SchoolMailRepository mails;
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailChoiceRepository mailChoices;
    private final SchoolMailStatusRepository mailStatus;

    public Ingest(SchoolSyncRunRepository runs, SchoolChangeRepository changes, SchoolCourseRepository courses,
            SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolTuitionRepository tuition,
            SchoolBbCourseRepository bbCourses, SchoolBbAnnouncementRepository bbAnnouncements,
            SchoolBbAssignmentRepository bbAssignments, SchoolBbMaterialRepository bbMaterials,
            SchoolMailRepository mails, SchoolMailChangeRepository mailChanges, SchoolMailChoiceRepository mailChoices,
            SchoolMailStatusRepository mailStatus) {
        this.runs = runs;
        this.changes = changes;
        this.courses = courses;
```

Change 5: replace

```java
        this.bbAnnouncements = bbAnnouncements;
        this.bbAssignments = bbAssignments;
        this.bbMaterials = bbMaterials;
    }

    /** Aware time as sent -> UTC without an offset, as stored. */
```

with

```java
        this.bbAnnouncements = bbAnnouncements;
        this.bbAssignments = bbAssignments;
        this.bbMaterials = bbMaterials;
        this.mails = mails;
        this.mailChanges = mailChanges;
        this.mailChoices = mailChoices;
        this.mailStatus = mailStatus;
    }

    /** Aware time as sent -> UTC without an offset, as stored. */
```

Change 6: replace

```java
        part(run, summary, "exams", payload.exams(), data -> saveExams(userId, data, now), now);
        part(run, summary, "tuition", payload.tuition(), data -> saveTuition(userId, data), now);
        part(run, summary, "blackboard", payload.blackboard(), data -> saveBlackboard(userId, data), now);
        run.setSections(summary);
        return run.getStatus();
    }
```

with

```java
        part(run, summary, "exams", payload.exams(), data -> saveExams(userId, data, now), now);
        part(run, summary, "tuition", payload.tuition(), data -> saveTuition(userId, data), now);
        part(run, summary, "blackboard", payload.blackboard(), data -> saveBlackboard(userId, data), now);
        part(run, summary, "outlook", payload.outlook(), data -> saveOutlook(userId, data, now), now);
        run.setSections(summary);
        return run.getStatus();
    }
```

Change 7: replace

```java
        }
        return Changes.blackboard(old, fresh);
    }
}
```

with

```java
        }
        return Changes.blackboard(old, fresh);
    }

    /**
     * Replaces the user's mail with this upload, keeping the student's Done and Move to… choices for the emails
     * still there. An email sent twice with the same key is kept once (the first).
     */
    private List<Change> saveOutlook(Integer userId, Outlook data, LocalDateTime now) {
        mailChanges.deleteAllOfUser(userId);
        mails.deleteAllOfUser(userId);
        Set<String> keys = new LinkedHashSet<>();
        for (MailItem item : data.emails()) {
            if (!keys.add(item.key())) {
                continue;
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
        if (keys.isEmpty()) {
            mailChoices.deleteAllOfUser(userId);
        } else {
            mailChoices.deleteOthers(userId, keys);
        }
        mailStatus.save(new SchoolMailStatus(userId, data.since(), data.connected(), now));
        return List.of();
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`: 7 changes.

Change 1: replace

```java
 * Turns the sync history into the status the user sees. Pure functions; the Java twin of
 * app/school/services/sync_status.py, with the same wording. Times are UTC, shown in Vietnam time.
 *
 * <p>A run's parts are always read in the order timetable, exams, tuition, blackboard: MySQL keeps the
 * keys of the JSON column in its own order, so the order they come back in means nothing.
 */
public final class SyncStatus {
```

with

```java
 * Turns the sync history into the status the user sees. Pure functions; the Java twin of
 * app/school/services/sync_status.py, with the same wording. Times are UTC, shown in Vietnam time.
 *
 * <p>A run's parts are always read in the order timetable, exams, tuition, blackboard, outlook: MySQL keeps the
 * keys of the JSON column in its own order, so the order they come back in means nothing.
 */
public final class SyncStatus {
```

Change 2: replace

```java
    }

    static final String RETRY = "It will be tried again automatically.";
    static final List<String> PART_ORDER = List.of("timetable", "exams", "tuition", "blackboard");

    /** What a status says: state, headline, and what to do. */
    record Problem(String state, String headline, String detail) {
```

with

```java
    }

    static final String RETRY = "It will be tried again automatically.";
    static final List<String> PART_ORDER = List.of("timetable", "exams", "tuition", "blackboard", "outlook");

    /** What a status says: state, headline, and what to do. */
    record Problem(String state, String headline, String detail) {
```

Change 3: replace

```java
            "source_changed", new Problem("failed", "Sync failed: Blackboard's data format has changed",
                    "sla-agent needs an update to read it."));

    static final Map<String, String> PART_NAMES = Map.of(
            "timetable", "timetable", "exams", "exam schedule", "tuition", "tuition", "blackboard", "Blackboard");

    /** A system and the parts of a sync that come from it. */
    record SystemParts(String name, List<String> parts) {
```

with

```java
            "source_changed", new Problem("failed", "Sync failed: Blackboard's data format has changed",
                    "sla-agent needs an update to read it."));

    static final String CHECK_OUTLOOK = "Outlook may be showing a security warning, or your antivirus may be off. "
            + "The agent never clicks past it. Check Outlook, then press Sync now.";

    static final Map<String, Problem> OUTLOOK_PROBLEMS = Map.of(
            "outlook_not_set_up", new Problem("failed", "Sync failed: classic Outlook isn't set up on your laptop",
                    "Open Outlook (classic), sign in with your IU account, then press Sync now."),
            "outlook_blocked", new Problem("failed", "Sync failed: Outlook didn't let the agent read your mail",
                    CHECK_OUTLOOK));

    static final Map<String, String> PART_NAMES = Map.of(
            "timetable", "timetable", "exams", "exam schedule", "tuition", "tuition", "blackboard", "Blackboard",
            "outlook", "Outlook");

    /** A system and the parts of a sync that come from it. */
    record SystemParts(String name, List<String> parts) {
```

Change 4: replace

```java

    static final List<SystemParts> SYSTEMS = List.of(
            new SystemParts("EduSoft", List.of("timetable", "exams", "tuition")),
            new SystemParts("Blackboard", List.of("blackboard")));

    static final Map<String, String> PAUSE_HINTS = Map.of(
            "EduSoft/bad_credentials", "paused: wrong student ID or password. Run `sla-agent setup`.",
```

with

```java

    static final List<SystemParts> SYSTEMS = List.of(
            new SystemParts("EduSoft", List.of("timetable", "exams", "tuition")),
            new SystemParts("Blackboard", List.of("blackboard")),
            new SystemParts("Outlook", List.of("outlook")));

    static final Map<String, String> PAUSE_HINTS = Map.of(
            "EduSoft/bad_credentials", "paused: wrong student ID or password. Run `sla-agent setup`.",
```

Change 5: replace

```java
            "network", "couldn't be reached; it will be tried again automatically.",
            "session_expired", "ended the session; it will be tried again automatically.",
            "edusoft_changed", "its pages changed; sla-agent needs an update.",
            "source_changed", "its data format changed; sla-agent needs an update.");

    /** One sync run, as the status needs it. */
    public record RunInfo(String status, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode,
```

with

```java
            "network", "couldn't be reached; it will be tried again automatically.",
            "session_expired", "ended the session; it will be tried again automatically.",
            "edusoft_changed", "its pages changed; sla-agent needs an update.",
            "source_changed", "its data format changed; sla-agent needs an update.",
            "outlook_not_set_up", "classic Outlook isn't set up on your laptop; open it and sign in, then press Sync now.",
            "outlook_blocked", "Outlook didn't let the agent read your mail; check Outlook, then press Sync now.");

    /** One sync run, as the status needs it. */
    public record RunInfo(String status, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode,
```

Change 6: replace

```java
                code = failed.isEmpty() ? null : latest.parts().get(failed.get(0)).get("error_code");
                if (!failed.isEmpty() && failed.get(0).equals("blackboard")) {
                    problems = BLACKBOARD_PROBLEMS;
                }
            }
            problem = code == null ? UNKNOWN : problems.getOrDefault(code, UNKNOWN);
```

with

```java
                code = failed.isEmpty() ? null : latest.parts().get(failed.get(0)).get("error_code");
                if (!failed.isEmpty() && failed.get(0).equals("blackboard")) {
                    problems = BLACKBOARD_PROBLEMS;
                } else if (!failed.isEmpty() && failed.get(0).equals("outlook")) {
                    problems = OUTLOOK_PROBLEMS;
                }
            }
            problem = code == null ? UNKNOWN : problems.getOrDefault(code, UNKNOWN);
```

Change 7: replace

```java
        }
        return new Status("success", "Synced " + at(latest.finishedAt(), now), null, lastGoodFinishedAt, warning);
    }
}
```

with

```java
        }
        return new Status("success", "Synced " + at(latest.finishedAt(), now), null, lastGoodFinishedAt, warning);
    }

    /** A problem reading the Inbox, for the top of Mailbox: headline, what to do, and when. */
    public record MailProblem(String headline, String detail, LocalDateTime at) {
    }

    /** The Outlook problem of the newest finished run that included Outlook (runs newest first), or null. */
    public static MailProblem mailProblem(List<RunInfo> runs) {
        RunInfo run = runs.stream()
                .filter(r -> !r.status().equals(SchoolSyncRun.RUNNING) && r.parts().containsKey("outlook"))
                .findFirst().orElse(null);
        if (run == null || !"failed".equals(run.parts().get("outlook").get("status"))) {
            return null;
        }
        Problem problem = OUTLOOK_PROBLEMS.getOrDefault(run.parts().get("outlook").get("error_code"), UNKNOWN);
        return new MailProblem(problem.headline(), problem.detail(), run.finishedAt());
    }
}
```


- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='SchoolTablesTest,MigrationTest,IngestTest,SyncStatusTest')` → `Tests run: 55, Failures: 0, Errors: 0`. Then the whole suite: `(cd web && ./mvnw -B test)` → `Tests run: 390, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src/main/resources/db/migration/V20260928_1_1__school_mail.sql web/src/main/java/vn/edu/hcmiu/sla/school web/src/test/java/vn/edu/hcmiu/sla
git commit -m "feat(web): save each email's sorting results, keep the student's choices, and show Outlook in the sync status

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The Mailbox tab

`Mailbox` (pure) turns the stored emails and the student's choices into cards and boxes (§6.3): threads and emails sent twice become one card, cards go in the first box they fit, Events put training points first, Events and School tasks fold past cards away, Done cards go to the Done list. `MailboxController` shows the tab and handles Done, Undo, Move to… and Back to automatic. The School menu gets Mailbox after Overview.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailForm.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java`, `web/src/main/resources/templates/school/mailbox.html`, `web/src/main/resources/templates/school/mailbox-edit.html`
- Modify: `web/src/main/resources/templates/school/fragments.html` (the menu), `web/src/main/resources/static/css/style.css`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java`

**Interfaces:**
- Consumes: the entities and repositories and `SyncStatus.mailProblem` (Task 6); `VietnamTime`, `Flash`, `SchoolFormat` (`when`, `dayLabel`), `TestClock`, `SchoolTestData.user` (existing).
- Produces:
  - `Mailbox.CATEGORIES` (the 8 keys in order, with their names), `Mailbox.EVERYTHING_ELSE_SHOWN` (10); `record Card(key, keys, entryId, senderName, subject, receivedAt, categories, fromLecturer, moved, dates, nextDate, losesPoints, sorted, done, messages, copies)` with `trainingPoints()` and `past()`; `record Box(id, title, cards, more, past, pastTitle)` with `empty()` (ids `lecturers`, `tasks`, `money`, `events`, `other`); `record View(boxes, done)` with `card(key)`; `View build(List<SchoolMail> newestFirst, Map<String, SchoolMailChoice> choicesByKey, LocalDate today)`; `boolean validCategories(List<String>)`; `String sameSubject(String)`.
  - Pages: `GET /school/mailbox`; `POST /school/mailbox/{key}/done`, `/undone`, `/automatic`; `GET`/`POST /school/mailbox/{key}/edit` (fields `category1`, `category2`, `fromLecturer`); each card is `<li id="mail-{key}">`, each box `<section id="box-{id}">`, the Done list `#box-done`. `MailboxController.CATEGORY_ERROR`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/mail/MailboxTest.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.mail.Mailbox.Box;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.View;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;

/** The Mailbox tab's cards and boxes (spec 2026-09-28-outlook-mailbox-design.md, section 6.3). */
class MailboxTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 28); // Mon, in Vietnam
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0);

    /** An email; key and subject are the same short name, e.g. "tcl". Times are hours before NOW. */
    static final class Mail {
        String key;
        String thread;
        int hoursAgo;
        String sender = "oss@hcmiu.edu.vn";
        String subject;
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        boolean losesPoints;
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
            this.key = key;
            this.subject = key;
            this.hoursAgo = hoursAgo;
            this.categories = List.of(categories);
        }

        Mail thread(String thread) {
            this.thread = thread;
            return this;
        }

        Mail subject(String subject) {
            this.subject = subject;
            return this;
        }

        Mail from(String sender) {
            this.sender = sender;
            return this;
        }

        Mail lecturer() {
            this.lecturer = true;
            return this;
        }

        Mail on(int... daysFromToday) {
            this.dates = Arrays.stream(daysFromToday).mapToObj(TODAY::plusDays).sorted().toList();
            return this;
        }

        Mail losesPoints() {
            this.losesPoints = true;
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
        }

        SchoolMail row() {
            return new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender", sender,
                    subject, categories, lecturer, dates, losesPoints, sorted, null);
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, TODAY);
    }

    static View build(Mail... mails) {
        return build(Map.of(), mails);
    }

    static Box box(View view, String id) {
        return view.boxes().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    static List<String> keys(List<Card> cards) {
        return cards.stream().map(Card::key).toList();
    }

    static SchoolMailChoice done(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.setDone(true, NOW);
        return choice;
    }

    static SchoolMailChoice moved(String key, boolean lecturer, String... categories) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.move(List.of(categories), lecturer, NOW);
        return choice;
    }

    @Test
    void theBoxesComeInPriorityOrder() {
        View view = build();

        assertThat(view.boxes()).extracting(Box::title)
                .containsExactly("From lecturers", "School tasks", "Money", "Events", "Everything else");
        assertThat(view.boxes()).allMatch(Box::empty);
    }

    @Test
    void eachCardGoesInTheFirstBoxItFits() {
        View view = build(
                new Mail("lecturer", 1, "class").lecturer(),
                new Mail("survey", 2, "school_task", "training_points"),
                new Mail("invoice", 3, "money"),
                new Mail("workshop", 4, "event", "training_points"),
                new Mail("receipt", 5, "class"),
                new Mail("nothing", 6));

        assertThat(keys(box(view, "lecturers").cards())).containsExactly("lecturer");
        assertThat(keys(box(view, "tasks").cards())).containsExactly("survey");
        assertThat(keys(box(view, "money").cards())).containsExactly("invoice");
        assertThat(keys(box(view, "events").cards())).containsExactly("workshop");
        assertThat(keys(box(view, "other").cards())).containsExactly("receipt", "nothing");
    }

    @Test
    void eventsWithTrainingPointsComeFirstThenTheSoonest() {
        View view = build(
                new Mail("later-points", 1, "event", "training_points").on(5),
                new Mail("soon-no-points", 2, "event").on(1),
                new Mail("soon-points", 3, "event", "training_points").on(2),
                new Mail("undated", 4, "event"),
                new Mail("undated-points", 5, "event", "training_points"));

        assertThat(keys(box(view, "events").cards()))
                .containsExactly("soon-points", "later-points", "undated-points", "soon-no-points", "undated");
    }

    @Test
    void theNextDateIsTheEarliestFromTodayOn() {
        Card card = build(new Mail("closing", 1, "event").on(-6, 2)).card("closing");

        assertThat(card.nextDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(build(new Mail("today", 1, "event").on(0)).card("today").nextDate()).isEqualTo(TODAY);
    }

    @Test
    void pastEventsAndTasksFoldAwayButUndatedOnesStay() {
        View view = build(
                new Mail("over", 1, "event").on(-3),
                new Mail("long-over", 2, "event").on(-10),
                new Mail("undated", 3, "event"),
                new Mail("task-over", 4, "school_task").on(-1),
                new Mail("task-due", 5, "school_task").on(5));

        assertThat(keys(box(view, "events").cards())).containsExactly("undated");
        assertThat(keys(box(view, "events").past())).containsExactly("over", "long-over");
        assertThat(box(view, "events").pastTitle()).isEqualTo("Past events");
        assertThat(keys(box(view, "tasks").cards())).containsExactly("task-due");
        assertThat(keys(box(view, "tasks").past())).containsExactly("task-over");
    }

    @Test
    void otherBoxesNeverFoldPastDates() {
        assertThat(keys(box(build(new Mail("invoice", 1, "money").on(-5)), "money").cards())).containsExactly("invoice");
    }

    @Test
    void aThreadIsOneCardShowingItsNewestEmail() {
        View view = build(
                new Mail("first", 30, "class").lecturer().thread("T1").on(-2),
                new Mail("reply", 2, "class").lecturer().thread("T1"));

        Card card = box(view, "lecturers").cards().get(0);
        assertThat(box(view, "lecturers").cards()).hasSize(1);
        assertThat(List.of(card.key(), card.messages(), card.copies())).containsExactly("reply", 2, 1);
        assertThat(card.keys()).containsExactly("reply", "first");
    }

    @Test
    void theSameEmailSentTwiceIsOneCard() {
        View view = build(
                new Mail("tcl-1", 50, "event", "training_points").subject("[THƯ MỜI] sinh viên tham gia WORKSHOP “TỪ GIẢNG ĐƯỜNG TỚI CÔNG SỞ\""),
                new Mail("tcl-2", 30, "event", "training_points").subject("[THƯ MỜI] SINH VIÊN THAM GIA WORKSHOP “TỪ GIẢNG ĐƯỜNG TỚI CÔNG SỞ”"));

        Card card = box(view, "events").cards().get(0);
        assertThat(box(view, "events").cards()).hasSize(1);
        assertThat(List.of(card.key(), card.copies())).containsExactly("tcl-2", 2);
    }

    @Test
    void theSameSubjectFromAnotherSenderOrMonthsApartStaysSeparate() {
        View view = build(
                new Mail("a", 1, "event").subject("Workshop"),
                new Mail("b", 2, "event").subject("Workshop").from("hoisinhvien@hcmiu.edu.vn"),
                new Mail("c", 24 * 40, "event").subject("Workshop"));

        assertThat(box(view, "events").cards()).hasSize(3);
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
    void doneCardsLeaveTheirBoxAndANewReplyBringsThemBack() {
        Map<String, SchoolMailChoice> choices = new HashMap<>(Map.of("first", done("first")));

        View before = build(choices, new Mail("first", 30, "class").lecturer().thread("T"));
        View after = build(choices, new Mail("first", 30, "class").lecturer().thread("T"),
                new Mail("reply", 1, "class").lecturer().thread("T"));

        assertThat(keys(before.done())).containsExactly("first");
        assertThat(box(before, "lecturers").cards()).isEmpty();
        assertThat(after.done()).isEmpty();
        assertThat(keys(box(after, "lecturers").cards())).containsExactly("reply");
    }

    @Test
    void moveToWinsOverTheLaptopsSortingAndSurvivesANewReply() {
        Map<String, SchoolMailChoice> choices = Map.of("first", moved("first", true, "class"));

        View view = build(choices, new Mail("first", 30, "promotion").thread("T"),
                new Mail("reply", 1, "promotion").thread("T"));

        Card card = box(view, "lecturers").cards().get(0);
        assertThat(List.of(card.categories(), card.fromLecturer(), card.moved()))
                .containsExactly(List.of("class"), true, true);
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
    void anUnsortedEmailSaysSo() {
        assertThat(build(new Mail("x", 1).unsorted()).card("x").sorted()).isFalse();
    }

    @Test
    void validCategories() {
        assertThat(Mailbox.validCategories(List.of("event", "training_points"))).isTrue();
        assertThat(Mailbox.validCategories(List.of("class"))).isTrue();
        assertThat(Mailbox.validCategories(List.of())).isFalse();
        assertThat(Mailbox.validCategories(List.of("event", "event"))).isFalse();
        assertThat(Mailbox.validCategories(List.of("homework"))).isFalse();
        assertThat(Mailbox.validCategories(List.of("event", "money", "class"))).isFalse();
    }

    @Test
    void sameSubjectIgnoresCaseQuotesAndPunctuation() {
        assertThat(Mailbox.sameSubject("[THƯ MỜI] Workshop “A”")).isEqualTo(Mailbox.sameSubject("[thư mời] WORKSHOP \"a\""));
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java` (new):

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
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;

/** The Mailbox tab: boxes, cards, Done, Move to…, and what it says when Outlook has a problem. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class MailboxPageTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam
    static final String TCL = "a".repeat(64);
    static final String LAB_QUESTION = "b".repeat(64);
    static final String LAB_REPLY = "c".repeat(64);
    static final String INVOICE = "d".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    SchoolMailChoiceRepository choices;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        clock.set(NOW);
    }

    @AfterEach
    void realTime() {
        clock.reset();
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

    void run(AppUser who, Map<String, Map<String, String>> sections, String status) {
        SchoolSyncRun run = new SchoolSyncRun(who.id(), null, "scheduled", NOW.minusMinutes(10));
        run.finish(status, NOW.minusMinutes(9), null, null);
        run.setSections(sections);
        db.persist(run);
        db.flush();
    }

    String page() throws Exception {
        return mvc.perform(get("/school/mailbox").with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The HTML of one box. */
    static String box(String html, String id) {
        int start = html.indexOf("id=\"box-" + id + "\"");
        int end = html.indexOf("id=\"box-", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    @Test
    void theBoxesShowEachCardInItsPlace() throws Exception {
        inbox(an);

        String html = page();

        assertThat(html.indexOf("From lecturers")).isLessThan(html.indexOf("School tasks"));
        assertThat(box(html, "lecturers")).contains("Re: Slide bài tập bị thiếu số trang").contains("2 messages")
                .doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("Couldn't sort this email automatically. Use Move to…");
        assertThat(box(html, "events")).contains("★ Training points").contains("⚠ lose points if absent")
                .contains("Next: Wed 30/09");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00");
    }

    @Test
    void eachCardOpensTheExactEmailOnTheLaptopOrOutlookOnTheWeb() throws Exception {
        inbox(an);

        String card = box(page(), "events");

        assertThat(card).contains("href=\"sla-mail:00A1AAAA\"");
        assertThat(card).contains("href=\"https://outlook.office.com/mail/\" target=\"_blank\" rel=\"noopener noreferrer\"");
    }

    @Test
    void beforeOutlookIsConnectedThePageSaysHow() throws Exception {
        assertThat(page()).contains("Outlook isn't connected yet.").contains("sla-agent setup --outlook")
                .doesNotContain("From lecturers");
    }

    @Test
    void anOutlookProblemIsShownAtTheTop() throws Exception {
        inbox(an);
        run(an, Map.of("timetable", Map.of("status", "ok"), "outlook",
                Map.of("status", "failed", "error_code", "outlook_blocked", "error_message", "x")), "partial");

        String html = page();

        assertThat(html).contains("Sync failed: Outlook didn&#39;t let the agent read your mail")
                .contains("The agent never clicks past it.");
        assertThat(html.indexOf("role=\"alert\"")).isLessThan(html.indexOf("From lecturers"));
    }

    @Test
    void anOfflineOutlookGetsAYellowNote() throws Exception {
        db.persist(new SchoolMailStatus(an.id(), LocalDate.of(2026, 8, 1), false, NOW));
        db.flush();

        assertThat(page()).contains("Outlook was offline when your laptop last read it");
    }

    @Test
    void doneMovesACardToTheDoneListAndUndoBringsItBack() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + INVOICE + "/done").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + INVOICE));
        assertThat(box(page(), "money")).doesNotContain("Xuất hóa đơn");
        assertThat(box(page(), "done")).contains("Done (1)").contains("Xuất hóa đơn").contains(">Undo<");

        mvc.perform(post("/school/mailbox/" + INVOICE + "/undone").with(user(an)).with(csrf()));
        assertThat(box(page(), "money")).contains("Xuất hóa đơn");
    }

    @Test
    void moveToPutsACardWhereTheStudentChoseForEveryEmailOfTheThread() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + LAB_REPLY + "/edit").with(user(an)).with(csrf())
                        .param("category1", "school_task").param("category2", "").param("fromLecturer", "false"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + LAB_REPLY));

        assertThat(box(page(), "tasks")).contains("Re: Slide bài tập").contains("School task");
        assertThat(choices.findByUserId(an.id())).extracting(SchoolMailChoice::getMailKey)
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    @Test
    void theMoveToPageStartsFromTheCardsCategories() throws Exception {
        inbox(an);

        String html = mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("<option value=\"event\" selected=\"selected\">Event</option>");
        assertThat(html).containsPattern(
                "id=\"category2\"[\\s\\S]*<option value=\"training_points\" selected=\"selected\">Training points</option>");
    }

    @Test
    void badCategoriesAreRefusedWithAMessage() throws Exception {
        inbox(an);

        String html = mvc.perform(post("/school/mailbox/" + TCL + "/edit").with(user(an)).with(csrf())
                        .param("category1", "event").param("category2", "event"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MailboxController.CATEGORY_ERROR);
        assertThat(choices.findByUserId(an.id())).isEmpty();
    }

    @Test
    void backToAutomaticUndoesMoveTo() throws Exception {
        inbox(an);
        mvc.perform(post("/school/mailbox/" + TCL + "/edit").with(user(an)).with(csrf())
                .param("category1", "promotion").param("category2", ""));

        mvc.perform(post("/school/mailbox/" + TCL + "/automatic").with(user(an)).with(csrf()));

        assertThat(box(page(), "events")).contains("Workshop");
    }

    @Test
    void someoneElsesCardIs404() throws Exception {
        AppUser binh = data.user("binh@example.com");
        inbox(binh);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an))).andExpect(status().isNotFound());
        assertThat(page()).doesNotContain("Workshop");
    }

    @Test
    void formsNeedTheCsrfToken() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an))).andExpect(status().isForbidden());
    }

    @Test
    void theSchoolMenuHasMailboxAfterOverview() throws Exception {
        String menu = page();
        menu = menu.substring(menu.indexOf("class=\"subnav\""));

        assertThat(menu.indexOf(">Overview<")).isLessThan(menu.indexOf(">Mailbox<"));
        assertThat(menu.indexOf(">Mailbox<")).isLessThan(menu.indexOf(">Timetable<"));
    }
}
```


- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest')`
Expected: compilation fails: `package vn.edu.hcmiu.sla.school.mail does not exist`.

- [ ] **Step 3: The cards and boxes, the controller, the pages and the menu**

`web/src/main/java/vn/edu/hcmiu/sla/school/mail/Mailbox.java` (new):

```java
package vn.edu.hcmiu.sla.school.mail;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;

/**
 * What the Mailbox tab shows: emails grouped into cards, cards in boxes, in the order of
 * docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.3. Pure functions.
 */
public final class Mailbox {

    private Mailbox() {
    }

    /** The categories in the order they are shown, with their names. */
    public static final Map<String, String> CATEGORIES = orderedNames(
            "class", "Class", "school_task", "School task", "money", "Money", "event", "Event",
            "training_points", "Training points", "requests_account", "Your requests & account",
            "system_notice", "System notice", "promotion", "Promotion");
    static final Duration SAME_EMAIL_WINDOW = Duration.ofDays(30);
    public static final int EVERYTHING_ELSE_SHOWN = 10;

    private static Map<String, String> orderedNames(String... pairs) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            names.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(names);
    }

    /**
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. nextDate: its earliest date from today on.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, LocalDate nextDate, boolean losesPoints, boolean sorted, boolean done,
            int messages, int copies) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
        }

        /** Every date is over (a card without dates is never past). */
        public boolean past() {
            return !dates.isEmpty() && nextDate == null;
        }

        LocalDate lastDate() {
            return dates.isEmpty() ? null : dates.get(dates.size() - 1);
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

    /** The boxes in order, and the closed "Done" list. */
    public record View(List<Box> boxes, List<Card> done) {

        public Card card(String key) {
            return boxes.stream().flatMap(b -> Stream.of(b.cards(), b.more(), b.past()))
                    .flatMap(List::stream).filter(c -> c.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> done.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null));
        }
    }

    /** Lower case, letters and digits only, one space between words: how "the same subject" is compared. */
    static String sameSubject(String subject) {
        String text = Normalizer.normalize(subject, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return text.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** A card's emails, newest first, and how many separate sends it merged. */
    private record Group(List<SchoolMail> mails, int copies) {
    }

    /** Emails of one thread form a group; groups with the same sender and subject within 30 days merge. */
    private static List<Group> groups(List<SchoolMail> newestFirst) {
        Map<String, List<SchoolMail>> threads = new LinkedHashMap<>();
        for (SchoolMail mail : newestFirst) {
            String thread = mail.getThreadId() != null ? "t:" + mail.getThreadId() : "k:" + mail.getMailKey();
            threads.computeIfAbsent(thread, t -> new ArrayList<>()).add(mail);
        }
        List<List<SchoolMail>> merged = new ArrayList<>();
        List<Integer> copies = new ArrayList<>();
        for (List<SchoolMail> thread : threads.values()) {
            int same = sameEmail(merged, thread.get(0));
            if (same >= 0) {
                merged.get(same).addAll(thread);
                copies.set(same, copies.get(same) + 1);
            } else {
                merged.add(new ArrayList<>(thread));
                copies.add(1);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            List<SchoolMail> mails = merged.get(i);
            mails.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
            groups.add(new Group(mails, copies.get(i)));
        }
        return groups;
    }

    /** The group whose newest email is this email sent again, or -1. */
    private static int sameEmail(List<List<SchoolMail>> groups, SchoolMail mail) {
        for (int i = 0; i < groups.size(); i++) {
            SchoolMail other = groups.get(i).get(0);
            if (other.getSenderAddress().equalsIgnoreCase(mail.getSenderAddress())
                    && sameSubject(other.getSubject()).equals(sameSubject(mail.getSubject()))
                    && Duration.between(mail.getReceivedAt(), other.getReceivedAt()).abs()
                            .compareTo(SAME_EMAIL_WINDOW) <= 0) {
                return i;
            }
        }
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
                moved != null, List.copyOf(dates), next, mails.stream().anyMatch(SchoolMail::isLosesPoints),
                newest.isSorted(), newestChoice != null && newestChoice.isDone(), mails.size(), group.copies());
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
    private static final Comparator<Card> SOONEST_FIRST = Comparator.comparing(Card::nextDate,
            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(NEWEST_FIRST);
    private static final Comparator<Card> LATEST_DATE_FIRST = Comparator.comparing(Card::lastDate,
            Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())).thenComparing(NEWEST_FIRST);

    private static String boxOf(Card card) {
        if (card.fromLecturer()) {
            return "lecturers";
        }
        for (String[] box : new String[][] {{"school_task", "tasks"}, {"money", "money"}, {"event", "events"}}) {
            if (card.categories().contains(box[0])) {
                return box[1];
            }
        }
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

        List<Card> events = byBox.get("events");
        List<Card> tasks = byBox.get("tasks");
        List<Card> other = byBox.get("other");
        byBox.get("lecturers").sort(NEWEST_FIRST);
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

    private static List<Card> current(List<Card> cards, Comparator<Card> order) {
        return cards.stream().filter(c -> !c.past()).sorted(order).toList();
    }

    private static List<Card> past(List<Card> cards) {
        return cards.stream().filter(Card::past).sorted(LATEST_DATE_FIRST).toList();
    }

    /** Whether these are allowed Move to… categories: one or two known ones, different. */
    public static boolean validCategories(List<String> categories) {
        return !categories.isEmpty() && categories.size() <= 2
                && categories.stream().allMatch(c -> c != null && CATEGORIES.containsKey(c))
                && categories.stream().distinct().count() == categories.size();
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailForm.java` (new):

```java
package vn.edu.hcmiu.sla.school.pages;

import java.util.ArrayList;
import java.util.List;

/** Move to…: one or two categories, and whether the email is from a lecturer. category2 "" means none. */
public class MailForm {

    private String category1 = "";
    private String category2 = "";
    private boolean fromLecturer;

    public MailForm() {
    }

    MailForm(List<String> categories, boolean fromLecturer) {
        this.category1 = categories.isEmpty() ? "" : categories.get(0);
        this.category2 = categories.size() < 2 ? "" : categories.get(1);
        this.fromLecturer = fromLecturer;
    }

    /** The chosen categories, in the order chosen. */
    List<String> categories() {
        List<String> chosen = new ArrayList<>();
        chosen.add(category1);
        if (!category2.isEmpty()) {
            chosen.add(category2);
        }
        return chosen;
    }

    public String getCategory1() {
        return category1;
    }

    public void setCategory1(String category1) {
        this.category1 = category1 == null ? "" : category1.strip();
    }

    public String getCategory2() {
        return category2;
    }

    public void setCategory2(String category2) {
        this.category2 = category2 == null ? "" : category2.strip();
    }

    public boolean isFromLecturer() {
        return fromLecturer;
    }

    public void setFromLecturer(boolean fromLecturer) {
        this.fromLecturer = fromLecturer;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java` (new):

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
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
public class MailboxController {

    static final String CATEGORY_ERROR = "Choose a first category, and a different second one or none.";

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

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Mailbox.View view(Integer userId) {
        Map<String, SchoolMailChoice> byKey = choices.findByUserId(userId).stream()
                .collect(Collectors.toMap(SchoolMailChoice::getMailKey, Function.identity()));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, VietnamTime.date(now()));
    }

    /** The user's card whose newest email has this key, else 404. */
    private Card card(Integer userId, String key) {
        Card card = view(userId).card(key);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
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
        model.addAttribute("categories", Mailbox.CATEGORIES);
        return "school/mailbox";
    }

    @PostMapping("/{key}/done")
    String done(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        return setDone(user.id(), key, true);
    }

    @PostMapping("/{key}/undone")
    String undone(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        return setDone(user.id(), key, false);
    }

    private String setDone(Integer userId, String key, boolean done) {
        Card card = card(userId, key);
        SchoolMailChoice choice = choice(userId, card.key());
        choice.setDone(done, now());
        choices.save(choice);
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @GetMapping("/{key}/edit")
    String edit(@AuthenticationPrincipal AppUser user, @PathVariable String key, Model model) {
        Card card = card(user.id(), key);
        return editPage(model, card, new MailForm(card.categories(), card.fromLecturer()), null);
    }

    private String editPage(Model model, Card card, MailForm form, String error) {
        model.addAttribute("card", card);
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        model.addAttribute("categories", Mailbox.CATEGORIES);
        return "school/mailbox-edit";
    }

    @PostMapping("/{key}/edit")
    String move(@AuthenticationPrincipal AppUser user, @PathVariable String key, @ModelAttribute("form") MailForm form,
            Model model, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
        if (!Mailbox.validCategories(form.categories())) {
            return editPage(model, card, form, CATEGORY_ERROR);
        }
        for (String mailKey : card.keys()) {
            SchoolMailChoice choice = choice(user.id(), mailKey);
            choice.move(form.categories(), form.isFromLecturer(), now());
            choices.save(choice);
        }
        Flash.success(redirect, "Moved. The app will keep this email where you put it.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @PostMapping("/{key}/automatic")
    String automatic(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
        for (String mailKey : card.keys()) {
            choices.findByUserIdAndMailKey(user.id(), mailKey).ifPresent(choice -> {
                choice.backToAutomatic(now());
                choices.save(choice);
            });
        }
        Flash.success(redirect, "Back to automatic: the laptop's sorting applies again.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }
}
```

`web/src/main/resources/templates/school/mailbox.html` (new):

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Mailbox · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Mailbox</h1>
  <nav th:replace="~{school/fragments :: subnav('mailbox')}"></nav>

  <section class="card status-card status-failed" th:if="${problem != null}" role="alert">
    <p class="status-headline" th:text="${problem.headline}">Sync failed: Outlook didn't let the agent read your mail</p>
    <p class="status-detail" th:text="${problem.detail}">Check Outlook, then press Sync now.</p>
    <p class="status-detail" th:if="${problem.at != null}" th:text="|Last try: ${@schoolFormat.when(problem.at)}|">Last try:
      Mon 28/09 07:02</p>
  </section>

  <section class="card" th:if="${status == null}">
    <p>Outlook isn't connected yet. On your laptop, open Outlook (classic), sign in, then run
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
    <span class="tag tag-warning" th:if="${card.losesPoints}">⚠ lose points if absent</span>
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
</html>
```

`web/src/main/resources/templates/school/mailbox-edit.html` (new):

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Move to… · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Move to…</h1>
  <nav th:replace="~{school/fragments :: subnav('mailbox')}"></nav>

  <section class="card narrow">
    <p><strong th:text="${card.subject.isEmpty() ? '(no subject)' : card.subject}">[THƯ MỜI] Workshop</strong><br>
      <span class="muted" th:text="${card.senderName}">P.CTSV [OSS]</span></p>
    <p class="field-error" th:if="${error != null}" th:text="${error}">Choose a first category.</p>

    <form method="post" th:action="@{/school/mailbox/{key}/edit(key=${card.key})}" th:object="${form}">
      <div class="field">
        <label for="category1">Category 1</label>
        <select id="category1" th:field="*{category1}">
          <option th:each="category : ${categories}" th:value="${category.key}" th:text="${category.value}">Event</option>
        </select>
      </div>
      <div class="field">
        <label for="category2">Category 2 (optional)</label>
        <select id="category2" th:field="*{category2}">
          <option value="">None</option>
          <option th:each="category : ${categories}" th:value="${category.key}" th:text="${category.value}">Training
            points</option>
        </select>
      </div>
      <div class="field">
        <label class="check"><input type="checkbox" th:field="*{fromLecturer}"> This is from a lecturer</label>
      </div>
      <div class="form-actions">
        <button type="submit" class="button">Save</button>
        <a th:href="@{/school/mailbox}">Cancel</a>
      </div>
    </form>

    <form method="post" th:action="@{/school/mailbox/{key}/automatic(key=${card.key})}" class="mail-automatic">
      <button type="submit" class="link-button">Back to automatic</button>
      <span class="muted">(the laptop's sorting applies again)</span>
    </form>
  </section>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/fragments.html`: one change.

Replace

```html
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<body>

<!--/* The school menu; current is the page's name: overview, timetable, courses, exams, tuition or devices. */-->
<nav th:fragment="subnav(current)" class="subnav" aria-label="School pages">
  <a th:href="@{/school}" th:attr="aria-current=${current == 'overview' ? 'page' : null}">Overview</a>
  <a th:href="@{/school/timetable}" th:attr="aria-current=${current == 'timetable' ? 'page' : null}">Timetable</a>
  <a th:href="@{/school/courses}" th:attr="aria-current=${current == 'courses' ? 'page' : null}">Courses</a>
  <a th:href="@{/school/exams}" th:attr="aria-current=${current == 'exams' ? 'page' : null}">Exams</a>
```

with

```html
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<body>

<!--/* The school menu; current is the page's name: overview, mailbox, timetable, courses, exams, tuition or devices. */-->
<nav th:fragment="subnav(current)" class="subnav" aria-label="School pages">
  <a th:href="@{/school}" th:attr="aria-current=${current == 'overview' ? 'page' : null}">Overview</a>
  <a th:href="@{/school/mailbox}" th:attr="aria-current=${current == 'mailbox' ? 'page' : null}">Mailbox</a>
  <a th:href="@{/school/timetable}" th:attr="aria-current=${current == 'timetable' ? 'page' : null}">Timetable</a>
  <a th:href="@{/school/courses}" th:attr="aria-current=${current == 'courses' ? 'page' : null}">Courses</a>
  <a th:href="@{/school/exams}" th:attr="aria-current=${current == 'exams' ? 'page' : null}">Exams</a>
```

`web/src/main/resources/static/css/style.css`: one change.

Replace

```css
.badge-cancelled { background: var(--cancelled-color); color: var(--text); }
.fc .fc-list-event a.event-title { color: inherit; text-decoration: none; }
.fc .fc-list-event.event-cancelled a.event-title { text-decoration: line-through; }
```

with

```css
.badge-cancelled { background: var(--cancelled-color); color: var(--text); }
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
.tag-warning { color: var(--error); background: var(--error-bg); border-color: var(--error-bg); }
.mail-unsorted { margin: 0 0 6px; color: #c77700; font-size: 0.9rem; }
.mail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; }
.mail-open { padding: 4px 12px; font-size: 0.9rem; text-decoration: none; }
.mailbox-box details { margin-top: 8px; }
.mailbox-box summary { cursor: pointer; color: var(--accent); }
.field select {
  width: 100%;
  padding: 10px 12px;
  font: inherit;
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.field .check { display: flex; gap: 8px; align-items: center; font-weight: 400; }
.field .check input { width: auto; margin: 0; }
.form-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.mail-automatic { margin-top: 12px; }
```


- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='MailboxTest,MailboxPageTest')` → `Tests run: 29, Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 419, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/mail web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailForm.java web/src/main/java/vn/edu/hcmiu/sla/school/pages/MailboxController.java web/src/main/resources/templates/school web/src/main/resources/static/css/style.css web/src/test/java/vn/edu/hcmiu/sla/school/mail web/src/test/java/vn/edu/hcmiu/sla/school/pages/MailboxPageTest.java
git commit -m "feat(web): the Mailbox tab: priority boxes, training points first, Past, Done and Move to

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Class changes from lecturers' emails in the Timetable

`ClassChanges` folds announcements and emailed changes together, newest first wins per course, day and slot, exactly as before for announcements alone. Each change carries a `Source`: the course page ("See announcement") or the email in Mailbox ("See email"). `Schedule` skips an email that is Blackboard's copy of a stored announcement (same course code and title, case and outer spaces ignored), whichever arrived first (§6.4).

**Files:**
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java`, `Schedule.java`, `CalendarController.java`, `web/src/main/resources/templates/school/fragments.html` (the item's link)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java`

**Interfaces:**
- Consumes: `SchoolMailChangeRepository.findOfUser` and the `SchoolMail`/`SchoolMailChange` getters (Task 6).
- Produces: `ClassChanges.Source(link, text)` with `Source.course(int bbCourseId)` and `Source.email(String mailKey)`; `ClassChange(code, source, kind, day, start, end, room)` and `Posted(code, source, title, text, postedAt)` now take a `Source` instead of `bbCourseId`; `Emailed(code, source, postedAt, change)`; `changesFrom(List<Posted>, List<Emailed>)`; `Schedule.Item` has `Source source()` instead of `bbCourseId()`; the calendar feed's `url` is `source.link()`; `SchoolTestData.lecturerEmail(user, key, receivedAt, blackboardTitle)`, `emailChange(mail, code, kind, day, start, end, room)`, `save(SchoolMail)`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java`: 3 changes.

Change 1: replace

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
```

with

```java

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;

/** Rows for School page tests, saved straight into the test database. All times are UTC. */
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;

/** Rows for School page tests, saved straight into the test database. All times are UTC. */
```

Change 3: replace

```java
        db.flush();
        return course.getId();
    }
}
```

with

```java
        db.flush();
        return course.getId();
    }

    /**
     * A lecturer's email as the laptop sorted it; add class changes with {@link #emailChange}, then {@link #save}.
     * blackboardTitle: set when it is Blackboard's copy of an announcement.
     */
    public SchoolMail lecturerEmail(AppUser user, String key, LocalDateTime receivedAt, String blackboardTitle) {
        return new SchoolMail(user.id(), key, "00A1", null, receivedAt, "Tran Van An", "tvan@hcmiu.edu.vn",
                blackboardTitle != null ? "Course_S1: " + blackboardTitle : "Class notice", List.of("class"), true,
                List.of(), false, true, blackboardTitle);
    }

    public SchoolMail emailChange(SchoolMail mail, String code, String kind, LocalDate day, LocalTime start,
            LocalTime end, String room) {
        mail.getChanges().add(new SchoolMailChange(mail, code, kind, day, start, end, room));
        return mail;
    }

    public void save(SchoolMail mail) {
        db.persist(mail);
        db.flush();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`: 4 changes.

Change 1: replace

```java
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;

/** Java twin of tests/test_school_class_changes.py. postedAt values are UTC. */
class ClassChangesTest {
```

with

```java
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Source;

/** Java twin of tests/test_school_class_changes.py. postedAt values are UTC. */
class ClassChangesTest {
```

Change 2: replace

```java
    // ---- Changes by course and day -------------------------------------------------

    static Posted row(String title, LocalDateTime posted) {
        return new Posted("MA026IU", 5, title, "", posted);
    }

    @Test
```

with

```java
    // ---- Changes by course and day -------------------------------------------------

    static Posted row(String title, LocalDateTime posted) {
        return new Posted("MA026IU", Source.course(5), title, "", posted);
    }

    @Test
```

Change 3: replace

```java
                row("Online class on 24/9", LocalDateTime.of(2026, 9, 20, 0, 0))));

        assertThat(changes).containsExactly(Map.entry(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class"),
                new ClassChange("MA026IU", 5, "cancelled", LocalDate.of(2026, 9, 24), null, null, null)));
    }

    @Test
```

with

```java
                row("Online class on 24/9", LocalDateTime.of(2026, 9, 20, 0, 0))));

        assertThat(changes).containsExactly(Map.entry(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class"),
                new ClassChange("MA026IU", Source.course(5), "cancelled", LocalDate.of(2026, 9, 24), null, null, null)));
    }

    @Test
```

Change 4: replace

```java
    @Test
    void announcementsWithoutACourseCodeOrTimeAreSkipped() {
        assertThat(ClassChanges.changesFrom(List.of(row("Online class on 24/9", null),
                new Posted(null, 5, "Online class on 24/9", "", POSTED)))).isEmpty();
    }

    @Test
```

with

```java
    @Test
    void announcementsWithoutACourseCodeOrTimeAreSkipped() {
        assertThat(ClassChanges.changesFrom(List.of(row("Online class on 24/9", null),
                new Posted(null, Source.course(5), "Online class on 24/9", "", POSTED)))).isEmpty();
    }

    @Test
```

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`: 2 changes.

Change 1: replace

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
```

with

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
```

Change 2: replace

```java

        assertThat(classes()).singleElement().extracting(e -> e.get("classNames")).isEqualTo(List.of("event-class"));
    }
}
```

with

```java

        assertThat(classes()).singleElement().extracting(e -> e.get("classNames")).isEqualTo(List.of("event-class"));
    }

    // ---- Class changes from lecturers' emails ----------------------------------------

    static final String KEY = "e".repeat(64);
    static final LocalDate SEPT_24 = LocalDate.of(2026, 9, 24);

    @Test
    void anEmailsChangeMarksTheClassAndLinksToTheEmail() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED, null), "MA026IU", "online", SEPT_24, null, null,
                null));

        Map<String, Object> event = classes().get(0);

        assertThat(event.get("title")).isEqualTo("Online: " + PROBABILITY);
        assertThat(event.get("url")).isEqualTo("/school/mailbox#mail-" + KEY);
    }

    @Test
    void aBlackboardCopyIsSkippedWhenTheAnnouncementIsStoredWhicheverCameFirst() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        Integer courseId = announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.minusMinutes(5), " online class on september 24 "),
                "MA026IU", "online", SEPT_24, null, null, null));

        Map<String, Object> event = classes().get(0);

        assertThat(classes()).hasSize(1);
        assertThat(event.get("url")).isEqualTo("/school/courses/" + courseId);
    }

    @Test
    void aBlackboardCopyCountsWhileBlackboardHasNotSyncedIt() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED, "Online class on September 24"), "MA026IU",
                "online", SEPT_24, null, null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsExactly("online");
    }

    @Test
    void theSameChangeSentBothWaysIsOneClass() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(1), null), "MA026IU", "online",
                SEPT_24, null, null, null));

        assertThat(classes()).hasSize(1);
        assertThat(classes().get(0).get("url")).isEqualTo("/school/mailbox#mail-" + KEY);
    }

    @Test
    void aNewerEmailOverridesAnOlderAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(3), null), "MA026IU", "cancelled",
                SEPT_24, null, null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsExactly("cancelled");
    }

    @Test
    void aMakeUpByEmailKeepsTheCancellationByAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(3), null), "MA026IU", "makeup",
                LocalDate.of(2026, 9, 26), LocalTime.of(8, 0), LocalTime.of(9, 40), "A2.401"));

        List<Map<String, Object>> week = classes();

        assertThat(week).extracting(e -> props(e).get("change")).containsExactly("cancelled", "makeup");
        assertThat(week.get(1).get("start")).isEqualTo("2026-09-26T08:00:00");
        assertThat(props(week.get(1)).get("room")).isEqualTo("A2.401");
    }

    @Test
    void anotherUsersEmailsNeverChangeMyClasses() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        AppUser binh = data.user("binh@example.com");
        data.save(data.emailChange(data.lecturerEmail(binh, KEY, POSTED, null), "MA026IU", "cancelled", SEPT_24, null,
                null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsOnlyNulls();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java`: one change.

Replace

```java
        assertThat(linkTo(html, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(html.split("Open in Blackboard ↗", -1)).hasSize(5); // the course and its three items
    }
}
```

with

```java
        assertThat(linkTo(html, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(html.split("Open in Blackboard ↗", -1)).hasSize(5); // the course and its three items
    }

    @Test
    void aClassChangedByEmailLinksToTheEmailInMailbox() throws Exception {
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 0)); // Tue 29/09 07:00 in Vietnam
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        String key = "f".repeat(64);
        data.save(data.emailChange(data.lecturerEmail(an, key, LocalDateTime.of(2026, 9, 28, 2, 0), null), "IT093IU",
                "online", LocalDate.of(2026, 9, 29), null, null, null));

        String html = page("/school");

        assertThat(linkTo(html, "/school/mailbox#mail-" + key)).isNotEmpty();
        assertThat(html).contains(">See email</a>");
    }
}
```


- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B test -Dtest='ClassChangesTest,CalendarFeedTest,SchoolPagesTest')`
Expected: compilation fails: `cannot find symbol: class Source` in `ClassChangesTest`.

- [ ] **Step 3: Emailed changes in the fold, the skip, and the link**

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java`: 3 changes.

Change 1: replace

```java
        }
    }

    /** A change for one course: code "MA026IU", bbCourseId the app's page for the course that posted it. */
    public record ClassChange(String code, int bbCourseId, String kind, LocalDate day, LocalTime start, LocalTime end,
            String room) {
    }
```

with

```java
        }
    }

    /**
     * Where a change links to: the course page for a Blackboard announcement ("See announcement"), or the email
     * in Mailbox for a lecturer's email ("See email").
     */
    public record Source(String link, String text) {

        public static Source course(int bbCourseId) {
            return new Source("/school/courses/" + bbCourseId, "See announcement");
        }

        public static Source email(String mailKey) {
            return new Source("/school/mailbox#mail-" + mailKey, "See email");
        }
    }

    /** A change for one course, code "MA026IU", and where it was announced. */
    public record ClassChange(String code, Source source, String kind, LocalDate day, LocalTime start, LocalTime end,
            String room) {
    }
```

Change 2: replace

```java
    }

    /** An announcement with its course: what {@link #changesFrom} reads. postedAt is UTC. */
    public record Posted(String code, int bbCourseId, String title, String text, LocalDateTime postedAt) {
    }

    /** Reads one announcement; tests swap in a reader that fails. */
```

with

```java
    }

    /** An announcement with its course: what {@link #changesFrom} reads. postedAt is UTC. */
    public record Posted(String code, Source source, String title, String text, LocalDateTime postedAt) {
    }

    /** A change the laptop already read from a lecturer's email. postedAt (when it arrived) is UTC. */
    public record Emailed(String code, Source source, LocalDateTime postedAt, Announced change) {
    }

    /** Reads one announcement; tests swap in a reader that fails. */
```

Change 3: replace

```java
     * posting time are skipped, and so is one that can't be read.
     */
    public static Map<Slot, ClassChange> changesFrom(List<Posted> announcements) {
        return changesFrom(announcements, ClassChanges::readAnnouncement);
    }

    static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, Reader reader) {
        Map<Slot, ClassChange> changes = new LinkedHashMap<>();
        List<Posted> dated = announcements.stream()
                .filter(a -> a.code() != null && !a.code().isEmpty() && a.postedAt() != null)
                .sorted(Comparator.comparing(Posted::postedAt))
                .toList();
        for (Posted a : dated) {
            List<Announced> announced;
            try {
                announced = reader.read(a.title(), a.text(), a.postedAt());
            } catch (RuntimeException error) { // one unreadable announcement must never break a page
                log.warn("Couldn't read an announcement for class changes", error);
                continue;
            }
            for (Announced one : announced) {
                String slot = one.kind().equals("makeup") ? "makeup" : "class";
                changes.put(new Slot(a.code(), one.day(), slot), new ClassChange(a.code(), a.bbCourseId(), one.kind(),
                        one.day(), one.start(), one.end(), one.room()));
            }
        }
        return changes;
    }
}
```

with

```java
     * posting time are skipped, and so is one that can't be read.
     */
    public static Map<Slot, ClassChange> changesFrom(List<Posted> announcements) {
        return changesFrom(announcements, List.of());
    }

    /** Announcements and emailed changes together: the newest wins in each slot, whichever it came from. */
    public static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, List<Emailed> emailed) {
        return changesFrom(announcements, emailed, ClassChanges::readAnnouncement);
    }

    static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, Reader reader) {
        return changesFrom(announcements, List.of(), reader);
    }

    static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, List<Emailed> emailed, Reader reader) {
        List<Emailed> all = new ArrayList<>();
        for (Posted a : announcements) {
            if (a.code() == null || a.code().isEmpty() || a.postedAt() == null) {
                continue;
            }
            try {
                for (Announced one : reader.read(a.title(), a.text(), a.postedAt())) {
                    all.add(new Emailed(a.code(), a.source(), a.postedAt(), one));
                }
            } catch (RuntimeException error) { // one unreadable announcement must never break a page
                log.warn("Couldn't read an announcement for class changes", error);
            }
        }
        emailed.stream().filter(e -> e.code() != null && !e.code().isEmpty() && e.postedAt() != null).forEach(all::add);
        all.sort(Comparator.comparing(Emailed::postedAt)); // stable: at the same time, emails come after announcements

        Map<Slot, ClassChange> changes = new LinkedHashMap<>();
        for (Emailed e : all) {
            Announced one = e.change();
            String slot = one.kind().equals("makeup") ? "makeup" : "class";
            changes.put(new Slot(e.code(), one.day(), slot), new ClassChange(e.code(), e.source(), one.kind(), one.day(),
                    one.start(), one.end(), one.room()));
        }
        return changes;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/Schedule.java`: 8 changes.

Change 1: replace

```java
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
```

with

```java
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
```

Change 2: replace

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;

/**
 * Classes and exams for a day or a week, with day boundaries in Vietnam time. Classes changed by a
 * Blackboard announcement (online, cancelled, make-up) are marked here, so every page shows them the same
 * way. The Java twin of app/school/services/schedule.py.
 */
@Service
public class Schedule {
```

with

```java
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Emailed;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Source;

/**
 * Classes and exams for a day or a week, with day boundaries in Vietnam time. Classes changed by a
 * Blackboard announcement or a lecturer's email (online, cancelled, make-up) are marked here, so every page
 * shows them the same way. The Java twin of app/school/services/schedule.py.
 */
@Service
public class Schedule {
```

Change 3: replace

```java

    /**
     * A class or an exam on the timetable. Times are UTC; endAt may be empty. label: "Final exam" for exams.
     * change: online / cancelled / makeup, from a Blackboard announcement, with bbCourseId the app's page for
     * the course that announced it. allDay: a make-up class announced without a time.
     */
    public record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room,
            String label, String change, Integer bbCourseId, boolean allDay) {

        Item changed(String change, Integer bbCourseId, String room) {
            return new Item(kind, startAt, endAt, code, title, room, label, change, bbCourseId, allDay);
        }
    }
```

with

```java

    /**
     * A class or an exam on the timetable. Times are UTC; endAt may be empty. label: "Final exam" for exams.
     * change: online / cancelled / makeup, from a Blackboard announcement or a lecturer's email, with source the
     * app's page where it was announced. allDay: a make-up class announced without a time.
     */
    public record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room,
            String label, String change, Source source, boolean allDay) {

        Item changed(String change, Source source, String room) {
            return new Item(kind, startAt, endAt, code, title, room, label, change, source, allDay);
        }
    }
```

Change 4: replace

```java
    private final SchoolCourseRepository courses;
    private final SchoolBbAnnouncementRepository announcements;
    private final SchoolBbAssignmentRepository assignments;

    public Schedule(SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolCourseRepository courses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments) {
        this.meetings = meetings;
        this.exams = exams;
        this.courses = courses;
        this.announcements = announcements;
        this.assignments = assignments;
    }

    /** Classes and exams starting in [startUtc, endUtc), with announced changes, in time order. */
```

with

```java
    private final SchoolCourseRepository courses;
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
```

Change 5: replace

```java
        return assignments.findDue(userId, startUtc, endUtc);
    }

    private Map<Slot, ClassChange> announcedChanges(Integer userId) {
        List<Posted> posted = announcements.findWithCourse(userId).stream()
                .map(a -> new Posted(a.getCourse().getCourseCode(), a.getCourse().getId(), a.getTitle(), a.getText(),
                        a.getPostedAt()))
                .toList();
        return ClassChanges.changesFrom(posted);
    }

    /** Each timetable course's name and its most common class length (the first name and length seen win). */
```

with

```java
        return assignments.findDue(userId, startUtc, endUtc);
    }

    /** A Blackboard announcement's title, as compared with a Blackboard email's: case and outer spaces ignored. */
    private record Titled(String code, String title) {

        static Titled of(String code, String title) {
            return new Titled(code, title.strip().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Changes from Blackboard announcements and lecturers' emails. An email that is Blackboard's copy of an
     * announcement stored here (same course code and title) is skipped, whichever arrived first.
     */
    private Map<Slot, ClassChange> announcedChanges(Integer userId) {
        List<Posted> posted = new ArrayList<>();
        Set<Titled> titles = new HashSet<>();
        announcements.findWithCourse(userId).forEach(a -> {
            posted.add(new Posted(a.getCourse().getCourseCode(), Source.course(a.getCourse().getId()), a.getTitle(),
                    a.getText(), a.getPostedAt()));
            if (a.getCourse().getCourseCode() != null) {
                titles.add(Titled.of(a.getCourse().getCourseCode(), a.getTitle()));
            }
        });
        List<Emailed> emailed = new ArrayList<>();
        for (SchoolMailChange c : mailChanges.findOfUser(userId)) {
            String copyOf = c.getMail().getBlackboardTitle();
            if (copyOf != null && titles.contains(Titled.of(c.getCourseCode(), copyOf))) {
                continue;
            }
            emailed.add(new Emailed(c.getCourseCode(), Source.email(c.getMail().getMailKey()),
                    c.getMail().getReceivedAt(), new Announced(c.getKind(), c.getDay(), c.getStart(), c.getEnd(),
                            c.getRoom())));
        }
        return ClassChanges.changesFrom(posted, emailed);
    }

    /** Each timetable course's name and its most common class length (the first name and length seen win). */
```

Change 6: replace

```java
                classDays.add(new CourseDay(item.code(), day));
                ClassChange change = changes.get(new Slot(item.code(), day, "class"));
                if (change != null && (change.kind().equals("online") || change.kind().equals("cancelled"))) {
                    item = item.changed(change.kind(), change.bbCourseId(),
                            change.kind().equals("online") ? "Online" : item.room());
                }
            }
```

with

```java
                classDays.add(new CourseDay(item.code(), day));
                ClassChange change = changes.get(new Slot(item.code(), day, "class"));
                if (change != null && (change.kind().equals("online") || change.kind().equals("cancelled"))) {
                    item = item.changed(change.kind(), change.source(),
                            change.kind().equals("online") ? "Online" : item.room());
                }
            }
```

Change 7: replace

```java
                LocalDateTime dayStart = VietnamTime.dayStart(change.day());
                if (!dayStart.isBefore(startUtc) && dayStart.isBefore(endUtc)) {
                    result.add(new Item("class", dayStart, null, change.code(), course.name(), change.room(), null,
                            "makeup", change.bbCourseId(), true));
                }
                continue;
            }
```

with

```java
                LocalDateTime dayStart = VietnamTime.dayStart(change.day());
                if (!dayStart.isBefore(startUtc) && dayStart.isBefore(endUtc)) {
                    result.add(new Item("class", dayStart, null, change.code(), course.name(), change.room(), null,
                            "makeup", change.source(), true));
                }
                continue;
            }
```

Change 8: replace

```java
                    : startAt.plus(course.usual());
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                result.add(new Item("class", startAt, endAt, change.code(), course.name(), change.room(), null,
                        "makeup", change.bbCourseId(), false));
            }
        }
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
```

with

```java
                    : startAt.plus(course.usual());
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                result.add(new Item("class", startAt, endAt, change.code(), course.name(), change.room(), null,
                        "makeup", change.source(), false));
            }
        }
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
```

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/CalendarController.java`: one change.

Replace

```java
        if (item.change() != null) {
            props.put("change", item.change());
        }
        if (item.bbCourseId() != null) {
            event.put("url", "/school/courses/" + item.bbCourseId());
        }
        return event;
    }
```

with

```java
        if (item.change() != null) {
            props.put("change", item.change());
        }
        if (item.source() != null) {
            event.put("url", item.source().link());
        }
        return event;
    }
```

`web/src/main/resources/templates/school/fragments.html`: 2 changes.

Change 1: replace

```html
  </form>
</section>

<!--/* One class or exam (Schedule.Item). A class changed by an announcement gets a badge and a link to it. */-->
<li th:fragment="item(item)" th:with="look=${item.change == 'cancelled' ? 'cancelled' : 'changed'}"
    th:class="|item item-${item.kind}${item.change != null ? ' item-' + look : ''}|">
  <span class="item-time" th:text="${item.allDay ? 'All day' : @schoolFormat.clock(item.startAt)
```

with

```html
  </form>
</section>

<!--/* One class or exam (Schedule.Item). A class changed by an announcement or email gets a badge and a link to it. */-->
<li th:fragment="item(item)" th:with="look=${item.change == 'cancelled' ? 'cancelled' : 'changed'}"
    th:class="|item item-${item.kind}${item.change != null ? ' item-' + look : ''}|">
  <span class="item-time" th:text="${item.allDay ? 'All day' : @schoolFormat.clock(item.startAt)
```

Change 2: replace

```html
      · <span th:if="${item.room.toUpperCase().startsWith('ONLINE')}" class="badge">Online</span><th:block
          th:unless="${item.room.toUpperCase().startsWith('ONLINE')}" th:text="${item.room}">A2.508</th:block>
    </th:block>
    <th:block th:if="${item.bbCourseId != null}">
      · <a th:href="@{/school/courses/{id}(id=${item.bbCourseId})}">See announcement</a>
    </th:block>
  </span>
</li>
```

with

```html
      · <span th:if="${item.room.toUpperCase().startsWith('ONLINE')}" class="badge">Online</span><th:block
          th:unless="${item.room.toUpperCase().startsWith('ONLINE')}" th:text="${item.room}">A2.508</th:block>
    </th:block>
    <th:block th:if="${item.source != null}">
      · <a th:href="@{${item.source.link}}" th:text="${item.source.text}">See announcement</a>
    </th:block>
  </span>
</li>
```


- [ ] **Step 4: Run them to see them pass**

Run: `(cd web && ./mvnw -B test -Dtest='ClassChangesTest,CalendarFeedTest,SchoolPagesTest')` → `Tests run: 154, Failures: 0, Errors: 0`. Then `(cd web && ./mvnw -B test)` → `Tests run: 427, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/schedule web/src/main/resources/templates/school/fragments.html web/src/test/java/vn/edu/hcmiu/sla/school
git commit -m "feat(web): lecturers' emails change the timetable like announcements, and Blackboard's copies count once

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Every test, on MySQL too, and the pages in a real browser

Nothing in the repository changes in this task.

**Files:** none.

**Interfaces:**
- Consumes: Tasks 1–8.
- Produces: the go-ahead for the checkpoints.

- [ ] **Step 1: Every test**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (365 tests). Run: `(cd web && ./mvnw -B test)` → `Tests run: 427, Failures: 0, Errors: 0, Skipped: 0`.

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

Expected: `Tests run: 427, Failures: 0, Errors: 0, Skipped: 0`. When the background server has exited, `rm -rf "$SCRATCH/mysqldata"`.

- [ ] **Step 3: The pages in Edge, with made-up mail**

Start the site on a throwaway in-memory database with the test settings (it never touches MySQL), in the background:

```bash
(cd web && ./mvnw -B spring-boot:test-run -Dspring-boot.run.arguments="--server.port=8099 --server.servlet.session.cookie.secure=false")
```

Make a scratch Playwright environment once (it uses the installed Edge; nothing is downloaded but the Python package):

```bash
py -3.12 -m venv "$SCRATCH/pwenv" && "$SCRATCH/pwenv/Scripts/python.exe" -m pip install -q playwright
```

When the site says `Started SlaWebApplication`, save this as `$SCRATCH/mailbox_check.py`. It registers an account, adds a device, uploads made-up mail (results only, as the agent sends them) and a made-up timetable through the real sync API, opens Mailbox, Overview and the Timetable at 1400×1000 and 390×844, and tries Done and Move to…:

```python
"""Browser check of the Mailbox tab in Edge, on the throwaway in-memory site at :8099.

Registers an account, adds a device, uploads made-up mail (sorting results only, as the agent sends them) and a
made-up timetable through the real sync API, then opens Mailbox, Move to..., Overview and the Timetable at desktop
and phone size, and tries Done and Move to....
Usage: python mailbox_check.py <screenshot folder>
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
              dates=[day(3)], loses_points=True),
        email(9, "Talkshow B", "Hoi Sinh Vien IU", "hoisinhvien@hcmiu.edu.vn", ["event"], 6, dates=[day(1)]),
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


def cards(tab, box, part="> ul.mail-cards"):
    return tab.locator(f"#box-{box} {part} > li.mail-card .mail-subject").all_text_contents()


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page()
    page.goto(f"{BASE}/auth/register")
    page.fill("#email", f"an.mail.{int(time.time())}@example.com")
    page.fill("#displayName", "An")
    page.fill("#password", "correct-horse-8")
    page.fill("#confirm", "correct-horse-8")
    page.click("button[type=submit]")
    page.goto(f"{BASE}/school/mailbox")
    notes.append("before any upload: " + page.locator("main .card").first.inner_text().strip()[:60])
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
        tab = context.new_page()
        errors, failed = [], []
        tab.on("console", lambda m: m.type == "error" and errors.append(m.text))
        tab.on("requestfailed", lambda r: failed.append(r.url))
        for name, path in [("mailbox", "/school/mailbox"), ("overview", "/school"), ("timetable", "/school/timetable")]:
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
                notes.append(f"{box}: {cards(tab, box)}")
            notes.append(f"other: {len(cards(tab, 'other'))} shown, "
                         f"{tab.locator('#box-other summary').all_inner_texts()}")
            notes.append(f"past: tasks {cards(tab, 'tasks', 'details ul.mail-cards')}, "
                         f"events {cards(tab, 'events', 'details ul.mail-cards')}")
            notes.append("Workshop A tags: " + tab.locator("#box-events li.mail-card").first.locator(".mail-tags")
                         .inner_text().replace("\n", " "))
            money = tab.locator("#box-money > ul.mail-cards > li.mail-card").first
            money_id = money.get_attribute("id")
            money.locator("button:has-text('Done')").click()
            tab.wait_for_load_state("networkidle")
            notes.append("after Done: " + tab.locator("#box-done summary").inner_text()
                         + f"; still in Money: {tab.locator('#box-money #' + money_id).count()}")
            unsorted = tab.locator("li.mail-card", has_text="A notice the rules couldn't read")
            notes.append("unsorted note: " + unsorted.locator(".mail-unsorted").inner_text())
            unsorted.locator("a:has-text('Move to')").click()
            tab.screenshot(path=f"{OUT}/desktop-move-to.png", full_page=True)
            tab.select_option("#category1", "school_task")
            tab.click("button:has-text('Save')")
            tab.wait_for_load_state("networkidle")
            notes.append(f"moved to School tasks: {'A notice the rules couldn' + chr(39) + 't read' in cards(tab, 'tasks')}; "
                         + "flash: " + tab.locator(".flash").first.inner_text())
            tab.goto(f"{BASE}/school")
            notes.append("overview status: " + tab.locator(".system-lines").inner_text().replace("\n", " | "))
            notes.append(f"'See email' links on Overview: {tab.locator('a:has-text(' + chr(39) + 'See email' + chr(39) + ')').count()}")
        if errors or failed:
            problems.append(f"{size}: console errors {errors}, failed requests {failed}")
        context.close()
    browser.close()

print("\n".join(notes))
print("PROBLEMS:" if problems else "No problems found.", *problems, sep="\n")
```


Run: `mkdir -p "$SCRATCH/shots" && PYTHONIOENCODING=utf-8 "$SCRATCH/pwenv/Scripts/python.exe" "$SCRATCH/mailbox_check.py" "$SCRATCH/shots"`

Expected (the times differ):

```text
before any upload: Outlook isn't connected yet. On your laptop, open Outlook (c
finish: {'status': 'success'}
boxes: ['From lecturers', 'School tasks', 'Money', 'Events', 'Everything else']
lecturers: ['Online class tomorrow', 'Re: Lab 4 questions']
tasks: ['Thông báo về việc đăng ký tạm trú']
money: ['[ M-Invoice ] TB: Xuất hóa đơn điện tử số 00001', '[Thông báo] Chương trình học bổng 2026']
events: ['[THƯ MỜI] Workshop A', 'Talkshow B', '[thư mời] WORKSHOP D']
other: 10 shown, ['Show all (13)']
past: tasks ['Nhắc nhở hạn cuối khảo sát'], events ['[Thông báo] Casting C']
Workshop A tags: Event ★ Training points ⚠ lose points if absent
after Done: Done (1); still in Money: 0
unsorted note: Couldn't sort this email automatically. Use Move to…
moved to School tasks: True; flash: Moved. The app will keep this email where you put it.
overview status: EduSoft: synced at 11:20 | Outlook: synced at 11:20
'See email' links on Overview: 1
No problems found.
```

Look at `desktop-mailbox.png`, `phone-mailbox.png` and `desktop-move-to.png`: boxes in order, tags on one line where they fit, the "This is from a lecturer" tick box its normal size, nothing cut off at phone width. Then stop the site (its Java process keeps port 8099 until it is stopped: find it with `netstat -ano | grep ":8099 .*LISTENING"` and `taskkill //F //PID <pid>` after checking its command line holds `--server.port=8099`).

---

### Task 10: Checkpoint: a real sync from the student's Outlook

Nothing in the repository changes in this task. It runs the new site on the student's database (Flyway adds the four tables), sets up Outlook with the agent's own command, runs one real sync, and then **the executor stops and asks the student** to check their Mailbox and to open one email from it. Main session only.

**Files:** none.

**Interfaces:**
- Consumes: Tasks 1–9; classic Outlook on this laptop, signed in with the student's IU account (done on 2026-09-28).
- Produces: `state.json` with `outlook_account`, `term_code`, `courses`, `bb_courses` (Task 11 needs them); the student's go-ahead.

- [ ] **Step 1: Stop the running site, start the new one**

```bash
netstat -ano | grep -E ":5000 .*LISTENING"
```

If a site is running, check it is `vn.edu.hcmiu.sla.SlaWebApplication` with PowerShell `(Get-CimInstance Win32_Process -Filter "ProcessId=<pid>").CommandLine`, then `taskkill //F //PID <pid>`. Anything else on port 5000: stop and ask the student. Then, in the background (it reads the repository's `.env`, so the student's database):

```bash
(cd web && ./mvnw -B spring-boot:run)
```

Expected in its output: `Migrating schema ... to version "20260928.1.1 - school mail"`, `Tomcat started on port 5000`, `Started SlaWebApplication`.

- [ ] **Step 2: Set up Outlook with the agent's own command**

**Main session only.** It finds classic Outlook's accounts and asks which Inbox to read; the one IU account is the right one. It also adds the `sla-mail:` link type for this Windows user.

Run: `echo y | .venv/Scripts/sla-agent.exe setup --outlook`
Expected: `Read the Inbox of <the student's IU address>? [Y/n]:` then `Outlook is on: each sync reads the Inbox of …, sorts it on this laptop and uploads only the results, never the text.` If it lists more than one account, stop and ask the student which one. If it says classic Outlook isn't set up, stop and ask the student to open Outlook (classic) and sign in.

- [ ] **Step 3: One real sync**

Run: `.venv/Scripts/sla-agent.exe sync-now`
Expected: `Sync finished.` (`The last sync was less than 5 minutes ago` means wait and run it again.)

- [ ] **Step 4: What was saved (counts only, no subjects)**

Save as `$SCRATCH/mail_saved.py`:

```python
"""How many emails the site saved per category, and the newest run's parts (reads DATABASE_URL from .env)."""

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
    cursor.execute("SELECT COUNT(*) FROM school_mail_changes")
    print("class changes found in emails", cursor.fetchone()[0])
db.close()
```

Run: `.venv/Scripts/python.exe "$SCRATCH/mail_saved.py"`
Expected: `run success {… 'outlook': 'ok'}`, about 55 emails or more (as of 2026-09-28), 8 or more from lecturers, 0 unsorted, every category present, and 2 class changes (the Physics 4 and Web Application online classes of 18/9 and 22/9).

- [ ] **Step 5: The student's own check**

Stop and ask the student to:

1. Open http://localhost:5000/school/mailbox and check the boxes: From lecturers (the lecturers' replies and Blackboard announcements), School tasks (tạm trú, sinh hoạt công dân), Money (invoices, the EVN scholarship), Events (training-point events first, Past events folded away), Everything else.
2. Press **Open in Outlook** on one card. Edge asks to open `sla-agent`: allow it (tick "Always allow"). The email must open in Outlook.
3. Look at Overview: the sync status has an **Outlook** line.
4. Try **✓ Done** and **Move to…** on one card, then undo them (Undo, Back to automatic).

Continue with Task 11 only after the student says it's right. If an email is in the wrong box, note which and how it should be sorted, and discuss it with the student: a rule change is outside this plan.

---

### Task 11: Checkpoint: anonymized samples of the student's Inbox

`anonymize_mail.py` copies the student's Inbox (subject, sender, time, and only the announcements' text) with the student and every IU person replaced by made-up people whose addresses are built the same way, so the lecturer rules still apply. Each email gets the result the rules give now as `expected`; **the student checks the file and the results before it is committed** (spec §8). `test_mail_samples.py` then pins those results.

**Files:**
- Create: `agent/tools/anonymize_mail.py`, `agent/tests/fixtures/mail-samples.json` (made by the tool)
- Test: `agent/tests/test_anonymize_mail.py`, `agent/tests/test_mail_samples.py`

**Interfaces:**
- Consumes: `inbox_of`, `open_outlook`, `read_emails`, `semester_start` (Task 4); `Email`, `Context`, `sort_email` and the helpers of Task 3; `load_state` with the fields of Task 5, filled by Task 10's sync.
- Produces: `anonymize(emails, context, student_name, student_id, private=()) -> (emails, context, swaps)`; `PLACEHOLDER_TEXT`; the command `python -m agent.tools.anonymize_mail OUTPUT --name "<full name>" [--student-id ID] [--private TEXT …]`.

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
    email("4", "[THƯ MỜI] Workshop", "Gửi ititiu99001@student.hcmiu.edu.vn: cộng điểm rèn luyện. Mã 12345678.",
          "oss@hcmiu.edu.vn", "P.CTSV [OSS]"),
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

Each email's `expected` result is what the sorting rules give now; the student checks them before the file
is committed. The tool reads Outlook only; it writes nothing but the output file."""

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
            "categories": item.categories, "from_lecturer": item.from_lecturer, "loses_points": item.loses_points,
            "dates": [d.isoformat() for d in item.dates], "blackboard_title": item.blackboard_title,
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


Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_anonymize_mail.py -q` → PASS (2 tests).

- [ ] **Step 4: Make the samples from the student's Inbox**

**Main session only.** `--name` is the student's full name as their emails write it (with Vietnamese accents); ask the student if unsure. The student ID comes from what `sla-agent setup` saved, so the command never shows it.

Run: `PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -m agent.tools.anonymize_mail agent/tests/fixtures/mail-samples.json --name "<the student's full name>"`
Expected: `emails: 55 (skipped 0), names and addresses replaced: 41, still present: 0, sorted differently after anonymizing: 0` (more emails if new mail has arrived since 2026-09-28). If `still present` or `sorted differently` is not 0, stop and report.

- [ ] **Step 5: The student checks the file and the results**

Print the results for the student:

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -c "
import json
for e in json.load(open('agent/tests/fixtures/mail-samples.json', encoding='utf-8'))['emails']:
    x = e['expected']
    print(f\"{e['received_at'][:10]}  {'+'.join(x['categories']) or '(none)':30} {'L' if x['from_lecturer'] else ' '}{'!' if x['loses_points'] else ' '}  {e['subject'][:60]}\")
"
```

Stop and ask the student to (1) open `agent/tests/fixtures/mail-samples.json` and check that nothing personal is left (names, IDs, addresses, private replies), and (2) check the categories, especially the emails that were not in their 2026-09-27 paste. On 2026-09-28 those were: the Gemini check-out form (Event), "Welcome to physics 4 class" and two "TA Physics 1" replies (Class, from a lecturer), an IELTS voucher offer (Promotion), two older M-Invoice invoices (Money), the "Khảo sát Doanh nghiệp yêu thích" survey (School task + Promotion), and "THU THẬP DỮ LIỆU KIỂM KÊ KHÍ NHÀ KÍNH" (no category: Everything else). If the student wants a result changed, that is a rule change: stop and discuss it. Continue only with the student's go-ahead.

- [ ] **Step 6: Pin the checked results**

`agent/tests/test_mail_samples.py` (new):

```python
"""The sorting rules on anonymized copies of the student's real Inbox: agent/tests/fixtures/mail-samples.json,
made by agent/tools/anonymize_mail.py. Each `expected` result was checked by the student."""

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
        "categories": item.categories, "from_lecturer": item.from_lecturer, "loses_points": item.loses_points,
        "dates": [d.isoformat() for d in item.dates], "blackboard_title": item.blackboard_title,
        "class_changes": [c.model_dump(mode="json", exclude_none=True) for c in item.class_changes],
    } == sample["expected"]


def test_the_samples_cover_every_box():
    categories = {c for sample in SAMPLES["emails"] for c in sample["expected"]["categories"]}

    assert categories >= {"class", "event", "training_points", "school_task", "money", "requests_account",
                          "system_notice", "promotion"}
    assert any(sample["expected"]["class_changes"] for sample in SAMPLES["emails"])
```


Run: `.venv/Scripts/python.exe -m pytest -q` → PASS (365 + the number of sample emails + 3; 423 with 55 emails).

- [ ] **Step 7: Commit**

```bash
git add agent/tools/anonymize_mail.py agent/tests/test_anonymize_mail.py agent/tests/test_mail_samples.py agent/tests/fixtures/mail-samples.json
git commit -m "test(agent): the sorting rules on anonymized samples of the student's Inbox, checked by the student

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: The README and the spec

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: documentation only.

- [ ] **Step 1: The README, and the spec as built**

The README gets `setup --outlook` and a short Mailbox section, and a line about the shared sentences. The spec's status becomes "Built", and it records what trying it out settled: the agent never closes Outlook, where the arrival time comes from, the exact lecturer rules, the sentences' folder, and the column names.

`README.md`: one change.

Replace

```text
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Remove the saved passwords, key and schedule | `sla-agent forget` |

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

---

## Adding your module
```

with

```text
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Read your Inbox through classic Outlook | `sla-agent setup --outlook` |
| Remove the saved passwords, key, schedule and `sla-mail:` link type | `sla-agent forget` |

**Mailbox (Outlook).** IU doesn't let students approve apps that read mail, so the agent reads your Inbox through the classic Outlook app on your laptop (Windows only). Open **Outlook (classic)**, sign in with your IU account, wait for "All folders are up to date", then run `sla-agent setup --outlook`. Each sync then reads your Inbox since the start of the semester, sorts every email on your laptop, and uploads only the results (sender, subject, time, categories, dates, class changes), **never the text**. School → Mailbox shows them: **Open in Outlook** opens the email on this laptop (Edge asks once to open `sla-agent`), and **Outlook on the web** works anywhere. If Outlook shows a security warning or blocks the agent, the agent never clicks past it; Mailbox says so at the top.

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

`contract/samples/class-changes/sentences.json` lists example announcements and the class changes each must give. The agent's Python reader (lecturers' emails) and the site's Java reader (Blackboard announcements) both check every one, so the two stay the same.

---

## Adding your module
```

`docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md`: 7 changes.

Change 1: replace

```text
**Date:** 2026-09-28
**Scope:** read the student's IU Inbox through classic Outlook on the laptop, sort every email into the agreed categories, show them in a new **Mailbox** tab in priority order, and let class-change emails change the timetable like Blackboard announcements do, without counting a change twice
**Owner:** Nguyen Khang Vy
**Status:** Design, not built
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and "To submit"](2026-09-26-class-changes-and-to-submit-design.md) and [the Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---
```

with

```text
**Date:** 2026-09-28
**Scope:** read the student's IU Inbox through classic Outlook on the laptop, sort every email into the agreed categories, show them in a new **Mailbox** tab in priority order, and let class-change emails change the timetable like Blackboard announcements do, without counting a change twice
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-28-outlook-mailbox.md)
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and "To submit"](2026-09-26-class-changes-and-to-submit-design.md) and [the Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---
```

Change 2: replace

```text
- **Only the default Inbox** of the confirmed account, and only emails (Outlook item class 43), not meeting requests or delivery reports.
- **From the semester start**, taken from the EduSoft term code of the last timetable read (`YYYYS`): semester 1 → 1 August of `YYYY`; semester 2 → 1 January of `YYYY+1`; semester 3 (summer) → 1 June of `YYYY+1`. For `20261` this is **1 August 2026**. With no term code yet: the last 90 days.
- **Every sync reads all of them again.** About 50 ms per email, so 500 emails take under 30 seconds. This keeps it simple: deleted or moved emails disappear by themselves, and an improved rule re-sorts old mail at the next sync (no "re-read" button is needed).
- **Outlook is started hidden** if it isn't running. After reading, the agent closes it again only if the agent started it and no Outlook window is open.
- **Time limit:** the Outlook read runs in a child process and is stopped after **3 minutes** (for example when Outlook shows a security prompt nobody answers).
- **Read only, in code:** the Outlook reader only reads item properties and, in `open-mail`, calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`, or sets any property (including `UnRead`). A test scans the reader's source for these names.
- **Outlook's security warning is never answered by the agent.** If Outlook blocks the read, the Outlook part fails with a message (4.4).
```

with

```text
- **Only the default Inbox** of the confirmed account, and only emails (Outlook item class 43), not meeting requests or delivery reports.
- **From the semester start**, taken from the EduSoft term code of the last timetable read (`YYYYS`): semester 1 → 1 August of `YYYY`; semester 2 → 1 January of `YYYY+1`; semester 3 (summer) → 1 June of `YYYY+1`. For `20261` this is **1 August 2026**. With no term code yet: the last 90 days.
- **Every sync reads all of them again.** About 50 ms per email, so 500 emails take under 30 seconds. This keeps it simple: deleted or moved emails disappear by themselves, and an improved rule re-sorts old mail at the next sync (no "re-read" button is needed).
- **Outlook is started hidden** if it isn't running, and the reader waits up to 30 seconds for it to connect (it needs about 5). The agent never closes Outlook: a hidden Outlook closes by itself when the agent lets go of it and no Outlook window is open (checked 2026-09-28), so a window the student opened meanwhile is never closed.
- **The time an email arrived** is read from `PR_MESSAGE_DELIVERY_TIME` (UTC). pywin32 labels Outlook's `ReceivedTime` as UTC although it is the laptop's local time, so that is only the fallback, read as local time.
- **Time limit:** the Outlook read runs in a child process and is stopped after **3 minutes** (for example when Outlook shows a security prompt nobody answers).
- **Read only, in code:** the Outlook reader only reads item properties and, in `open-mail`, calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`, or sets any property (including `UnRead`). A test scans the reader's source for these names.
- **Outlook's security warning is never answered by the agent.** If Outlook blocks the read, the Outlook part fails with a message (4.4).
```

Change 3: replace

```text
2. **Timetable lecturer:** the part before `@` equals a timetable lecturer's name with dots, spaces and accents removed, in lower case (`P.Q.Hùng` → `pqhung`).
3. **An IU person:** the address is at `hcmiu.edu.vn` or one of its sub-domains, except `student.hcmiu.edu.vn`, and the part before `@` is built from the sender's own name (accents removed, lower case). It is either the initials of every word but the last followed by the last word (`Vo Minh Khoa` → `vmkhoa`), or all words joined (`Bui Thanh Nga` → `buithanhnga`). Both word orders are tried, so `Hung Quoc Pham` also gives `pqhung`. Office accounts (`oss@`, `hoisinhvien@`, `iuyouth@`, `bb@`, `noreply.cis@` …) never match because their names aren't built this way.

**Automatic Microsoft notices never count as lecturer mail:** Teams "added you to a group" emails (subject contains "Microsoft Teams" and one of "được thêm", "đã thêm", "added you") and anything from `sharepointonline.com` or `microsoft.com` addresses.

### 5.2 Categories
```

with

```text
2. **Timetable lecturer:** the part before `@` equals a timetable lecturer's name with dots, spaces and accents removed, in lower case (`P.Q.Hùng` → `pqhung`).
3. **An IU person:** the address is at `hcmiu.edu.vn` or one of its sub-domains, except `student.hcmiu.edu.vn`, and the part before `@` is built from the sender's own name (accents removed, lower case). It is either the initials of every word but the last followed by the last word (`Vo Minh Khoa` → `vmkhoa`), or all words joined (`Bui Thanh Nga` → `buithanhnga`). Both word orders are tried, so `Hung Quoc Pham` also gives `pqhung`. Office accounts (`oss@`, `hoisinhvien@`, `iuyouth@`, `bb@`, `noreply.cis@` …) never match because their names aren't built this way.

Every rule needs an IU staff address (`hcmiu.edu.vn` or a sub-domain, not `student.hcmiu.edu.vn`). Rule 3 needs a name of at least two words, so a one-word office name never counts.

**Automatic Microsoft notices never count as lecturer mail:** Teams "added you to a group" emails (subject contains "Microsoft Teams" and one of "được thêm", "đã thêm", "added you") and anything from `sharepointonline.com` or `microsoft.com` addresses.

### 5.2 Categories
```

Change 4: replace

```text

The Python class-change reader removed with the Python website (`app/school/services/class_changes.py` at commit `471cac0^`, 178 lines) comes back in the agent as `agent/sla_agent/class_changes.py`. The Java `ClassChanges` stays for Blackboard announcements.

So the two can't drift apart, a shared file `contract/samples/class-change-sentences.json` lists example announcements (title, text, posting time) with the changes they must give. Both the Python and the Java tests check every example. The examples start with those in the class-changes design's tests.

---
```

with

```text

The Python class-change reader removed with the Python website (`app/school/services/class_changes.py` at commit `471cac0^`, 178 lines) comes back in the agent as `agent/sla_agent/class_changes.py`. The Java `ClassChanges` stays for Blackboard announcements.

So the two can't drift apart, a shared file `contract/samples/class-changes/sentences.json` lists example announcements (title, text, posting time) with the changes they must give, each distinct change once. Both the Python and the Java tests check every example. The examples start with those in the class-changes design's tests. The file is in a sub-folder because every `*.json` directly in `contract/samples/` is read as an upload.

---
```

Change 5: replace

```text

One Flyway migration, `V<build date>_1_<n>__school_mail.sql` (School module = 1):

- **`school_mail`**: `id`, `user_id`, `mail_key` CHAR(64), `entry_id` VARCHAR(512), `thread_id` VARCHAR(64) NULL, `received_at` DATETIME (UTC), `sender_name` VARCHAR(255), `sender_address` VARCHAR(255), `subject` VARCHAR(500), `categories` VARCHAR(100) (comma-separated), `from_lecturer`, `dates` VARCHAR(400) (comma-separated ISO dates), `loses_points`, `sorted`, `blackboard_title` VARCHAR(255) NULL. Unique (`user_id`, `mail_key`).
- **`school_mail_changes`**: `id`, `mail_id` (deleted with its email), `course_code`, `kind`, `day`, `start_time` NULL, `end_time` NULL, `room` NULL.
- **`school_mail_choices`**: `id`, `user_id`, `mail_key`, `done`, `categories` VARCHAR(100) NULL, `from_lecturer` NULL, `updated_at`. Unique (`user_id`, `mail_key`). The student's Done and Move to… choices.
- **`school_mail_status`**: `user_id` (key), `since`, `connected`, `synced_at` (UTC).
```

with

```text

One Flyway migration, `V<build date>_1_<n>__school_mail.sql` (School module = 1):

- **`school_mail`**: `id`, `user_id`, `mail_key` VARCHAR(64), `entry_id` VARCHAR(512), `thread_id` VARCHAR(64) NULL, `received_at` DATETIME (UTC), `sender_name` VARCHAR(255), `sender_address` VARCHAR(255), `subject` VARCHAR(500), `categories` VARCHAR(100) (comma-separated), `from_lecturer`, `dates` VARCHAR(400) (comma-separated ISO dates), `loses_points`, `is_sorted`, `blackboard_title` VARCHAR(255) NULL. Unique (`user_id`, `mail_key`).
- **`school_mail_changes`**: `id`, `mail_id` (deleted with its email), `course_code`, `kind`, `change_day`, `start_time` NULL, `end_time` NULL, `room` NULL.
- **`school_mail_choices`**: `id`, `user_id`, `mail_key`, `done`, `categories` VARCHAR(100) NULL, `from_lecturer` NULL, `updated_at`. Unique (`user_id`, `mail_key`). The student's Done and Move to… choices.
- **`school_mail_status`**: `user_id` (key), `since`, `connected`, `synced_at` (UTC).
```

Change 6: replace

```text
- **Categories and order:** subject-only words (the beFood prize), the top-two order, no match.
- **Dates:** each format, dates in links, year guessing, dates before arrival, the 30 limit; `loses_points`.
- **Class changes:** the four course rules; not from a lecturer → none.
- **Shared sentences:** every example in `class-change-sentences.json`.

**Agent: Outlook (with a fake Outlook, no real Outlook needed):**
```

with

```text
- **Categories and order:** subject-only words (the beFood prize), the top-two order, no match.
- **Dates:** each format, dates in links, year guessing, dates before arrival, the 30 limit; `loses_points`.
- **Class changes:** the four course rules; not from a lecturer → none.
- **Shared sentences:** every example in `class-changes/sentences.json`.

**Agent: Outlook (with a fake Outlook, no real Outlook needed):**
```

Change 7: replace

```text
## 9. Build order

1. Upload format: the `outlook` part and error codes (Python and Java), samples.
2. Agent: the Python date reader restored, with `class-change-sentences.json` checked by both test suites.
3. Agent: `anonymize_mail.py` and the sample file (the student checks it).
4. Agent: sorting rules (5.1–5.5).
5. Agent: the Outlook reader, `setup --outlook`, the link type, `open-mail`, `forget`, sync and problems.
```

with

```text
## 9. Build order

1. Upload format: the `outlook` part and error codes (Python and Java), samples.
2. Agent: the Python date reader restored, with `class-changes/sentences.json` checked by both test suites.
3. Agent: `anonymize_mail.py` and the sample file (the student checks it).
4. Agent: sorting rules (5.1–5.5).
5. Agent: the Outlook reader, `setup --outlook`, the link type, `open-mail`, `forget`, sync and problems.
```


- [ ] **Step 2: Commit**

```bash
git add README.md docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md
git commit -m "docs: Mailbox and sla-agent setup --outlook in the README; the Outlook spec as built

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
