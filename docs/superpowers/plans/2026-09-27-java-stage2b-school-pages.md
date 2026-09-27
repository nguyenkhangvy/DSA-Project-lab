# Java Stage 2b: School Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Java website shows the School module's pages with the same content and look as the Python website: Overview (status card, Today/Tomorrow with class changes, To submit, Latest announcements, Next exam, What changed), Timetable with its calendar feed, Courses and a course page, Exams, Tuition, Devices, the school menu, and "Sync now".

**Architecture:** Three pure parts are ported first, each with its Java test twin: Vietnam-time helpers with the sync status texts, then the Blackboard announcement reader (online, cancelled and make-up classes). Next come the database-backed parts: the schedule service (a day's classes and exams, with announced changes) and the calendar JSON feed; the page controller with Thymeleaf templates; and the Devices page. The pages read "now" from a `Clock` bean, so tests can set the time. No new tables.

**Tech Stack:** Java 17, Spring Boot 4.1.1 (Spring MVC, Thymeleaf with the Spring Security dialect, Spring Data JPA / Hibernate 7, Bean Validation), `java.util.regex` in Unicode mode, JUnit 5 + MockMvc, H2 for tests; FullCalendar 6.1.21 from jsDelivr (unchanged), Playwright + Edge for the browser check.

**Spec:** `docs/superpowers/specs/2026-09-26-java-website-design.md` — §5.3 (status, devices, schedule and calendar feed, class changes, pages), §5.4, §6, §8, §11.

**Tried first:** every file below was built and run in a scratch copy of `web/` before this plan was written. 302 Java tests passed on H2 and on a real MySQL 8.4 server. A browser check in Edge covered every new page at 1400×1000 and 390×844, in light and dark mode, on made-up data uploaded through the real sync API: no console errors, no failed requests, no page wider than the screen, a light background with black text.

## Global Constraints

- Java 17 language level; no new dependencies; `web/pom.xml` unchanged.
- No new tables and no Flyway migration. The Python site stays in daily use and changes no tables (spec §6).
- Same content, wording and look as the Python pages (spec §5.3). `static/css/style.css` is already the same file. `static/js/timetable.js` is copied unchanged. FullCalendar 6.1.21 is loaded from jsDelivr with the same SRI hash.
- The site is always light: white and light-grey backgrounds, black text, no dark mode.
- Times are stored in UTC without an offset and shown in Vietnam time (UTC+7). A Vietnam day runs from 17:00 UTC the day before to 17:00 UTC that day.
- The announcement reader's patterns use Unicode mode (`Pattern.UNICODE_CHARACTER_CLASS`, plus `CASE_INSENSITIVE | UNICODE_CASE` where Python used `re.IGNORECASE`), and text is normalised to NFC first (spec §5.3).
- Every query is filtered by the logged-in user; another user's course or device is 404.
- `spring.jpa.open-in-view=false`: a template may only read what its query loaded. Any course a template shows next to an announcement or assignment is fetched with it (`join fetch`).
- Every form posts with its CSRF token (Thymeleaf adds it to `th:action` forms).
- Tests never touch the real database: H2 by default; GitHub also runs them on a throwaway MySQL.
- Imports grouped as in stage 1: static; `java`; `jakarta`; `org`; `tools`; `vn`, with one blank line between groups.
- Commands below are for Git Bash, run from the repository root.

## Review Focus

The failure modes most likely to bite that the spec implies but a quick read of the tests might miss, and the test that pins each:

1. **A 7-hour slip at the day boundary.** A class at 06:00 on a Monday in Vietnam is still Sunday in UTC, and a deadline at 00:30 belongs to the next Vietnam day. Pinned by `VietnamTimeTest` (Task 1) and by `CalendarFeedTest.aClassEarlyOnMondayInVietnamBelongsToThatMonday` and `aDeadlineJustAfterMidnightBelongsToTheNextVietnamDay` (Task 3).
2. **Vietnamese announcements.** Word boundaries next to letters with diacritics, "học bù", "nghỉ", "hủy", and text typed with separate accent marks (NFD). Java's patterns match these only in Unicode mode. Pinned by `ClassChangesTest` (Task 2); taking Unicode mode away makes 5 of its cases fail (checked while writing this plan).
3. **Another user's data.** Courses, announcements, changes, classes, the calendar and devices must never show another user's rows. Pinned by `CalendarFeedTest.theCalendarFeedHasOnlyMyClasses` and `anotherUsersAnnouncementsNeverChangeMyClasses` (Task 3), `SchoolPagesTest.someoneElsesCoursePageIs404`, `theCoursesPageListsOnlyMyCourses`, `schoolHomeDoesNotShowOtherUsersChanges` (Task 4), and `DevicesPageTest.nobodyCanChangeAnotherUsersDevice` and `theDevicesPageListsOnlyMyDevices` (Task 5).
4. **A template reaching into data that wasn't loaded.** With open-in-view off, `${a.course.name}` fails only when there is a row to show. Pinned by the page tests that have data: `SchoolPagesTest.toSubmitListsWhatIHaventSubmittedWithALink` and `overviewShowsThe3LatestAnnouncements` (Task 4).
5. **MySQL isn't H2.** MySQL gives the keys of the run's JSON column back in its own order (shortest first), which would reorder "Couldn't read: …". Pinned by `SyncStatusTest.partsAreNamedInTheSameOrderWhateverOrderTheDatabaseKeptThemIn` (Task 1); Task 6 runs the whole suite on MySQL.

## Decisions made while trying it out

- **`school/VietnamTime`** holds the Vietnam-time helpers every School part uses (day boundaries, "Tue 29/09 08:00", the calendar's wall-clock times). Stage 2a's `Changes` now uses it instead of its own copy. Its texts are unchanged; `ChangesTest` checks them.
- **The status card reads a run's parts in the fixed order timetable, exams, tuition, blackboard**, not in the order the JSON column returns them. MySQL re-sorts those keys, which would change "Couldn't read: …".
- **Pages take "now" from a `Clock` bean** (`core/ClockConfig`, UTC); page tests set it through `TestClock`. The sync API from stage 2a keeps the system clock.
- **Templates call a `schoolFormat` bean** (`${@schoolFormat.when(...)}`) for times, money, scores and grades, like the Python site's template filters. The grade wording is shared with the "What changed" lines (`Changes.grade`).
- **The course list's counts come from one query** (`CourseCard`), so the page never loads every announcement to count them.
- **Loops that render a fragment put `th:each` on a `th:block`**: Thymeleaf runs `th:replace` before `th:each` on the same element.
- **Page tests read HTML with small helpers** (regular expressions), not a new HTML library.
- **Every page sends `Cache-Control: no-cache, no-store…`** (Spring Security's default), and the page showing a new device key sends exactly `no-store`, as the Python site does.
- **A known look quirk is left as it is:** in the week view, a long all-day deadline title can run on in white text into the next days' columns; it is visible only on today's shaded column. The Python site does the same (same CSS and script); fixing it is a separate change for both.

## File Structure

```
web/src/main/java/vn/edu/hcmiu/sla/
  core/ClockConfig.java                     the site's clock, UTC (Task 4)
  school/VietnamTime.java                   UTC <-> Vietnam time, day boundaries, formats (Task 1)
  school/SchoolModule.java                  School in the menu and on the dashboard (Task 4)
  school/sync/Changes.java                  uses VietnamTime (Task 1); number and grade public (Task 4)
  school/sync/SyncRuns.java                 + requestSync, for "Sync now" (Task 4)
  school/sync/DeviceKeys.java               + active, own, rename, revoke (Task 5)
  school/model/*Repository.java             + calendar queries (Task 3), page queries (Task 4, 5)
  school/model/CourseCard.java              a course with its counts (Task 4)
  school/pages/SyncStatus.java              the status card's texts (Task 1)
  school/schedule/ClassChanges.java         reads online/cancelled/make-up classes from announcements (Task 2)
  school/schedule/Schedule.java             a day's classes and exams with announced changes (Task 3)
  school/schedule/CalendarController.java   /school/api/calendar (Task 3)
  school/pages/SchoolFormat.java            template helpers (Task 4)
  school/pages/SchoolController.java        Overview, Timetable, Courses, a course, Exams, Tuition, Sync now (Task 4)
  school/pages/DeviceForm.java, DevicesController.java    Devices page (Task 5)
web/src/main/resources/
  templates/school/fragments.html           school menu, status card, one class or exam (Task 4)
  templates/school/index.html, timetable.html, courses.html, course.html, exams.html, tuition.html (Task 4)
  templates/school/devices.html             (Task 5)
  static/js/timetable.js                    copied from app/static/js/ (Task 4)
web/src/test/java/vn/edu/hcmiu/sla/school/
  VietnamTimeTest.java, pages/SyncStatusTest.java           (Task 1)
  schedule/ClassChangesTest.java                            (Task 2)
  SchoolTestData.java, schedule/CalendarFeedTest.java       (Task 3)
  TestClock.java, pages/SchoolPagesTest.java                (Task 4)
  pages/DevicesPageTest.java                                (Task 5)
README.md, docs/superpowers/specs/2026-09-26-java-website-design.md   (Task 6)
```

Test counts: stage 2a left 167 Java tests. After each task: Task 1 → 191, Task 2 → 241, Task 3 → 264, Task 4 → 292, Task 5 → 302.

---

### Task 1: Vietnam time and the sync status texts

`VietnamTime` gathers the time helpers every School part needs. `SyncStatus` ports `app/school/services/sync_status.py` (a pure function) with the same wording. `SyncStatusTest` is the Java twin of `tests/test_school_sync_status.py`, plus one case for Review Focus 5. Then stage 2a's `Changes` switches to `VietnamTime`; `ChangesTest` shows its texts are unchanged.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/VietnamTime.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java` (replaced)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/VietnamTimeTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/pages/SyncStatusTest.java`

**Interfaces:**
- Consumes: `SchoolSyncRun` (status constants and getters) and `Scheduling.RUN_TIMEOUT` (stage 2a).
- Produces:
  - `VietnamTime` (static): `VIETNAM`; `OffsetDateTime of(LocalDateTime utc)`; `LocalDate date(LocalDateTime utc)` (the Vietnam day); `LocalDateTime dayStart(LocalDate day)` (midnight in Vietnam, as UTC); `LocalDateTime utc(LocalDate day, LocalTime clock)`; `String when(LocalDateTime utc)` ("Tue 29/09 08:00"); `String clock(LocalDateTime utc)` ("08:00"); `String fullDate(LocalDate day)` ("29/09/2026"); `String dayLabel(LocalDate day)` ("Tue 29/09"); `String wallClock(LocalDateTime utc)` ("2026-09-29T08:00:00").
  - `SyncStatus` (static): `record RunInfo(String status, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode, String errorMessage, Map<String, Map<String, String>> sections)` with `static RunInfo of(SchoolSyncRun run)`; `record Status(String state, String headline, String detail, LocalDateTime lastSyncedAt, String laptopWarning)`; `record SystemLine(String name, String state, String text)`; `Status describe(LocalDateTime now, int intervalHours, LocalDateTime syncRequestedAt, RunInfo latest, LocalDateTime lastGoodFinishedAt, boolean hasDevice, LocalDateTime lastSeenAt)`; `List<SystemLine> systemLines(List<RunInfo> runs, LocalDateTime now)` (runs newest first).

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/VietnamTimeTest.java`:

```java
package vn.edu.hcmiu.sla.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Stored times are UTC; a Vietnam day runs from 17:00 UTC the day before to 17:00 UTC that day. */
class VietnamTimeTest {

    @Test
    void aVietnamDayStartsAt1700UtcTheDayBefore() {
        assertThat(VietnamTime.dayStart(LocalDate.of(2026, 10, 5))).isEqualTo(LocalDateTime.of(2026, 10, 4, 17, 0));
        assertThat(VietnamTime.date(LocalDateTime.of(2026, 10, 4, 16, 59))).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(VietnamTime.date(LocalDateTime.of(2026, 10, 4, 17, 0))).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void aVietnamClockTimeBecomesUtc() {
        assertThat(VietnamTime.utc(LocalDate.of(2026, 9, 29), LocalTime.of(8, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 29, 1, 0));
    }

    @Test
    void timesAreWrittenInVietnamTime() {
        LocalDateTime utc = LocalDateTime.of(2026, 10, 2, 16, 59); // Fri 02/10 23:59 in Vietnam

        assertThat(VietnamTime.when(utc)).isEqualTo("Fri 02/10 23:59");
        assertThat(VietnamTime.clock(utc)).isEqualTo("23:59");
        assertThat(VietnamTime.wallClock(utc)).isEqualTo("2026-10-02T23:59:00");
        assertThat(VietnamTime.fullDate(LocalDate.of(2026, 10, 15))).isEqualTo("15/10/2026");
        assertThat(VietnamTime.dayLabel(LocalDate.of(2026, 9, 29))).isEqualTo("Tue 29/09");
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/SyncStatusTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.Status;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.SystemLine;

/** Java twin of tests/test_school_sync_status.py. */
class SyncStatusTest {

    // UTC. 07:05 UTC = 14:05 in Vietnam.
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 7, 30);

    static LocalDateTime at(String hhmm) {
        return hhmm == null ? null : LocalDateTime.of(NOW.toLocalDate(), LocalTime.parse(hhmm));
    }

    static RunInfo run(String status, String started, String finished, String errorCode,
            Map<String, Map<String, String>> sections) {
        return new RunInfo(status, at(started), at(finished), errorCode, null, sections);
    }

    static RunInfo run(String status) {
        return run(status, "07:00", "07:05", null, null);
    }

    static Status status(RunInfo latest, LocalDateTime requested, boolean hasDevice, LocalDateTime lastSeen,
            LocalDateTime lastGood) {
        return SyncStatus.describe(NOW, 12, requested, latest, lastGood, hasDevice, lastSeen);
    }

    static Status status(RunInfo latest) {
        return status(latest, null, true, NOW, null);
    }

    static final Map<String, String> OK = Map.of("status", "ok");

    static Map<String, String> bad(String code) {
        return Map.of("status", "failed", "error_code", code, "error_message", "x");
    }

    /** Parts in the order given, as the agent sends them. */
    @SafeVarargs
    static Map<String, Map<String, String>> parts(Map.Entry<String, Map<String, String>>... entries) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : entries) {
            sections.put(entry.getKey(), entry.getValue());
        }
        return sections;
    }

    static Map<String, Map<String, String>> edu() {
        return parts(Map.entry("timetable", OK), Map.entry("exams", OK), Map.entry("tuition", OK));
    }

    @Test
    void noDeviceYetPointsToTheDevicesPage() {
        Status result = status(null, null, false, null, null);

        assertThat(result.state()).isEqualTo("no_device");
        assertThat(result.detail()).contains("Devices page");
        assertThat(result.laptopWarning()).isNull();
    }

    @Test
    void neverSynced() {
        assertThat(status(null).state()).isEqualTo("never");
    }

    @Test
    void aSuccessfulSyncShowsVietnamTime() {
        Status result = status(run("success"), null, true, NOW, LocalDateTime.of(2026, 10, 1, 7, 5));

        assertThat(List.of(result.state(), result.headline())).containsExactly("success", "Synced at 14:05");
        assertThat(result.lastSyncedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 7, 5));
    }

    @Test
    void aSyncFromAnEarlierDayShowsTheDate() {
        RunInfo earlier = new RunInfo("success", LocalDateTime.of(2026, 9, 29, 7, 0), LocalDateTime.of(2026, 9, 29, 7, 5),
                null, null, null);

        assertThat(status(earlier).headline()).isEqualTo("Synced on 29/09 14:05");
    }

    @Test
    void running() {
        assertThat(status(run("running", "07:25", null, null, null)).state()).isEqualTo("syncing");
    }

    @Test
    void aRunStuckFor20MinutesShowsAsFailed() {
        assertThat(status(run("running", "07:10", null, null, null)).state()).isEqualTo("failed");
    }

    @Test
    void syncNowWaitingForTheLaptop() {
        assertThat(status(run("success"), at("07:20"), true, NOW, null).state()).isEqualTo("requested");
    }

    @Test
    void aRequestAlreadyServedIsNotShown() {
        assertThat(status(run("success"), at("06:00"), true, NOW, null).state()).isEqualTo("success");
    }

    @Test
    void wrongPasswordPausesAndSaysHowToFixIt() {
        Status result = status(run("failed", "07:00", "07:05", "bad_credentials", null));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.detail()).contains("sla-agent setup");
    }

    @Test
    void extraVerificationPausesAndSuggestsImport() {
        Status result = status(run("failed", "07:00", "07:05", "extra_verification", null));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.detail()).contains("sla-agent import");
    }

    @Test
    void aNetworkFailureWillRetry() {
        Status result = status(run("failed", "07:00", "07:05", "network", null));

        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.detail()).contains("automatically");
    }

    @Test
    void allPartsFailedUsesTheirError() {
        Status result = status(run("failed", "07:00", "07:05", null,
                parts(Map.entry("timetable", bad("edusoft_changed")), Map.entry("tuition", bad("edusoft_changed")))));

        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.headline()).contains("changed");
    }

    @Test
    void partialNamesThePartsThatFailed() {
        Status result = status(run("partial", "07:00", "07:05", null,
                parts(Map.entry("timetable", OK), Map.entry("tuition", bad("edusoft_changed")))),
                null, true, NOW, LocalDateTime.of(2026, 10, 1, 7, 5));

        assertThat(List.of(result.state(), result.headline())).containsExactly("partial", "Partly synced at 14:05");
        assertThat(result.detail()).contains("tuition").doesNotContain("timetable");
    }

    @Test
    void partsAreNamedInTheSameOrderWhateverOrderTheDatabaseKeptThemIn() {
        // MySQL gives JSON keys back shortest first: exams, tuition, timetable.
        Status result = status(run("partial", "07:00", "07:05", null, parts(Map.entry("exams", bad("edusoft_changed")),
                Map.entry("tuition", OK), Map.entry("timetable", bad("edusoft_changed")))));

        assertThat(result.detail()).startsWith("Couldn't read: timetable, exam schedule.");
    }

    @Test
    void laptopThatNeverCheckedIn() {
        assertThat(status(null, null, true, null, null).laptopWarning()).contains("hasn't checked in yet");
    }

    @Test
    void laptopSilentForMoreThanTwiceTheInterval() {
        assertThat(status(null, null, true, LocalDateTime.of(2026, 9, 30, 7, 0), null).laptopWarning())
                .isEqualTo("Your laptop hasn't checked in since Wed 30/09 14:00.");
    }

    @Test
    void laptopSeenRecentlyGivesNoWarning() {
        assertThat(status(null, null, true, LocalDateTime.of(2026, 9, 30, 20, 0), null).laptopWarning()).isNull();
    }

    // ---- One line per system ------------------------------------------------------

    @Test
    void oneLinePerSystemFromTheLatestRunThatIncludedIt() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("blackboard", bad("bad_credentials"));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"),
                        tuple("Blackboard", "paused"));
        assertThat(lines.get(0).text()).isEqualTo("synced at 14:05");
        assertThat(lines.get(1).text()).contains("sla-agent setup --blackboard");
    }

    @Test
    void aSystemNeverSyncedHasNoLine() {
        assertThat(SyncStatus.systemLines(List.of(run("success", "07:00", "07:05", null, edu())), NOW))
                .extracting(SystemLine::name).containsExactly("EduSoft");
    }

    @Test
    void aPartFailureIsShownAsPartlySynced() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("tuition", bad("edusoft_changed"));

        assertThat(SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW))
                .containsExactly(new SystemLine("EduSoft", "partial", "synced at 14:05, but couldn't read: tuition"));
    }

    @Test
    void blackboardFailureHeadlineNamesBlackboard() {
        Status result = status(run("failed", "07:00", "07:05", null, parts(Map.entry("blackboard", bad("bad_credentials")))));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.headline()).contains("Blackboard");
        assertThat(result.detail()).contains("sla-agent setup --blackboard");
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `(cd web && ./mvnw -B -q test -Dtest='VietnamTimeTest,SyncStatusTest')`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `VietnamTime` and `SyncStatus`.

- [ ] **Step 3: Write the helpers and the status texts**

`web/src/main/java/vn/edu/hcmiu/sla/school/VietnamTime.java`:

```java
package vn.edu.hcmiu.sla.school;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The database keeps times in UTC without an offset (as the Python site does); pages show Vietnam time,
 * UTC+7 all year. A Vietnam day runs from 17:00 UTC the day before to 17:00 UTC that day.
 */
public final class VietnamTime {

    private VietnamTime() {
    }

    public static final ZoneOffset VIETNAM = ZoneOffset.ofHours(7);

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.ENGLISH);
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** UTC as stored -> the same moment in Vietnam. */
    public static OffsetDateTime of(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(VIETNAM);
    }

    /** The day it is in Vietnam at this UTC moment. */
    public static LocalDate date(LocalDateTime utc) {
        return of(utc).toLocalDate();
    }

    /** Midnight in Vietnam on this day, as UTC. */
    public static LocalDateTime dayStart(LocalDate day) {
        return utc(day, LocalTime.MIDNIGHT);
    }

    /** A Vietnam wall-clock time on this day, as UTC. */
    public static LocalDateTime utc(LocalDate day, LocalTime clock) {
        return day.atTime(clock).atOffset(VIETNAM).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** "Tue 29/09 08:00". */
    public static String when(LocalDateTime utc) {
        return WHEN.format(of(utc));
    }

    /** "08:00". */
    public static String clock(LocalDateTime utc) {
        return CLOCK.format(of(utc));
    }

    /** "29/09/2026". */
    public static String fullDate(LocalDate day) {
        return FULL_DATE.format(day);
    }

    /** "Tue 29/09". */
    public static String dayLabel(LocalDate day) {
        return DAY_LABEL.format(day);
    }

    /** Vietnam wall-clock time without an offset, "2026-09-29T08:00:00", as the calendar wants it. */
    public static String wallClock(LocalDateTime utc) {
        return WALL_CLOCK.format(of(utc));
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.Scheduling;

/**
 * Turns the sync history into the status the user sees. Pure functions; the Java twin of
 * app/school/services/sync_status.py, with the same wording. Times are UTC, shown in Vietnam time.
 *
 * <p>A run's parts are always read in the order timetable, exams, tuition, blackboard: MySQL keeps the
 * keys of the JSON column in its own order, so the order they come back in means nothing.
 */
public final class SyncStatus {

    private SyncStatus() {
    }

    static final String RETRY = "It will be tried again automatically.";
    static final List<String> PART_ORDER = List.of("timetable", "exams", "tuition", "blackboard");

    /** What a status says: state, headline, and what to do. */
    record Problem(String state, String headline, String detail) {
    }

    static final Map<String, Problem> PROBLEMS = Map.of(
            "bad_credentials", new Problem("paused", "Paused: EduSoft rejected your student ID or password",
                    "Run `sla-agent setup` on your laptop to enter them again."),
            "extra_verification", new Problem("paused", "Paused: EduSoft asked for extra verification",
                    "Automatic sync can't pass a CAPTCHA or code. Save the pages from your browser "
                            + "and use `sla-agent import`."),
            "network", new Problem("failed", "Sync failed: EduSoft couldn't be reached", RETRY),
            "session_expired", new Problem("failed", "Sync failed: EduSoft ended the session", RETRY),
            "edusoft_changed", new Problem("failed", "Sync failed: EduSoft's pages have changed",
                    "sla-agent needs an update to read the new pages."),
            "timeout", new Problem("failed", "The last sync didn't finish", RETRY));
    static final Problem UNKNOWN = new Problem("failed", "Sync failed", RETRY);

    static final Map<String, Problem> BLACKBOARD_PROBLEMS = Map.of(
            "bad_credentials", new Problem("paused", "Paused: Blackboard rejected your username or password",
                    "Run `sla-agent setup --blackboard` on your laptop to enter them again."),
            "extra_verification", new Problem("paused", "Paused: Blackboard asked for extra verification",
                    "Automatic Blackboard sync can't pass a CAPTCHA, code or Microsoft sign-in."),
            "network", new Problem("failed", "Sync failed: Blackboard couldn't be reached", RETRY),
            "session_expired", new Problem("failed", "Sync failed: Blackboard ended the session", RETRY),
            "source_changed", new Problem("failed", "Sync failed: Blackboard's data format has changed",
                    "sla-agent needs an update to read it."));

    static final Map<String, String> PART_NAMES = Map.of(
            "timetable", "timetable", "exams", "exam schedule", "tuition", "tuition", "blackboard", "Blackboard");

    /** A system and the parts of a sync that come from it. */
    record SystemParts(String name, List<String> parts) {
    }

    static final List<SystemParts> SYSTEMS = List.of(
            new SystemParts("EduSoft", List.of("timetable", "exams", "tuition")),
            new SystemParts("Blackboard", List.of("blackboard")));

    static final Map<String, String> PAUSE_HINTS = Map.of(
            "EduSoft/bad_credentials", "paused: wrong student ID or password. Run `sla-agent setup`.",
            "Blackboard/bad_credentials", "paused: wrong username or password. Run `sla-agent setup --blackboard`.",
            "EduSoft/extra_verification", "paused: asked for extra verification (CAPTCHA or code).",
            "Blackboard/extra_verification",
            "paused: asked for extra verification (CAPTCHA, code or Microsoft sign-in).");

    static final Map<String, String> FAILURE_HINTS = Map.of(
            "network", "couldn't be reached; it will be tried again automatically.",
            "session_expired", "ended the session; it will be tried again automatically.",
            "edusoft_changed", "its pages changed; sla-agent needs an update.",
            "source_changed", "its data format changed; sla-agent needs an update.");

    /** One sync run, as the status needs it. */
    public record RunInfo(String status, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode,
            String errorMessage, Map<String, Map<String, String>> sections) {

        public static RunInfo of(SchoolSyncRun run) {
            return new RunInfo(run.getStatus(), run.getStartedAt(), run.getFinishedAt(), run.getErrorCode(),
                    run.getErrorMessage(), run.getSections());
        }

        Map<String, Map<String, String>> parts() {
            return sections == null ? Map.of() : sections;
        }
    }

    /** One line per system on the status card. state: ok / partial / failed / paused. */
    public record SystemLine(String name, String state, String text) {
    }

    /** state: no_device / never / requested / syncing / success / partial / failed / paused. */
    public record Status(String state, String headline, String detail, LocalDateTime lastSyncedAt,
            String laptopWarning) {
    }

    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /** "at 14:05" today (in Vietnam), else "on 29/09 14:05". */
    static String at(LocalDateTime moment, LocalDateTime now) {
        if (VietnamTime.date(moment).equals(VietnamTime.date(now))) {
            return "at " + VietnamTime.clock(moment);
        }
        return "on " + DAY_CLOCK.format(VietnamTime.of(moment));
    }

    private static List<String> failedParts(Map<String, Map<String, String>> sections, List<String> parts) {
        List<String> failed = new ArrayList<>();
        for (String part : parts) {
            Map<String, String> result = sections.get(part);
            if (result != null && "failed".equals(result.get("status"))) {
                failed.add(part);
            }
        }
        return failed;
    }

    private static String partNames(List<String> parts) {
        return String.join(", ", parts.stream().map(p -> PART_NAMES.getOrDefault(p, p)).toList());
    }

    /** One line per system, from the newest finished run that included it (runs newest first). */
    public static List<SystemLine> systemLines(List<RunInfo> runs, LocalDateTime now) {
        List<SystemLine> lines = new ArrayList<>();
        for (SystemParts system : SYSTEMS) {
            RunInfo run = runs.stream()
                    .filter(r -> !r.status().equals(SchoolSyncRun.RUNNING)
                            && system.parts().stream().anyMatch(r.parts()::containsKey))
                    .findFirst().orElse(null);
            if (run == null) {
                continue;
            }
            List<String> included = system.parts().stream().filter(run.parts()::containsKey).toList();
            List<String> failed = failedParts(run.parts(), included);
            if (failed.isEmpty()) {
                lines.add(new SystemLine(system.name(), "ok", "synced " + at(run.finishedAt(), now)));
                continue;
            }
            String code = run.parts().get(failed.get(0)).get("error_code");
            String pause = PAUSE_HINTS.get(system.name() + "/" + code);
            if (pause != null) {
                lines.add(new SystemLine(system.name(), "paused", pause));
            } else if (failed.size() < included.size()) {
                lines.add(new SystemLine(system.name(), "partial",
                        "synced " + at(run.finishedAt(), now) + ", but couldn't read: " + partNames(failed)));
            } else {
                lines.add(new SystemLine(system.name(), "failed",
                        FAILURE_HINTS.getOrDefault(code, "sync failed; it will be tried again automatically.")));
            }
        }
        return lines;
    }

    static String laptopWarning(LocalDateTime now, int intervalHours, LocalDateTime lastSeenAt) {
        if (lastSeenAt == null) {
            return "Your laptop hasn't checked in yet. Run `sla-agent setup` on it.";
        }
        if (Duration.between(lastSeenAt, now).compareTo(Duration.ofHours(2L * intervalHours)) > 0) {
            return "Your laptop hasn't checked in since " + VietnamTime.when(lastSeenAt) + ".";
        }
        return null;
    }

    public static Status describe(LocalDateTime now, int intervalHours, LocalDateTime syncRequestedAt, RunInfo latest,
            LocalDateTime lastGoodFinishedAt, boolean hasDevice, LocalDateTime lastSeenAt) {
        if (!hasDevice) {
            return new Status("no_device", "Not set up yet",
                    "Add your laptop on the Devices page, then run `sla-agent setup` on it.", null, null);
        }
        String warning = laptopWarning(now, intervalHours, lastSeenAt);

        boolean running = latest != null && latest.status().equals(SchoolSyncRun.RUNNING);
        if (running && Duration.between(latest.startedAt(), now).compareTo(Scheduling.RUN_TIMEOUT) < 0) {
            return new Status("syncing", "Syncing…", null, lastGoodFinishedAt, warning);
        }
        if (syncRequestedAt != null && (latest == null || syncRequestedAt.isAfter(latest.startedAt()))) {
            return new Status("requested", "Sync requested, waiting for your laptop",
                    "Your laptop checks in every 15 minutes while it's on.", lastGoodFinishedAt, warning);
        }
        if (latest == null) {
            return new Status("never", "Never synced", "Your laptop will sync at its next check-in.",
                    lastGoodFinishedAt, warning);
        }

        Problem problem = null;
        if (running) {
            problem = PROBLEMS.get("timeout");
        } else if (latest.status().equals(SchoolSyncRun.FAILED)) {
            String code = latest.errorCode();
            Map<String, Problem> problems = PROBLEMS;
            if (code == null) {
                List<String> failed = failedParts(latest.parts(), PART_ORDER);
                code = failed.isEmpty() ? null : latest.parts().get(failed.get(0)).get("error_code");
                if (!failed.isEmpty() && failed.get(0).equals("blackboard")) {
                    problems = BLACKBOARD_PROBLEMS;
                }
            }
            problem = code == null ? UNKNOWN : problems.getOrDefault(code, UNKNOWN);
        } else if (latest.status().equals(SchoolSyncRun.PARTIAL)) {
            problem = new Problem("partial", "Partly synced " + at(latest.finishedAt(), now),
                    "Couldn't read: " + partNames(failedParts(latest.parts(), PART_ORDER))
                            + ". The previous data for it is still shown.");
        }
        if (problem != null) {
            return new Status(problem.state(), problem.headline(), problem.detail(), lastGoodFinishedAt, warning);
        }
        return new Status("success", "Synced " + at(latest.finishedAt(), now), null, lastGoodFinishedAt, warning);
    }
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `(cd web && ./mvnw -B test -Dtest='VietnamTimeTest,SyncStatusTest')`
Expected: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Let the What-changed texts use the shared helpers**

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java` with (only the time formatting moved to `VietnamTime`):

```java
package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import vn.edu.hcmiu.sla.school.VietnamTime;

/**
 * The "What changed" feed: compare old and new data, describe the differences. The Java twin of
 * app/school/services/changes.py, with the same wording.
 *
 * <p>Times are UTC as stored and shown in Vietnam time. Only upcoming classes and exams count, so weeks
 * that are simply over aren't reported.
 */
public final class Changes {

    private Changes() {
    }

    static final int MAX_DATES = 3;
    static final int MATERIALS_NAMED = 3; // titles named in a "new materials" line

    /** One feed line. kind is added, removed or changed. */
    public record Change(String kind, String summary) {
    }

    public record Meeting(String courseCode, String courseName, LocalDateTime startAt, LocalDateTime endAt,
            String room) {
    }

    public record ExamInfo(String courseCode, String courseName, String examType, LocalDateTime startAt,
            String room) {
    }

    public record TuitionInfo(String termCode, long balance, LocalDate dueDate, String statusText) {
    }

    /** An announcement, assignment or material, reduced to what the feed compares. */
    public record BbItem(String kind, String course, String bbId, String title, LocalDateTime dueAt, String status,
            Double score, Double pointsPossible, String gradeText, String materialKind) {

        public static BbItem announcement(String course, String bbId, String title) {
            return new BbItem("announcement", course, bbId, title, null, null, null, null, null, null);
        }

        public static BbItem assignment(String course, String bbId, String title, LocalDateTime dueAt, String status,
                Double score, Double pointsPossible, String gradeText) {
            return new BbItem("assignment", course, bbId, title, dueAt, status, score, pointsPossible, gradeText, null);
        }

        public static BbItem material(String course, String bbId, String title, String materialKind) {
            return new BbItem("material", course, bbId, title, null, null, null, null, null, materialKind);
        }
    }

    /** What Blackboard held: the course names and everything in them. */
    public record BbState(List<String> courses, List<BbItem> items) {
    }

    /** "Tue 29/09 08:00" in Vietnam time. */
    private static String when(LocalDateTime utc) {
        return VietnamTime.when(utc);
    }

    private static String dates(Collection<LocalDateTime> moments) {
        List<LocalDateTime> sorted = moments.stream().sorted().toList();
        List<String> shown = sorted.stream().limit(MAX_DATES).map(Changes::when).toList();
        String text = String.join(", ", shown);
        if (sorted.size() > MAX_DATES) {
            text += " and " + (sorted.size() - MAX_DATES) + " more";
        }
        return text;
    }

    private static String count(long n, String singular) {
        return count(n, singular, singular + "s");
    }

    private static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static String day(LocalDate value) {
        return VietnamTime.fullDate(value);
    }

    private static String money(long vnd) {
        return String.format(Locale.ROOT, "%,d", vnd);
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static boolean upcoming(LocalDateTime start, LocalDateTime now) {
        return !start.isBefore(now);
    }

    // ---- Timetable --------------------------------------------------------------

    private record MeetingKey(String courseCode, LocalDateTime startAt) {
    }

    private record Kept(Meeting before, Meeting after) {
    }

    private record RoomMove(String from, String to) {
    }

    private static Map<MeetingKey, Meeting> upcomingMeetings(List<Meeting> meetings, LocalDateTime now) {
        Map<MeetingKey, Meeting> byKey = new LinkedHashMap<>();
        for (Meeting m : meetings) {
            if (upcoming(m.startAt(), now)) {
                byKey.put(new MeetingKey(m.courseCode(), m.startAt()), m);
            }
        }
        return byKey;
    }

    /** old is null on the first sync of a term (or while it had no classes). */
    public static List<Change> timetable(List<Meeting> old, List<Meeting> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing to announce yet
            }
            long courses = fresh.stream().map(Meeting::courseCode).distinct().count();
            long upcoming = fresh.stream().filter(m -> upcoming(m.startAt(), now)).count();
            return List.of(new Change("added", "Timetable loaded: " + count(courses, "course") + ", "
                    + count(upcoming, "upcoming class", "upcoming classes")));
        }

        Map<MeetingKey, Meeting> before = upcomingMeetings(old, now);
        Map<MeetingKey, Meeting> after = upcomingMeetings(fresh, now);
        Map<String, String> labels = new TreeMap<>();
        for (Meeting m : before.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }
        for (Meeting m : after.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }

        List<Change> changes = new ArrayList<>();
        labels.forEach((code, label) -> {
            List<LocalDateTime> removed = new ArrayList<>();
            List<Meeting> added = new ArrayList<>();
            List<Kept> kept = new ArrayList<>();
            before.forEach((key, meeting) -> {
                if (key.courseCode().equals(code)) {
                    if (after.containsKey(key)) {
                        kept.add(new Kept(meeting, after.get(key)));
                    } else {
                        removed.add(key.startAt());
                    }
                }
            });
            after.forEach((key, meeting) -> {
                if (key.courseCode().equals(code) && !before.containsKey(key)) {
                    added.add(meeting);
                }
            });

            if (!removed.isEmpty()) {
                String what = removed.size() == 1 ? "class cancelled" : removed.size() + " classes cancelled";
                changes.add(new Change("removed", label + ": " + what + " on " + dates(removed)));
            }

            if (added.size() == 1) {
                Meeting m = added.get(0);
                String room = m.room() == null || m.room().isEmpty() ? "" : " (" + m.room() + ")";
                changes.add(new Change("added", label + ": new class on " + when(m.startAt()) + room));
            } else if (!added.isEmpty()) {
                changes.add(new Change("added", label + ": " + added.size() + " new classes on "
                        + dates(added.stream().map(Meeting::startAt).toList())));
            }

            Map<RoomMove, List<LocalDateTime>> roomMoves = new LinkedHashMap<>();
            for (Kept pair : kept) {
                if (!Objects.equals(pair.before().room(), pair.after().room())) {
                    roomMoves.computeIfAbsent(new RoomMove(pair.before().room(), pair.after().room()),
                            move -> new ArrayList<>()).add(pair.after().startAt());
                }
            }
            roomMoves.entrySet().stream()
                    .sorted(Comparator.comparing(move -> Collections.min(move.getValue())))
                    .forEach(move -> changes.add(new Change("changed", label + ": room "
                            + orElse(move.getKey().from(), "?") + " → " + orElse(move.getKey().to(), "?")
                            + " on " + dates(move.getValue()))));

            kept.stream()
                    .sorted(Comparator.comparing(pair -> pair.after().startAt()))
                    .filter(pair -> !pair.before().endAt().equals(pair.after().endAt()))
                    .forEach(pair -> changes.add(new Change("changed", label + ": class on "
                            + when(pair.after().startAt()) + " now ends at "
                            + VietnamTime.clock(pair.after().endAt()))));
        });
        return changes;
    }

    // ---- Exams ------------------------------------------------------------------

    private record ExamKey(String courseCode, String examType) {
    }

    private static Map<ExamKey, ExamInfo> upcomingExams(List<ExamInfo> exams, LocalDateTime now) {
        Map<ExamKey, ExamInfo> byKey = new LinkedHashMap<>();
        for (ExamInfo e : exams) {
            if (upcoming(e.startAt(), now)) {
                byKey.put(new ExamKey(e.courseCode(), e.examType()), e);
            }
        }
        return byKey;
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word
                : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** old is null on the first sync of a term (or while no exams were published). */
    public static List<Change> exams(List<ExamInfo> old, List<ExamInfo> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing published yet; announced once exams appear
            }
            return List.of(new Change("added", "Exam schedule loaded: " + count(fresh.size(), "exam")));
        }

        Map<ExamKey, ExamInfo> before = upcomingExams(old, now);
        Map<ExamKey, ExamInfo> after = upcomingExams(fresh, now);
        Set<ExamKey> keys = new TreeSet<>(Comparator.comparing(ExamKey::courseCode).thenComparing(ExamKey::examType));
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        List<Change> changes = new ArrayList<>();
        for (ExamKey key : keys) {
            ExamInfo oldExam = before.get(key);
            ExamInfo newExam = after.get(key);
            ExamInfo exam = newExam != null ? newExam : oldExam;
            String label = exam.courseCode() + " " + exam.courseName();
            String type = exam.examType();
            if (oldExam == null) {
                String room = newExam.room() == null || newExam.room().isEmpty() ? "" : " (" + newExam.room() + ")";
                changes.add(new Change("added", "New " + type + " exam: " + label + ", " + when(newExam.startAt()) + room));
            } else if (newExam == null) {
                changes.add(new Change("removed", capitalize(type) + " exam removed: " + label));
            } else {
                if (!oldExam.startAt().equals(newExam.startAt())) {
                    changes.add(new Change("changed", label + " " + type + " exam moved: "
                            + when(oldExam.startAt()) + " → " + when(newExam.startAt())));
                }
                if (!Objects.equals(oldExam.room(), newExam.room())) {
                    changes.add(new Change("changed", label + " " + type + " exam room: "
                            + orElse(oldExam.room(), "?") + " → " + orElse(newExam.room(), "?")));
                }
            }
        }
        return changes;
    }

    // ---- Tuition ----------------------------------------------------------------

    /** old is null on the first sync of a term. */
    public static List<Change> tuition(TuitionInfo old, TuitionInfo fresh) {
        String title = "Tuition " + fresh.termCode();
        if (old == null) {
            String due = fresh.dueDate() == null ? "" : ", due " + day(fresh.dueDate());
            return List.of(new Change("added", title + ": balance " + money(fresh.balance()) + " VND" + due));
        }

        List<Change> changes = new ArrayList<>();
        if (old.balance() != fresh.balance()) {
            changes.add(new Change("changed", title + ": balance " + money(old.balance()) + " → "
                    + money(fresh.balance()) + " VND"));
        }
        if (!Objects.equals(old.dueDate(), fresh.dueDate())) {
            String text;
            if (old.dueDate() == null) {
                text = "due date " + day(fresh.dueDate());
            } else if (fresh.dueDate() == null) {
                text = "due date removed";
            } else {
                text = "due date " + day(old.dueDate()) + " → " + day(fresh.dueDate());
            }
            changes.add(new Change("changed", title + ": " + text));
        }
        if (!Objects.equals(old.statusText(), fresh.statusText())) {
            changes.add(new Change("changed", title + ": status " + orElse(old.statusText(), "-") + " → "
                    + orElse(fresh.statusText(), "-")));
        }
        return changes;
    }

    // ---- Blackboard -------------------------------------------------------------

    private record ItemKey(String kind, String bbId) {
    }

    /** Like Python's f"{value:g}": 6 significant digits, no trailing zeros (8.5, 10, 6.66667, 1e+06). */
    static String number(double value) {
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(6, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (rounded.signum() != 0 && (exponent < -4 || exponent >= 6)) {
            String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
            return mantissa + String.format(Locale.ROOT, "e%+03d", exponent);
        }
        return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
    }

    private static String grade(BbItem item) {
        if (item.score() != null && item.pointsPossible() != null && item.pointsPossible() != 0) {
            return number(item.score()) + "/" + number(item.pointsPossible());
        }
        if (item.score() != null) {
            return number(item.score());
        }
        return orElse(item.gradeText(), "graded");
    }

    private static String bbCounts(List<BbItem> items) {
        long announcements = items.stream().filter(i -> i.kind().equals("announcement")).count();
        long assignments = items.stream().filter(i -> i.kind().equals("assignment")).count();
        long materials = items.stream().filter(i -> i.kind().equals("material")).count();
        return count(announcements, "announcement") + ", " + count(assignments, "assignment") + ", "
                + count(materials, "material");
    }

    private static Change materialsLine(String course, List<String> titles) {
        if (titles.size() == 1) {
            return new Change("added", "New material · " + course + ": " + titles.get(0));
        }
        String more = titles.size() > MATERIALS_NAMED ? " and " + (titles.size() - MATERIALS_NAMED) + " more" : "";
        return new Change("added", titles.size() + " new materials · " + course + ": "
                + String.join(", ", titles.subList(0, Math.min(MATERIALS_NAMED, titles.size()))) + more);
    }

    /**
     * old is null on the first Blackboard sync. A newly seen course and a batch of new materials each give
     * one line, so a folder of uploads doesn't push the other news off the short list on the Overview.
     */
    public static List<Change> blackboard(BbState old, BbState fresh) {
        if (old == null) {
            if (fresh.courses().isEmpty()) {
                return List.of();
            }
            return List.of(new Change("added", "Blackboard loaded: " + count(fresh.courses().size(), "course") + ", "
                    + bbCounts(fresh.items())));
        }

        Set<String> known = new HashSet<>(old.courses());
        List<Change> changes = new ArrayList<>();
        for (String course : fresh.courses()) {
            if (!known.contains(course)) {
                changes.add(new Change("added", "New course on Blackboard · " + course + ": "
                        + bbCounts(fresh.items().stream().filter(i -> i.course().equals(course)).toList())));
            }
        }
        Map<ItemKey, BbItem> before = new LinkedHashMap<>();
        for (BbItem item : old.items()) {
            before.put(new ItemKey(item.kind(), item.bbId()), item);
        }
        Map<String, List<String>> newMaterials = new LinkedHashMap<>();
        for (BbItem item : fresh.items()) {
            if (!known.contains(item.course())) {
                continue;
            }
            BbItem previous = before.get(new ItemKey(item.kind(), item.bbId()));
            if (item.kind().equals("announcement") && previous == null) {
                changes.add(new Change("added", "New announcement · " + item.course() + ": " + item.title()));
            } else if (item.kind().equals("assignment") && previous == null) {
                String due = item.dueAt() == null ? "" : ", due " + when(item.dueAt());
                changes.add(new Change("added", "New assignment · " + item.course() + ": " + item.title() + due));
            } else if (item.kind().equals("assignment")) {
                if (item.dueAt() != null && previous.dueAt() != null && !item.dueAt().equals(previous.dueAt())) {
                    changes.add(new Change("changed", "Due date changed · " + item.course() + ", " + item.title() + ": "
                            + when(previous.dueAt()) + " → " + when(item.dueAt())));
                } else if (item.dueAt() != null && previous.dueAt() == null) {
                    changes.add(new Change("changed", "Due date set · " + item.course() + ", " + item.title() + ": "
                            + when(item.dueAt())));
                }
                if ("graded".equals(item.status())
                        && (!"graded".equals(previous.status()) || !Objects.equals(previous.score(), item.score()))) {
                    changes.add(new Change("changed", "New grade · " + item.course() + ", " + item.title() + ": "
                            + grade(item)));
                }
            } else if (item.kind().equals("material") && previous == null && !"folder".equals(item.materialKind())) {
                newMaterials.computeIfAbsent(item.course(), course -> new ArrayList<>()).add(item.title());
            }
        }
        newMaterials.forEach((course, titles) -> changes.add(materialsLine(course, titles)));
        return changes;
    }
}
```

Run: `(cd web && ./mvnw -B test -Dtest=ChangesTest)`
Expected: `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 191, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/VietnamTime.java web/src/main/java/vn/edu/hcmiu/sla/school/pages web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java web/src/test/java/vn/edu/hcmiu/sla/school/VietnamTimeTest.java web/src/test/java/vn/edu/hcmiu/sla/school/pages
git commit -m "feat(web): Vietnam time helpers and the sync status texts in Java"
```

---

### Task 2: Class changes from Blackboard announcements

A port of `app/school/services/class_changes.py`, the announcement reader, with every test case of `tests/test_school_class_changes.py`. Python and Java regular expressions differ in three ways that matter here. Java writes named groups as `(?<name>…)`. Java has no `lastgroup`, so the code asks which group matched. And Java's `\w`, `\b`, `\d` and `\s` know Vietnamese letters only in Unicode mode. For the Python test that makes the reader fail, `changesFrom` takes the reader as a parameter.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`

**Interfaces:**
- Consumes: `VietnamTime.date` (Task 1).
- Produces (all in `ClassChanges`): `record Announced(String kind, LocalDate day, LocalTime start, LocalTime end, String room)`; `record ClassChange(String code, int bbCourseId, String kind, LocalDate day, LocalTime start, LocalTime end, String room)`; `record Slot(String code, LocalDate day, String slot)` (slot `class` or `makeup`); `record Posted(String code, int bbCourseId, String title, String text, LocalDateTime postedAt)`; `static List<Announced> readAnnouncement(String title, String text, LocalDateTime postedAt)`; `static Map<Slot, ClassChange> changesFrom(List<Posted> announcements)`; package-private `interface Reader` and `changesFrom(List<Posted>, Reader)`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java`:

```java
package vn.edu.hcmiu.sla.school.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;

/** Java twin of tests/test_school_class_changes.py. postedAt values are UTC. */
class ClassChangesTest {

    /** Real announcements from September 2026, with Teams codes and links shortened. */
    record Real(String title, String text, LocalDateTime postedAt) {
    }

    static final Real PROBABILITY_ONLINE = new Real("ONLINE CLASS ON SEPTEMBER 24",
            "Dear all, The class on September 24 is online on MS TEAMS. Please use the following code to access the class.",
            LocalDateTime.of(2026, 9, 23, 16, 7));
    static final Real WEB_ONLINE = new Real("Online Class Notification – Web Application – 22 September 2026",
            "Dear Students, Please be informed that our Web Application class will be conducted online via Microsoft "
                    + "Teams. Date: Tuesday, 22 September 2026 Time: From 8:00 AM Platform: Microsoft Teams Online Class Link: "
                    + "https://teams.microsoft.com/l/meetup-join/19%3ameeting_x%40thread.v2/0?context=%7b%22Tid%22%3a%2212-10%22",
            LocalDateTime.of(2026, 9, 15, 16, 0));
    static final Real PHYSICS_ONLINE = new Real("Link học online sáng thứ Sáu, 18/9/2026, 8g00-9g40",
            "Link: Physics 4 Friday, September 18 Time zone: Asia/Ho_Chi_Minh Google Meet joining info "
                    + "Video call link: https://meet.google.com/abc-defg-hij",
            LocalDateTime.of(2026, 9, 17, 9, 30));
    static final Real PROBABILITY_CANCEL = new Real("Cancel class on September 17",
            "The class this week on September 17 will be canceled. The makeup schedule will be announced later",
            LocalDateTime.of(2026, 9, 15, 2, 36));
    static final Real LOGISTICS = new Real("Logistics Reminder",
            "Lecture attendance: You will attend the first five lectures with me in person in Room A1.603 , with our "
                    + "last in-person lecture on 10/10. Attendance will be taken during these sessions. Starting the week of "
                    + "12/10 , lectures will be taught online by Dr. Nguyen Van A via MS Teams. Please register your group in "
                    + "SkillsGroupTerm1-26-27_Sat.xlsx , available on our MS Teams Channel.",
            LocalDateTime.of(2026, 9, 22, 3, 59));
    static final LocalDateTime POSTED = LocalDateTime.of(2026, 9, 20, 2, 0); // Sun 20/09 09:00 in Vietnam

    static List<Announced> read(String title) {
        return ClassChanges.readAnnouncement(title, "", POSTED);
    }

    static Announced online(int month, int day) {
        return new Announced("online", LocalDate.of(2026, month, day));
    }

    static Announced cancelled(int month, int day) {
        return new Announced("cancelled", LocalDate.of(2026, month, day));
    }

    static Announced makeup(int month, int day, LocalTime start, LocalTime end, String room) {
        return new Announced("makeup", LocalDate.of(2026, month, day), start, end, room);
    }

    static Stream<Arguments> realAnnouncements() {
        return Stream.of(
                Arguments.of(PROBABILITY_ONLINE, online(9, 24)),
                Arguments.of(WEB_ONLINE, online(9, 22)),
                Arguments.of(PHYSICS_ONLINE, online(9, 18)),
                Arguments.of(PROBABILITY_CANCEL, cancelled(9, 17)));
    }

    @ParameterizedTest
    @MethodSource("realAnnouncements")
    void theRealAnnouncements(Real announcement, Announced expected) {
        assertThat(ClassChanges.readAnnouncement(announcement.title(), announcement.text(), announcement.postedAt()))
                .containsOnly(expected);
    }

    @Test
    void onlyASentenceWithAChangeWordCounts() {
        // "in-person ... on 10/10" has no change word. "Starting the week of 12/10 ... online" is read as the
        // single day 12/10; ranges are out of scope (and the course has no class that Monday).
        assertThat(ClassChanges.readAnnouncement(LOGISTICS.title(), LOGISTICS.text(), LOGISTICS.postedAt()))
                .containsExactly(online(10, 12));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Online class on Sept. 24",
        "Online class on 24th September",
        "Online class on September 24th, 2026",
        "Lớp học trực tuyến ngày 24 tháng 9 năm 2026",
        "The lecture on 24/09 is online"})
    void dateFormatsAndVietnamese(String title) {
        assertThat(read(title)).containsExactly(online(9, 24));
    }

    @Test
    void vietnameseTypedWithSeparateAccentMarksReadsTheSame() {
        assertThat(read(Normalizer.normalize("Lớp học trực tuyến ngày 24/9", Normalizer.Form.NFD)))
                .containsExactly(online(9, 24));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lớp nghỉ ngày 24/09", "Hủy buổi học 24-9-2026", "No class on Sep 24",
        "Class on 24/9 is cancelled", "Nghỉ học ngày 24/9"})
    void cancelWords(String title) {
        assertThat(read(title)).containsExactly(cancelled(9, 24));
    }

    static Stream<Arguments> wordForms() {
        return Stream.of(
                Arguments.of("Classes on 24/9 are cancelled", "cancelled"),
                Arguments.of("Class cancellation on 24/9", "cancelled"),
                Arguments.of("We are cancelling the lecture on 24/9", "cancelled"),
                Arguments.of("Lectures on 24/9 will be online", "online"));
    }

    @ParameterizedTest
    @MethodSource("wordForms")
    void pluralsAndWordForms(String title, String kind) {
        assertThat(read(title)).containsExactly(new Announced(kind, LocalDate.of(2026, 9, 24)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Submit your report online by 24/9", // no class word
        "The class on September 10 was online", // before the posting day
        "Online class soon", // no date
        "Online class from 10:30-11:45 in A2.401", // times are not dates
        "Online class, see https://example.com/10-11/12", // dates inside links are ignored
        "Tell your classmates to submit online by 24/9", // "classmates" is not a class word
        "The classroom booking system goes online on 24/9", // nor is "classroom"
        "Nộp bài trực tuyến trước ngày 24/9 cho môn học"}) // a deadline, not a class
    void whatChangesNothing(String title) {
        assertThat(read(title)).isEmpty();
    }

    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of("No class on Thursday 24/9 (8-10)", cancelled(9, 24)),
                Arguments.of("The class on Thursday 24/9 will be online, 8-10am", online(9, 24)),
                Arguments.of("Học bù ngày 3/10, tiết 10-12", makeup(10, 3, null, null, null)));
    }

    @ParameterizedTest
    @MethodSource("ranges")
    void hourAndPeriodRangesAreNotDates(String title, Announced expected) {
        assertThat(read(title)).containsExactly(expected);
    }

    @Test
    void paragraphsFromBlackboardStaySeparateSentences() {
        // What the agent's html_to_text makes of <p>…</p><p>…</p>: one line per paragraph.
        String text = "our class on 24/9 will be online via MS Teams\nReminder: Homework 2 is due in class on 1/10";

        assertThat(ClassChanges.readAnnouncement("Notice", text, POSTED)).containsExactly(online(9, 24));
    }

    @Test
    void aDateWithoutAYearIsPlacedNearThePostingDate() {
        assertThat(ClassChanges.readAnnouncement("Online class on January 5", "", LocalDateTime.of(2026, 12, 28, 2, 0)))
                .containsExactly(new Announced("online", LocalDate.of(2027, 1, 5)));
    }

    @Test
    void eachDateTakesTheNearestChangeWord() {
        assertThat(read("The class on 26/9 is cancelled; make-up class on 3/10 from 8:00 to 9:40 in A2.401."))
                .containsExactly(cancelled(9, 26), makeup(10, 3, LocalTime.of(8, 0), LocalTime.of(9, 40), "A2.401"));
    }

    static Stream<Arguments> makeUps() {
        return Stream.of(
                Arguments.of("Học bù ngày 3/10", null, null, null),
                Arguments.of("Make-up class on 3/10, 8g00-9g40", LocalTime.of(8, 0), LocalTime.of(9, 40), null),
                Arguments.of("Make up class on 3/10 at 13h15", LocalTime.of(13, 15), null, null),
                Arguments.of("Makeup class online on 3/10, 1:15 PM", LocalTime.of(13, 15), null, "Online"),
                Arguments.of("Make-up lecture on 3/10 from 8:00 AM to 9:40 AM, room R109", LocalTime.of(8, 0),
                        LocalTime.of(9, 40), "R109"),
                Arguments.of("Make-up class at 8:00 on 3/10", LocalTime.of(8, 0), null, null),
                Arguments.of("Make-up class on 3/10 from 1:15 to 3:45 PM", LocalTime.of(13, 15), LocalTime.of(15, 45), null),
                Arguments.of("Make-up class on 3/10, 1:15-3:45pm", LocalTime.of(13, 15), LocalTime.of(15, 45), null),
                Arguments.of("Make-up class on 3/10 from 11:00 to 1:00 PM", LocalTime.of(11, 0), LocalTime.of(13, 0), null),
                Arguments.of("Thầy dạy bù ngày 3/10", null, null, null));
    }

    @ParameterizedTest
    @MethodSource("makeUps")
    void makeUpClasses(String title, LocalTime start, LocalTime end, String room) {
        assertThat(read(title)).containsExactly(makeup(10, 3, start, end, room));
    }

    @Test
    void aMakeUpTakesTheTimeAndRoomNextToItsOwnDate() {
        assertThat(read("Class on Thursday 24/9 13:15-15:45 cancelled, make-up class on Saturday 3/10 8:00-9:40 room A2.401"))
                .containsExactly(cancelled(9, 24), makeup(10, 3, LocalTime.of(8, 0), LocalTime.of(9, 40), "A2.401"));
    }

    @Test
    void twoMakeUpsInOneSentenceKeepTheirOwnTimes() {
        assertThat(read("Make-up class on 1/10 at 8:00 and make-up class on 3/10 at 14:00 in R109")).containsExactly(
                makeup(10, 1, LocalTime.of(8, 0), null, null), makeup(10, 3, LocalTime.of(14, 0), null, "R109"));
    }

    // ---- Changes by course and day -------------------------------------------------

    static Posted row(String title, LocalDateTime posted) {
        return new Posted("MA026IU", 5, title, "", posted);
    }

    @Test
    void theNewestAnnouncementWinsForACourseAndDate() {
        Map<Slot, ClassChange> changes = ClassChanges.changesFrom(List.of(
                row("Class on 24/9 is cancelled", LocalDateTime.of(2026, 9, 21, 0, 0)),
                row("Online class on 24/9", LocalDateTime.of(2026, 9, 20, 0, 0))));

        assertThat(changes).containsExactly(Map.entry(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class"),
                new ClassChange("MA026IU", 5, "cancelled", LocalDate.of(2026, 9, 24), null, null, null)));
    }

    @Test
    void aLaterMakeUpNoticeKeepsTheCancellationOfTheSameDay() {
        Map<Slot, ClassChange> changes = ClassChanges.changesFrom(List.of(
                row("Cancel class on September 24", LocalDateTime.of(2026, 9, 15, 0, 0)),
                row("The make-up for the class on September 24 will be on 3/10 at 8:00", LocalDateTime.of(2026, 9, 16, 0, 0))));

        assertThat(changes.get(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class")).kind()).isEqualTo("cancelled");
        assertThat(changes.get(new Slot("MA026IU", LocalDate.of(2026, 10, 3), "makeup")).start()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void announcementsWithoutACourseCodeOrTimeAreSkipped() {
        assertThat(ClassChanges.changesFrom(List.of(row("Online class on 24/9", null),
                new Posted(null, 5, "Online class on 24/9", "", POSTED)))).isEmpty();
    }

    @Test
    void anUnreadableAnnouncementIsSkipped() {
        ClassChanges.Reader failsOnBad = (title, text, postedAt) -> {
            if (title.equals("bad")) {
                throw new IllegalStateException("boom");
            }
            return ClassChanges.readAnnouncement(title, text, postedAt);
        };

        assertThat(ClassChanges.changesFrom(List.of(row("bad", POSTED), row("Online class on 24/9", POSTED)), failsOnBad))
                .containsOnlyKeys(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class"));
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=ClassChangesTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `ClassChanges`.

- [ ] **Step 3: Write the reader**

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java`:

```java
package vn.edu.hcmiu.sla.school.schedule;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.edu.hcmiu.sla.school.VietnamTime;

/**
 * Class changes announced on Blackboard: online, cancelled and make-up classes. Pure functions; the Java
 * twin of app/school/services/class_changes.py, with the same rules and test cases.
 *
 * <p>A sentence that mentions a class, a change word and a date makes a change; each date takes the
 * nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
 * docs/superpowers/specs/2026-09-26-class-changes-and-to-submit-design.md, section 2.
 *
 * <p>Patterns use Unicode mode, like Python's: {@code \w}, {@code \b}, {@code \d} and {@code \s} know
 * Vietnamese letters, and text is normalised to NFC first, so "lớp" typed either way matches.
 */
public final class ClassChanges {

    private ClassChanges() {
    }

    private static final Logger log = LoggerFactory.getLogger(ClassChanges.class);

    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int IGNORE_CASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE;

    static final Pattern CHANGE_WORDS = Pattern.compile(
            "(?<makeup>\\bmake[\\s-]?up\\b|\\bbù\\b)"
                    + "|(?<cancelled>\\bcancel(?:s|ed|led|ing|ling|lations?)?\\b|\\bno class(?:es)?\\b|\\bnghỉ\\b"
                    + "|\\bhủy\\b|\\bhuỷ\\b)"
                    + "|(?<online>\\bonline\\b|\\btrực tuyến\\b)",
            IGNORE_CASE);
    static final Pattern CLASS_WORDS = Pattern.compile(
            "\\b(?:class(?:es)?|lectures?|sessions?|lessons?|lớp|buổi|tiết|(?:học|dạy) (?:online|trực tuyến|bù)"
                    + "|nghỉ học)\\b",
            IGNORE_CASE);

    static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("june", 6), Map.entry("july", 7), Map.entry("august", 8),
            Map.entry("september", 9), Map.entry("october", 10), Map.entry("november", 11),
            Map.entry("december", 12), Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
            Map.entry("apr", 4), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9),
            Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));
    // Longest names first, so "september" is tried before "sep".
    static final String MONTH = MONTH_NAMES.keySet().stream()
            .sorted(Comparator.comparing(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .collect(Collectors.joining("|"));
    static final String ORDINAL = "(?:st|nd|rd|th)?";
    static final String YEAR = "(?:,?\\s+(?<year>\\d{4}))?";
    static final List<Pattern> DATE_FORMATS = List.of(
            Pattern.compile("\\b(?<month>" + MONTH + ")\\b\\.?\\s+(?<day>\\d{1,2})" + ORDINAL + "(?!\\d)" + YEAR,
                    IGNORE_CASE),
            Pattern.compile("(?<!\\d)(?<day>\\d{1,2})" + ORDINAL + "\\s+(?:of\\s+)?(?<month>" + MONTH + ")\\b\\.?"
                    + YEAR, IGNORE_CASE),
            Pattern.compile("(?<![\\w/.:])(?<day>\\d{1,2})/(?<month>\\d{1,2})(?:/(?<year>\\d{4}))?(?![\\d/])",
                    UNICODE),
            Pattern.compile("(?<![\\w/.:-])(?<day>\\d{1,2})-(?<month>\\d{1,2})-(?<year>\\d{4})(?![\\d-])", UNICODE),
            Pattern.compile("ngày\\s+(?<day>\\d{1,2})\\s+tháng\\s+(?<month>\\d{1,2})(?:\\s+năm\\s+(?<year>\\d{4}))?",
                    IGNORE_CASE));
    static final Pattern TIME = Pattern.compile(
            "(?<![\\w/.:])(?<hour>\\d{1,2})(?:[:hg](?<minute>\\d{2})?|(?=\\s*[ap]\\.?m\\b))"
                    + "(?:\\s*(?<ampm>[ap])\\.?m\\.?)?(?!\\w)",
            IGNORE_CASE);
    static final Pattern RANGE_JOIN = Pattern.compile("\\s*(?:-|–|to|đến)\\s*", IGNORE_CASE);
    static final Pattern ROOM = Pattern.compile("(?<![\\w.])(?:[A-Z]{1,2}\\d\\.\\d{3}|R\\d{3})(?!\\w|\\.\\w)", UNICODE);
    static final Pattern URL = Pattern.compile("https?://\\S+", UNICODE);
    static final List<String> ABBREVIATIONS = List.of("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec", "dr", "mr", "ms", "mrs");
    static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.!?])" + ABBREVIATIONS.stream().map(a -> "(?<!\\b" + a + "\\.)").collect(Collectors.joining())
                    + "(?<![ap]\\.m\\.)\\s+|\\n+",
            IGNORE_CASE);

    /** A change one announcement makes. kind: online / cancelled / makeup; start, end, room: make-ups only. */
    public record Announced(String kind, LocalDate day, LocalTime start, LocalTime end, String room) {

        Announced(String kind, LocalDate day) {
            this(kind, day, null, null, null);
        }
    }

    /** A change for one course: code "MA026IU", bbCourseId the app's page for the course that posted it. */
    public record ClassChange(String code, int bbCourseId, String kind, LocalDate day, LocalTime start, LocalTime end,
            String room) {
    }

    /** Where a change applies: slot "class" (online, cancelled) or "makeup" (a make-up class). */
    public record Slot(String code, LocalDate day, String slot) {
    }

    /** An announcement with its course: what {@link #changesFrom} reads. postedAt is UTC. */
    public record Posted(String code, int bbCourseId, String title, String text, LocalDateTime postedAt) {
    }

    /** Reads one announcement; tests swap in a reader that fails. */
    interface Reader {
        List<Announced> read(String title, String text, LocalDateTime postedAt);
    }

    private record Found(int start, int end, LocalDate day) {
    }

    private record Word(int start, String kind) {
    }

    private static LocalDate date(Matcher match, LocalDate postedDay) {
        String monthText = match.group("month");
        int month = monthText.chars().allMatch(Character::isLetter)
                ? MONTH_NAMES.get(monthText.toLowerCase(Locale.ROOT))
                : Integer.parseInt(monthText);
        int day = Integer.parseInt(match.group("day"));
        String year = match.group("year");
        try {
            if (year != null) {
                return LocalDate.of(Integer.parseInt(year), month, day);
            }
            List<LocalDate> candidates = new ArrayList<>();
            for (int k = -1; k <= 1; k++) {
                candidates.add(LocalDate.of(postedDay.getYear() + k, month, day));
            }
            LocalDate nearest = candidates.get(0);
            for (LocalDate candidate : candidates) {
                if (distance(candidate, postedDay) < distance(nearest, postedDay)) {
                    nearest = candidate;
                }
            }
            return nearest;
        } catch (DateTimeException error) {
            return null; // 31/2, month 13, ...
        }
    }

    private static long distance(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    /** The dates in a sentence from the posting day on, in the order they appear, with their positions. */
    private static List<Found> dates(String sentence, LocalDate postedDay) {
        List<Found> found = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        for (Pattern pattern : DATE_FORMATS) {
            Matcher match = pattern.matcher(sentence);
            while (match.find()) {
                int start = match.start();
                int end = match.end();
                if (taken.stream().anyMatch(span -> start < span[1] && span[0] < end)) {
                    continue;
                }
                taken.add(new int[] {start, end});
                LocalDate day = date(match, postedDay);
                if (day != null && !day.isBefore(postedDay)) {
                    found.add(new Found(start, end, day));
                }
            }
        }
        found.sort(Comparator.comparingInt(Found::start).thenComparingInt(Found::end).thenComparing(Found::day));
        return found;
    }

    /** The time a match names, read with ampm ("a" / "p") when given, else with its own AM/PM. */
    private static LocalTime time(Matcher match, String ampm) {
        int hour = Integer.parseInt(match.group("hour"));
        int minute = match.group("minute") == null ? 0 : Integer.parseInt(match.group("minute"));
        String half = (ampm != null ? ampm : match.group("ampm") != null ? match.group("ampm") : "")
                .toLowerCase(Locale.ROOT);
        if (half.equals("p") && hour < 12) {
            hour += 12;
        }
        if (half.equals("a") && hour == 12) {
            hour = 0;
        }
        return hour < 24 && minute < 60 ? LocalTime.of(hour, minute) : null;
    }

    /**
     * The first time in a text and, for a range like 8:00-9:40, its end. In a range, a start without AM/PM
     * takes the end's when that keeps it before the end: "1:15 to 3:45 PM" is 13:15-15:45.
     */
    private static LocalTime[] times(String text) {
        Matcher first = TIME.matcher(text);
        if (!first.find()) {
            return new LocalTime[] {null, null};
        }
        LocalTime start = time(first, null);
        LocalTime end = null;
        Matcher second = TIME.matcher(text);
        if (second.find(first.end()) && RANGE_JOIN.matcher(text.substring(first.end(), second.start())).matches()) {
            end = time(second, null);
            if (start != null && end != null && first.group("ampm") == null && second.group("ampm") != null) {
                LocalTime shifted = time(first, second.group("ampm"));
                if (shifted != null && shifted.isBefore(end)) {
                    start = shifted;
                }
            }
        }
        return new LocalTime[] {start, end};
    }

    private static String kindOf(Matcher match) {
        if (match.group("makeup") != null) {
            return "makeup";
        }
        return match.group("cancelled") != null ? "cancelled" : "online";
    }

    /** The class changes one announcement makes. postedAt is UTC. */
    public static List<Announced> readAnnouncement(String title, String text, LocalDateTime postedAt) {
        LocalDate postedDay = VietnamTime.date(postedAt);
        List<String> sentences = new ArrayList<>();
        sentences.add(title == null ? "" : title);
        sentences.addAll(List.of(SENTENCE_END.split(text == null ? "" : text, -1)));

        List<Announced> found = new ArrayList<>();
        for (String raw : sentences) {
            String sentence = URL.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ");
            List<Word> words = new ArrayList<>();
            Matcher change = CHANGE_WORDS.matcher(sentence);
            while (change.find()) {
                words.add(new Word(change.start(), kindOf(change)));
            }
            if (words.isEmpty() || !CLASS_WORDS.matcher(sentence).find()) {
                continue;
            }
            // Online words decide only when there is no cancel or make-up word: "make-up class online on
            // 3/10" is an online make-up class.
            List<Word> deciding = words.stream().filter(word -> !word.kind().equals("online")).toList();
            if (deciding.isEmpty()) {
                deciding = words;
            }
            List<Found> dates = dates(sentence, postedDay);
            for (int i = 0; i < dates.size(); i++) {
                Found date = dates.get(i);
                Word nearest = deciding.get(0);
                for (Word word : deciding) {
                    if (Math.abs(word.start() - date.start()) < Math.abs(nearest.start() - date.start())) {
                        nearest = word;
                    }
                }
                if (!nearest.kind().equals("makeup")) {
                    found.add(new Announced(nearest.kind(), date.day()));
                    continue;
                }
                // A make-up's time and room come from the text after its date (up to the next date), or else
                // from the text before it (back to the previous date).
                String after = sentence.substring(date.end(), i + 1 < dates.size() ? dates.get(i + 1).start()
                        : sentence.length());
                String before = sentence.substring(i > 0 ? dates.get(i - 1).end() : 0, date.start());
                LocalTime[] clock = times(TIME.matcher(after).find() ? after : before);
                Matcher room = ROOM.matcher(after);
                if (!room.find()) {
                    room = ROOM.matcher(before);
                    if (!room.find()) {
                        room = null;
                    }
                }
                boolean online = words.stream().anyMatch(word -> word.kind().equals("online"));
                found.add(new Announced("makeup", date.day(), clock[0], clock[1],
                        online ? "Online" : room == null ? null : room.group()));
            }
        }
        return found;
    }

    /**
     * Changes by course, day and slot. The newest announcement wins within each slot, so a later make-up
     * notice naming a cancelled day doesn't erase the cancellation. Announcements without a course code or a
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

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest=ClassChangesTest)`
Expected: `Tests run: 50, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 241, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/schedule/ClassChanges.java web/src/test/java/vn/edu/hcmiu/sla/school/schedule/ClassChangesTest.java
git commit -m "feat(web): read online, cancelled and make-up classes from Blackboard announcements, in Java"
```

---

### Task 3: A day's classes and exams, and the calendar feed

`Schedule` ports `app/school/services/schedule.py`. It collects a range's classes and exams, marks the classes an announcement made online or cancelled, and adds make-up classes. It also gives the Blackboard deadlines. `CalendarController` ports the Python `calendar_feed` route: the same JSON events, Vietnam wall-clock times, and a range of 1 to 62 days. `SchoolTestData` saves rows for the School tests. `CalendarFeedTest` twins the feed cases of `tests/test_school_schedule_pages.py`, `tests/test_school_blackboard_pages.py` and `tests/test_school_class_change_pages.py`.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/schedule/Schedule.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/schedule/CalendarController.java`
- Modify (replaced; each gains its calendar queries): `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolClassMeetingRepository.java`, `SchoolExamRepository.java`, `SchoolCourseRepository.java`, `SchoolBbAnnouncementRepository.java`, `SchoolBbAssignmentRepository.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`

**Interfaces:**
- Consumes: `ClassChanges.changesFrom`, `Posted`, `Slot`, `ClassChange` (Task 2); `VietnamTime` (Task 1); the stage 2a entities; `AppUser` (stage 1).
- Produces:
  - Repositories: `SchoolClassMeetingRepository.findStarting(userId, start, end)` (with each meeting's course), `SchoolExamRepository.findStarting(userId, start, end)`, `SchoolCourseRepository.findWithMeetings(userId)`, `SchoolBbAnnouncementRepository.findWithCourse(userId)`, `SchoolBbAssignmentRepository.findDue(userId, start, end)` (with each assignment's course).
  - `Schedule` (a Spring bean): `EXAM_LABELS`; `record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room, String label, String change, Integer bbCourseId, boolean allDay)`; `List<Item> itemsBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc)`; `List<Item> itemsOn(Integer userId, LocalDate day)`; `List<SchoolBbAssignment> deadlinesBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc)`.
  - `GET /school/api/calendar?start=…&end=…` (login needed).
  - Test helper `SchoolTestData(EntityManager)`: `BB`, `record Meeting(start, end, room)`; `AppUser user(email)`; `course(user, code, name, Meeting...)`; `exam(user, code, name, start, room, type)`; `tuition(user, balance, dueDate, statusText)`; `SchoolBbCourse bbCourse(user, code, name)`; `announce(course, title, text, postedAt)`; `assign(course, name, dueAt, status, score, pointsPossible, feedback)`; `material(course, title, kind, path, createdAt)`; `Integer save(course)`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java`:

```java
package vn.edu.hcmiu.sla.school;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncement;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;

/** Rows for School page tests, saved straight into the test database. All times are UTC. */
public final class SchoolTestData {

    public static final String BB = "https://blackboard.hcmiu.edu.vn/x";

    /** A class: start and end in UTC, and its room. */
    public record Meeting(LocalDateTime start, LocalDateTime end, String room) {
    }

    private final EntityManager db;

    public SchoolTestData(EntityManager db) {
        this.db = db;
    }

    /** A new account; the returned principal logs it in with {@code user(...)} in MockMvc. */
    public AppUser user(String email) {
        User user = new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0));
        db.persist(user);
        return AppUser.of(user);
    }

    public void course(AppUser user, String code, String name, Meeting... meetings) {
        SchoolCourse course = new SchoolCourse(user.id(), "20261", code, name, null, null, null);
        for (Meeting m : meetings) {
            course.addMeeting(m.start(), m.end(), m.room());
        }
        db.persist(course);
        db.flush();
    }

    public void exam(AppUser user, String code, String name, LocalDateTime start, String room, String type) {
        db.persist(new SchoolExam(user.id(), "20261", code, name, type, start, null, room, null));
        db.flush();
    }

    public void tuition(AppUser user, long balance, LocalDate dueDate, String statusText) {
        db.persist(new SchoolTuition(user.id(), "20261", balance, 0, balance, dueDate, statusText, List.of()));
        db.flush();
    }

    /** A Blackboard course; add announcements, assignments and materials to it before calling {@link #save}. */
    public SchoolBbCourse bbCourse(AppUser user, String code, String name) {
        return new SchoolBbCourse(user.id(), "_" + code + "_" + name.length(), code, name, BB);
    }

    public SchoolBbCourse announce(SchoolBbCourse course, String title, String text, LocalDateTime postedAt) {
        course.getAnnouncements().add(new SchoolBbAnnouncement(course, "_a" + course.getAnnouncements().size(), title,
                text, postedAt, BB));
        return course;
    }

    public SchoolBbCourse assign(SchoolBbCourse course, String name, LocalDateTime dueAt, String status, Double score,
            Double pointsPossible, String feedback) {
        course.getAssignments().add(new SchoolBbAssignment(course, "_x" + course.getAssignments().size(), name, dueAt,
                pointsPossible, score, null, status, feedback, BB));
        return course;
    }

    public SchoolBbCourse material(SchoolBbCourse course, String title, String kind, String path,
            LocalDateTime createdAt) {
        course.getMaterials().add(new SchoolBbMaterial(course, "_m" + course.getMaterials().size(), title, kind, path,
                createdAt, BB));
        return course;
    }

    /** Saves a Blackboard course with everything added to it; returns its id (its page is /school/courses/{id}). */
    public Integer save(SchoolBbCourse course) {
        db.persist(course);
        db.flush();
        return course.getId();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java`:

```java
package vn.edu.hcmiu.sla.school.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;

/**
 * The Timetable's calendar feed: Java twin of the feed tests in tests/test_school_schedule_pages.py,
 * tests/test_school_blackboard_pages.py and tests/test_school_class_change_pages.py. Times in the
 * database are UTC; the feed shows Vietnam time (UTC+7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CalendarFeedTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    // Tue 29/09/2026 08:00-10:30 in Vietnam = 01:00-03:30 UTC
    static final Meeting WEB_TUESDAY = new Meeting(LocalDateTime.of(2026, 9, 29, 1, 0), LocalDateTime.of(2026, 9, 29, 3, 30),
            "A2.508");
    static final String NEXT_WEEK_START = "2026-09-28T00:00:00Z"; // as FullCalendar sends them
    static final String NEXT_WEEK_END = "2026-10-05T00:00:00Z";

    static final String PROBABILITY = "Probability, Statistic & Random Process";
    // Thursdays 13:15-15:45 in Vietnam = 06:15-08:45 UTC
    static final Meeting THU_24 = new Meeting(LocalDateTime.of(2026, 9, 24, 6, 15), LocalDateTime.of(2026, 9, 24, 8, 45),
            "A2.407");
    static final Meeting THU_01 = new Meeting(LocalDateTime.of(2026, 10, 1, 6, 15), LocalDateTime.of(2026, 10, 1, 8, 45),
            "A2.407");
    static final LocalDateTime POSTED = LocalDateTime.of(2026, 9, 20, 2, 0);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    List<Map<String, Object>> feed(String start, String end) throws Exception {
        String body = mvc.perform(get("/school/api/calendar").param("start", start).param("end", end).with(user(an)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    List<Map<String, Object>> nextWeek() throws Exception {
        return feed(NEXT_WEEK_START, NEXT_WEEK_END);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> props(Map<String, Object> event) {
        return (Map<String, Object>) event.get("extendedProps");
    }

    /** The classes in the week of Mon 21/09 - Sun 27/09, or another range. */
    List<Map<String, Object>> classes(String start, String end) throws Exception {
        return feed(start, end).stream().filter(e -> props(e).get("kind").equals("class")).toList();
    }

    List<Map<String, Object>> classes() throws Exception {
        return classes("2026-09-21", "2026-09-28");
    }

    List<Map<String, Object>> makeups() throws Exception {
        return classes().stream().filter(e -> "makeup".equals(props(e).get("change"))).toList();
    }

    Integer announce(AppUser who, String code, String title, String text, LocalDateTime postedAt) {
        SchoolBbCourse course = data.bbCourse(who, code, code + " on Blackboard");
        data.announce(course, title, text, postedAt);
        return data.save(course);
    }

    // ---- Classes and exams ------------------------------------------------------------

    @Test
    void theCalendarFeedGivesClassesInVietnamTime() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);

        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END)
                        .with(user(an)))
                .andExpect(content().json("""
                        [{"title": "Web Application Development",
                          "start": "2026-09-29T08:00:00",
                          "end": "2026-09-29T10:30:00",
                          "classNames": ["event-class"],
                          "extendedProps": {"kind": "class", "code": "IT093IU", "room": "A2.508"}}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void theCalendarFeedHasOnlyMyClasses() throws Exception {
        data.course(data.user("binh@example.com"), "BA001IU", "Binh's Business Course", WEB_TUESDAY);

        assertThat(nextWeek()).isEmpty();
    }

    @Test
    void aClassEarlyOnMondayInVietnamBelongsToThatMonday() throws Exception {
        // Mon 05/10/2026 06:00 in Vietnam is still Sunday 04/10 23:00 in UTC.
        data.course(an, "MA001IU", "Early Maths",
                new Meeting(LocalDateTime.of(2026, 10, 4, 23, 0), LocalDateTime.of(2026, 10, 5, 0, 30), "A1.1"));

        assertThat(nextWeek()).isEmpty();
        assertThat(feed("2026-10-05T00:00:00Z", "2026-10-12T00:00:00Z"))
                .singleElement().extracting(e -> e.get("start")).isEqualTo("2026-10-05T06:00:00");
    }

    @Test
    void examsAreInTheCalendarWithTheirOwnColour() throws Exception {
        data.exam(an, "IT093IU", "Web Application Development", LocalDateTime.of(2026, 9, 30, 1, 0), "A1.101", "final");

        Map<String, Object> event = nextWeek().get(0);

        assertThat(List.of(event.get("title"), event.get("start"), event.get("classNames"))).containsExactly(
                "Final exam: Web Application Development", "2026-09-30T08:00:00", List.of("event-exam"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NONE", value = {
        "missing, NONE, NONE",
        "not-a-date, not-a-date, 2026-10-05",
        "end-before-start, 2026-10-05, 2026-09-28",
        "too-long, 2026-01-01, 2026-12-31"})
    void theCalendarFeedRefusesBadRanges(String name, String start, String end) throws Exception {
        var request = get("/school/api/calendar").with(user(an));
        if (start != null) {
            request.param("start", start).param("end", end);
        }

        mvc.perform(request).andExpect(status().isBadRequest());
    }

    @Test
    void theCalendarFeedNeedsLogin() throws Exception {
        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END))
                .andExpect(redirectedUrl("/auth/login"));
    }

    // ---- Blackboard deadlines ---------------------------------------------------------------

    List<Map<String, Object>> deadlines() throws Exception {
        return feed("2026-09-28", "2026-10-05").stream().filter(e -> props(e).get("kind").equals("due")).toList();
    }

    @Test
    void aDeadlineAt2359VietnamTimeIsInTheCalendarOnThatDay() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.save(data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null));

        Map<String, Object> due = deadlines().get(0);

        assertThat(List.of(due.get("start"), due.get("allDay"), due.get("classNames")))
                .containsExactly("2026-10-02", true, List.of("event-due"));
        assertThat(due.get("title")).isEqualTo("Due 23:59: Lab 3 · Web Application Development");
    }

    @Test
    void aDeadlineJustAfterMidnightBelongsToTheNextVietnamDay() throws Exception {
        // Sat 03/10 00:30 in Vietnam is still Fri 02/10 17:30 in UTC.
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.save(data.assign(course, "Quiz", LocalDateTime.of(2026, 10, 2, 17, 30), "not_graded", null, null, null));

        assertThat(deadlines()).extracting(e -> e.get("start")).containsExactly("2026-10-03");
    }

    @Test
    void doneDeadlinesGetACheckMarkInTheCalendar() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "needs_grading", null, null, null);
        data.save(data.assign(course, "Lab 4", LocalDateTime.of(2026, 10, 3, 16, 59), "not_graded", null, null, null));

        assertThat(deadlines()).extracting(e -> e.get("title")).containsExactlyInAnyOrder(
                "Due 23:59: Lab 4 · Web Application Development", "✓ Due 23:59: Lab 3 · Web Application Development");
    }

    // ---- Classes changed by Blackboard announcements ----------------------------------------

    @Test
    void anOnlineClassIsPurpleAndLinksToTheAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24, THU_01);
        Integer courseId = announce(an, "MA026IU", "ONLINE CLASS ON SEPTEMBER 24", "", LocalDateTime.of(2026, 9, 23, 16, 7));

        Map<String, Object> event = classes().get(0);

        assertThat(classes()).hasSize(1);
        assertThat(event.get("title")).isEqualTo("Online: " + PROBABILITY);
        assertThat(List.of(event.get("classNames"), props(event).get("room"), props(event).get("change")))
                .containsExactly(List.of("event-changed"), "Online", "online");
        assertThat(event.get("url")).isEqualTo("/school/courses/" + courseId);
        Map<String, Object> nextWeek = classes("2026-09-28", "2026-10-05").get(0);
        assertThat(nextWeek.get("classNames")).isEqualTo(List.of("event-class"));
        assertThat(nextWeek).doesNotContainKey("url");
    }

    @Test
    void aCancelledClassStaysInItsSlotGreyed() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", POSTED);

        Map<String, Object> event = classes().get(0);

        assertThat(List.of(event.get("title"), event.get("classNames"), event.get("start"))).containsExactly(
                "Cancelled: " + PROBABILITY, List.of("event-cancelled"), "2026-09-24T13:15:00");
        assertThat(props(event).get("room")).isEqualTo("A2.407");
    }

    @Test
    void aMakeUpClassWithATimeIsAdded() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on Saturday 26/9 from 8:00 to 9:40 in A2.401", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(
                List.of(e.get("title"), e.get("start"), e.get("end"), props(e).get("room"))).containsExactly(
                "Make-up: " + PROBABILITY, "2026-09-26T08:00:00", "2026-09-26T09:40:00", "A2.401"));
        assertThat(classes()).hasSize(2); // the normal Thursday class is still there
    }

    @Test
    void aMakeUpClassWithoutAnEndLastsAsLongAsTheUsualClass() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24, THU_01);
        announce(an, "MA026IU", "Make-up class on 26/9 at 13h15", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(List.of(e.get("start"), e.get("end")))
                .containsExactly("2026-09-26T13:15:00", "2026-09-26T15:45:00"));
    }

    @Test
    void aMakeUpClassEndingBeforeItStartsUsesTheUsualLength() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on 26/9 from 13:15 to 9:40", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(List.of(e.get("start"), e.get("end")))
                .containsExactly("2026-09-26T13:15:00", "2026-09-26T15:45:00"));
    }

    @Test
    void aMakeUpClassWithoutATimeIsAnAllDayNote() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Học bù ngày 26/9", "", POSTED);

        assertThat(classes().stream().filter(e -> Boolean.TRUE.equals(e.get("allDay"))).toList()).singleElement()
                .satisfies(note -> assertThat(List.of(note.get("title"), note.get("start"), note.get("classNames")))
                        .containsExactly("Make-up class: " + PROBABILITY + " (see announcement)", "2026-09-26",
                                List.of("event-changed")));
    }

    @Test
    void aMakeUpClassAtTheTimeOfAClassIsNotAddedTwice() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on 24/9 at 13:15", "", POSTED);

        assertThat(classes()).hasSize(1);
    }

    @Test
    void aMakeUpNoticeNamingTheCancelledDayKeepsItCancelled() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", LocalDateTime.of(2026, 9, 15, 0, 0));
        announce(an, "MA026IU", "The make-up for the class on September 24 will be on 3/10 at 8:00", "",
                LocalDateTime.of(2026, 9, 16, 0, 0));

        assertThat(classes()).extracting(e -> e.get("title"), e -> e.get("start"))
                .containsExactly(tuple("Cancelled: " + PROBABILITY, "2026-09-24T13:15:00"));
        assertThat(classes("2026-09-28", "2026-10-05")).extracting(e -> e.get("title"), e -> e.get("start"))
                .containsExactly(tuple("Make-up: " + PROBABILITY, "2026-10-03T08:00:00"));
    }

    @Test
    void changesNeedAClassOfThatCourseOnThatDay() throws Exception {
        // The real "Logistics Reminder": "Starting the week of 12/10, lectures will be taught online" is read as
        // Mon 12/10, and this Saturday course has no class that day.
        data.course(an, "IT007WE", "Skills for Communicating Information",
                new Meeting(LocalDateTime.of(2026, 10, 10, 6, 15), LocalDateTime.of(2026, 10, 10, 8, 45), "A1.603"),
                new Meeting(LocalDateTime.of(2026, 10, 17, 6, 15), LocalDateTime.of(2026, 10, 17, 8, 45), "A1.603"));
        announce(an, "IT007WE", "Logistics Reminder", "Our last in-person lecture is on 10/10. Starting the week of 12/10 , "
                + "lectures will be taught online by Dr. Nguyen Van A via MS Teams.", LocalDateTime.of(2026, 9, 22, 3, 59));

        assertThat(classes("2026-10-05", "2026-10-19")).extracting(e -> e.get("classNames"))
                .containsExactly(List.of("event-class"), List.of("event-class"));
    }

    @Test
    void aMakeUpForACourseNotInTheTimetableIsIgnored() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "EN011IU", "Make-up class on 26/9 at 8:00", "", POSTED);

        assertThat(classes()).hasSize(1);
    }

    @Test
    void anotherUsersAnnouncementsNeverChangeMyClasses() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(data.user("binh@example.com"), "MA026IU", "ONLINE CLASS ON SEPTEMBER 24", "", POSTED);

        assertThat(classes()).singleElement().extracting(e -> e.get("classNames")).isEqualTo(List.of("event-class"));
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B test -Dtest=CalendarFeedTest)`
Expected: `Tests run: 23, Failures: 21, Errors: 1, Skipped: 0`. With no `/school/api/calendar` yet, every logged-in request gets 404. Only `theCalendarFeedNeedsLogin` passes, because login is checked before the address.

- [ ] **Step 3: Add the calendar's queries**

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolClassMeetingRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolClassMeetingRepository extends JpaRepository<SchoolClassMeeting, Integer> {

    @Query("select m from SchoolClassMeeting m join fetch m.course c where c.userId = :userId and c.termCode = :termCode")
    List<SchoolClassMeeting> findTerm(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolClassMeeting m where m.course.id in "
            + "(select c.id from SchoolCourse c where c.userId = :userId and c.termCode = :termCode)")
    void deleteTerm(Integer userId, String termCode);

    /** Classes starting in [start, end), with their course, in time order. */
    @Query("select m from SchoolClassMeeting m join fetch m.course where m.userId = :userId "
            + "and m.startAt >= :start and m.startAt < :end order by m.startAt, m.id")
    List<SchoolClassMeeting> findStarting(Integer userId, LocalDateTime start, LocalDateTime end);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolExamRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Integer> {

    List<SchoolExam> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolExam e where e.userId = :userId and e.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    /** Exams starting in [start, end), in time order. */
    @Query("select e from SchoolExam e where e.userId = :userId and e.startAt >= :start and e.startAt < :end "
            + "order by e.startAt, e.id")
    List<SchoolExam> findStarting(Integer userId, LocalDateTime start, LocalDateTime end);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolCourseRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolCourseRepository extends JpaRepository<SchoolCourse, Integer> {

    boolean existsByUserIdAndTermCode(Integer userId, String termCode);

    /** Deletes a term's courses; delete their classes first. */
    @Modifying
    @Query("delete from SchoolCourse c where c.userId = :userId and c.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    /** The user's timetable courses with their classes. */
    @Query("select c from SchoolCourse c left join fetch c.meetings m where c.userId = :userId order by c.id, m.id")
    List<SchoolCourse> findWithMeetings(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAnnouncementRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAnnouncementRepository extends JpaRepository<SchoolBbAnnouncement, Integer> {

    List<SchoolBbAnnouncement> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAnnouncement a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    @Query("select a from SchoolBbAnnouncement a join fetch a.course where a.userId = :userId")
    List<SchoolBbAnnouncement> findWithCourse(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAssignmentRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAssignmentRepository extends JpaRepository<SchoolBbAssignment, Integer> {

    List<SchoolBbAssignment> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAssignment a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    /** Deadlines in [start, end), soonest first, with their course. */
    @Query("select a from SchoolBbAssignment a join fetch a.course where a.userId = :userId "
            + "and a.dueAt >= :start and a.dueAt < :end order by a.dueAt, a.id")
    List<SchoolBbAssignment> findDue(Integer userId, LocalDateTime start, LocalDateTime end);
}
```

- [ ] **Step 4: Write the schedule and the feed**

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/Schedule.java`:

```java
package vn.edu.hcmiu.sla.school.schedule;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncementRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignmentRepository;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeeting;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeetingRepository;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
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

    public static final Map<String, String> EXAM_LABELS = Map.of(
            "final", "Final exam", "midterm", "Midterm exam", "other", "Exam");
    static final Duration DEFAULT_CLASS_LENGTH = Duration.ofMinutes(90); // a make-up of a course with no known classes

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

    /** A timetable course's name and the usual length of its classes. */
    record CourseInfo(String name, Duration usual) {
    }

    private record CourseDay(String code, LocalDate day) {
    }

    private final SchoolClassMeetingRepository meetings;
    private final SchoolExamRepository exams;
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
    @Transactional(readOnly = true)
    public List<Item> itemsBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> items = new ArrayList<>();
        for (SchoolClassMeeting m : meetings.findStarting(userId, startUtc, endUtc)) {
            items.add(new Item("class", m.getStartAt(), m.getEndAt(), m.getCourse().getCourseCode(),
                    m.getCourse().getCourseName(), m.getRoom(), null, null, null, false));
        }
        for (SchoolExam e : exams.findStarting(userId, startUtc, endUtc)) {
            LocalDateTime end = e.getDurationMin() == null ? null : e.getStartAt().plusMinutes(e.getDurationMin());
            items.add(new Item("exam", e.getStartAt(), end, e.getCourseCode(), e.getCourseName(), e.getRoom(),
                    EXAM_LABELS.getOrDefault(e.getExamType(), "Exam"), null, null, false));
        }
        return withChanges(items, announcedChanges(userId), timetableCourses(userId), startUtc, endUtc);
    }

    /** The items of one Vietnam day. */
    public List<Item> itemsOn(Integer userId, LocalDate day) {
        return itemsBetween(userId, VietnamTime.dayStart(day), VietnamTime.dayStart(day.plusDays(1)));
    }

    /** Blackboard deadlines in [startUtc, endUtc), soonest first, with their course. */
    public List<SchoolBbAssignment> deadlinesBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
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
    private Map<String, CourseInfo> timetableCourses(Integer userId) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Map<Duration, Integer>> lengths = new LinkedHashMap<>();
        for (SchoolCourse course : courses.findWithMeetings(userId)) {
            names.putIfAbsent(course.getCourseCode(), course.getCourseName());
            for (SchoolClassMeeting m : course.getMeetings()) {
                lengths.computeIfAbsent(course.getCourseCode(), code -> new LinkedHashMap<>())
                        .merge(Duration.between(m.getStartAt(), m.getEndAt()), 1, Integer::sum);
            }
        }
        Map<String, CourseInfo> result = new LinkedHashMap<>();
        names.forEach((code, name) -> {
            Duration usual = DEFAULT_CLASS_LENGTH;
            int best = 0;
            for (Map.Entry<Duration, Integer> length : lengths.getOrDefault(code, Map.of()).entrySet()) {
                if (length.getValue() > best) {
                    usual = length.getKey();
                    best = length.getValue();
                }
            }
            result.put(code, new CourseInfo(name, usual));
        });
        return result;
    }

    /**
     * Marks announced online and cancelled classes, and adds make-up classes in [startUtc, endUtc). No make-up
     * is added on a day the course has a class: such a date names the original class, as in "the make-up for
     * the class on 24/9".
     */
    static List<Item> withChanges(List<Item> items, Map<Slot, ClassChange> changes, Map<String, CourseInfo> courses,
            LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> result = new ArrayList<>();
        Set<CourseDay> classDays = new HashSet<>();
        for (Item item : items) {
            if (item.kind().equals("class")) {
                LocalDate day = VietnamTime.date(item.startAt());
                classDays.add(new CourseDay(item.code(), day));
                ClassChange change = changes.get(new Slot(item.code(), day, "class"));
                if (change != null && (change.kind().equals("online") || change.kind().equals("cancelled"))) {
                    item = item.changed(change.kind(), change.bbCourseId(),
                            change.kind().equals("online") ? "Online" : item.room());
                }
            }
            result.add(item);
        }
        for (ClassChange change : changes.values()) {
            if (!change.kind().equals("makeup") || !courses.containsKey(change.code())
                    || classDays.contains(new CourseDay(change.code(), change.day()))) {
                continue;
            }
            CourseInfo course = courses.get(change.code());
            if (change.start() == null) {
                LocalDateTime dayStart = VietnamTime.dayStart(change.day());
                if (!dayStart.isBefore(startUtc) && dayStart.isBefore(endUtc)) {
                    result.add(new Item("class", dayStart, null, change.code(), course.name(), change.room(), null,
                            "makeup", change.bbCourseId(), true));
                }
                continue;
            }
            LocalDateTime startAt = VietnamTime.utc(change.day(), change.start());
            LocalDateTime endAt = change.end() != null && change.end().isAfter(change.start())
                    ? VietnamTime.utc(change.day(), change.end())
                    : startAt.plus(course.usual());
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                result.add(new Item("class", startAt, endAt, change.code(), course.name(), change.room(), null,
                        "makeup", change.bbCourseId(), false));
            }
        }
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
        return result;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/schedule/CalendarController.java`:

```java
package vn.edu.hcmiu.sla.school.schedule;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * Events for the Timetable page's calendar (FullCalendar). Times are Vietnam wall-clock times without an
 * offset, so the calendar shows Vietnam time on any device.
 */
@RestController
public class CalendarController {

    static final int MAX_CALENDAR_DAYS = 62; // a month view asks for about 6 weeks
    static final Map<String, String> CHANGE_TITLES = Map.of(
            "online", "Online", "makeup", "Make-up", "cancelled", "Cancelled");
    static final Set<String> DONE = Set.of("needs_grading", "graded", "exempt");

    private final Schedule schedule;

    public CalendarController(Schedule schedule) {
        this.schedule = schedule;
    }

    /** start and end are Vietnam dates (end exclusive); FullCalendar sends "2026-09-28T00:00:00Z". */
    @GetMapping("/school/api/calendar")
    ResponseEntity<Object> feed(@AuthenticationPrincipal AppUser user,
            @RequestParam(required = false) String start, @RequestParam(required = false) String end) {
        LocalDate from = day(start);
        LocalDate to = day(end);
        if (from == null || to == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "start and end dates are required"));
        }
        if (!from.isBefore(to) || ChronoUnit.DAYS.between(from, to) > MAX_CALENDAR_DAYS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "the range must be 1 to " + MAX_CALENDAR_DAYS + " days"));
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Item item : schedule.itemsBetween(user.id(), VietnamTime.dayStart(from), VietnamTime.dayStart(to))) {
            events.add(event(item));
        }
        for (SchoolBbAssignment deadline : schedule.deadlinesBetween(user.id(), VietnamTime.dayStart(from),
                VietnamTime.dayStart(to))) {
            events.add(deadline(deadline));
        }
        return ResponseEntity.ok(events);
    }

    /** The date at the start of "2026-09-28T00:00:00Z" or "2026-09-28", or null. */
    private static LocalDate day(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    static Map<String, Object> event(Item item) {
        String title = item.label() != null ? item.label() + ": " + item.title() : item.title();
        String css = "event-" + item.kind();
        if (item.change() != null) {
            title = CHANGE_TITLES.get(item.change()) + ": " + title;
            css = item.change().equals("cancelled") ? "event-cancelled" : "event-changed";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", item.kind());
        props.put("code", item.code());
        props.put("room", item.room());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", item.allDay() ? "Make-up class: " + item.title() + " (see announcement)" : title);
        event.put("start", VietnamTime.wallClock(item.startAt()));
        event.put("classNames", List.of(css));
        event.put("extendedProps", props);
        if (item.allDay()) {
            event.put("start", VietnamTime.date(item.startAt()).toString());
            event.put("allDay", true);
        } else if (item.endAt() != null) {
            event.put("end", VietnamTime.wallClock(item.endAt()));
        }
        if (item.change() != null) {
            props.put("change", item.change());
        }
        if (item.bbCourseId() != null) {
            event.put("url", "/school/courses/" + item.bbCourseId());
        }
        return event;
    }

    static Map<String, Object> deadline(SchoolBbAssignment deadline) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "due");
        props.put("code", deadline.getCourse().getCourseCode());
        props.put("room", null);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", (DONE.contains(deadline.getStatus()) ? "✓ " : "") + "Due "
                + VietnamTime.clock(deadline.getDueAt()) + ": " + deadline.getName() + " · "
                + deadline.getCourse().getName());
        event.put("start", VietnamTime.date(deadline.getDueAt()).toString());
        event.put("allDay", true);
        event.put("classNames", List.of("event-due"));
        event.put("extendedProps", props);
        return event;
    }
}
```

- [ ] **Step 5: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest=CalendarFeedTest)`
Expected: `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 264, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/schedule web/src/main/java/vn/edu/hcmiu/sla/school/model web/src/test/java/vn/edu/hcmiu/sla/school/SchoolTestData.java web/src/test/java/vn/edu/hcmiu/sla/school/schedule/CalendarFeedTest.java
git commit -m "feat(web): the timetable's calendar feed in Java, with classes changed by announcements"
```

---

### Task 4: The School pages

The Overview, Timetable, Courses, a course's page, Exams, Tuition and "Sync now", ported from `app/school/routes.py` and its Jinja templates to a controller and Thymeleaf templates. `SchoolModule` turns on the School link in the menu and on the dashboard. `SchoolFormat` gives templates the Python site's filters. `ClockConfig` gives the pages their clock; `TestClock` sets it in tests. `SchoolPagesTest` twins the page cases of `tests/test_school_pages.py`, `test_school_schedule_pages.py`, `test_school_blackboard_pages.py` and `test_school_class_change_pages.py`, plus a check that "Sync now" needs its CSRF token.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/core/ClockConfig.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/SchoolModule.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/CourseCard.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SchoolFormat.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SchoolController.java`, `web/src/main/resources/templates/school/fragments.html`, `index.html`, `timetable.html`, `courses.html`, `course.html`, `exams.html`, `tuition.html`, `web/src/main/resources/static/js/timetable.js` (copied)
- Modify (replaced): `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncRuns.java`, and in `web/src/main/java/vn/edu/hcmiu/sla/school/model/`: `SchoolExamRepository.java`, `SchoolBbAnnouncementRepository.java`, `SchoolBbAssignmentRepository.java`, `SchoolBbCourseRepository.java`, `SchoolBbMaterialRepository.java`, `SchoolChangeRepository.java`, `SchoolSyncRunRepository.java`, `SchoolSyncDeviceRepository.java`, `SchoolTuitionRepository.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/TestClock.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java`

**Interfaces:**
- Consumes: `SyncStatus` and `VietnamTime` (Task 1); `Schedule` and `SchoolTestData` (Task 3); `SyncRuns.settings`, `latestRun` and `DeviceKeys.create` (stage 2a); `Flash`, `NavModule` and `AppUser` (stage 1).
- Produces:
  - `Clock` bean (UTC); `TestClock` (`set(LocalDateTime utc)`, `reset()`, used with `@Import(TestClock.Config.class)`).
  - `SchoolFormat` bean `schoolFormat`: `when(LocalDateTime)` ("never" for none), `clock(LocalDateTime)`, `date(LocalDateTime)`, `day(LocalDate)`, `dayLabel(LocalDate)`, `money(long)`, `score(Double)`, `grade(SchoolBbAssignment)`, `changeLabel(String)`, `preview(String)`.
  - `Changes.number(double)` and `Changes.grade(Double score, Double pointsPossible, String gradeText)` become public.
  - `SyncRuns.requestSync(Integer userId, LocalDateTime now)`.
  - Repositories: `SchoolExamRepository` next, upcoming and past exams; `SchoolBbAnnouncementRepository.findLatest(userId, Limit)`, `findByCourseIdOrderById`; `SchoolBbAssignmentRepository.findToSubmit(userId, since)`, `findByCourseIdOrderById`; `SchoolBbCourseRepository.findByIdAndUserId`, `findCards(userId)` → `List<CourseCard>`; `SchoolBbMaterialRepository.findByCourseIdOrderById`; `SchoolChangeRepository.findTop10ByUserIdOrderByIdDesc`; `SchoolSyncRunRepository.findTop10ByUserIdOrderByStartedAtDescIdDesc`; `SchoolSyncDeviceRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc`; `SchoolTuitionRepository.findByUserIdOrderByTermCodeDesc`.
  - Pages: `GET /school` (and `/school/`), `/school/timetable`, `/school/courses`, `/school/courses/{id}`, `/school/exams`, `/school/tuition`; `POST /school/sync-now` → redirect to `/school`.
  - Template fragments in `school/fragments.html`: `subnav(current)`, `status-card` (needs `status` and `systemLines`), `item(item)`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/TestClock.java`:

```java
package vn.edu.hcmiu.sla.school;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The site's clock in page tests: the real time until a test sets one. Use it with
 * {@code @Import(TestClock.Config.class)} and {@code @Autowired TestClock clock}; call {@link #reset} after.
 */
public class TestClock extends Clock {

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private Instant fixed;

    /** From now on the site's time is this UTC time. */
    public void set(LocalDateTime utc) {
        fixed = utc.toInstant(ZoneOffset.UTC);
    }

    public void reset() {
        fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return fixed != null ? fixed : Instant.now();
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * The School pages: Java twin of the page tests in tests/test_school_pages.py, test_school_schedule_pages.py,
 * test_school_blackboard_pages.py and test_school_class_change_pages.py. Times in the database are UTC;
 * pages show Vietnam time (UTC+7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class SchoolPagesTest {

    // Tue 29/09/2026 08:00-10:30 in Vietnam = 01:00-03:30 UTC
    static final Meeting WEB_TUESDAY = new Meeting(LocalDateTime.of(2026, 9, 29, 1, 0),
            LocalDateTime.of(2026, 9, 29, 3, 30), "A2.508");
    static final String BB = SchoolTestData.BB;

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    DeviceKeys deviceKeys;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url).with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The HTML from a heading to the next one. */
    static String section(String html, String heading) {
        int start = html.indexOf("<h2>" + heading + "</h2>");
        int end = html.indexOf("<h2>", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    /** The text in each element with this class, tags removed and spaces tidied. */
    static List<String> texts(String html, String cssClass) {
        List<String> texts = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"" + cssClass + "\"[^>]*>(.*?)</span>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            texts.add(m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip());
        }
        return texts;
    }

    /** The opening tag of the first link to this address. */
    static String linkTo(String html, String href) {
        Matcher m = Pattern.compile("<a [^>]*href=\"" + Pattern.quote(href) + "\"[^>]*>").matcher(html);
        return m.find() ? m.group() : "";
    }

    void change(AppUser who, String section, String kind, String summary) {
        SchoolSyncRun run = new SchoolSyncRun(who.id(), null, "manual", LocalDateTime.of(2026, 9, 28, 0, 0));
        db.persist(run);
        db.persist(new SchoolChange(who.id(), run.getId(), section, kind, summary, LocalDateTime.of(2026, 9, 28, 0, 0)));
        db.flush();
    }

    // ---- Login and status ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/school", "/school/", "/school/timetable", "/school/courses", "/school/exams",
        "/school/tuition", "/school/devices"})
    void schoolPagesNeedLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void schoolHomeShowsTheSetupHintBeforeAnyDevice() throws Exception {
        assertThat(page("/school")).contains("Devices page");
    }

    @Test
    void anotherUserLoggingInSeesTheirOwnStatus() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        AppUser binh = data.user("binh@example.com");

        assertThat(mvc.perform(get("/school").with(user(binh))).andReturn().getResponse().getContentAsString())
                .contains("Not set up yet");
    }

    @Test
    void schoolHomeShowsALinePerSystem() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled", LocalDateTime.of(2026, 9, 28, 1, 0));
        run.finish(SchoolSyncRun.PARTIAL, LocalDateTime.of(2026, 9, 28, 1, 5), null, null);
        run.setSections(Map.of("timetable", Map.of("status", "ok"), "exams", Map.of("status", "ok"),
                "tuition", Map.of("status", "ok"),
                "blackboard", Map.of("status", "failed", "error_code", "bad_credentials", "error_message", "rejected")));
        db.persist(run);
        db.flush();

        String html = page("/school");

        assertThat(html).contains("EduSoft:", "Blackboard:", "sla-agent setup --blackboard");
    }

    @Test
    void syncNowMakesTheNextCheckDue() throws Exception {
        String key = deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
        // A failed run 10 minutes ago, past the 5-minute gap.
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        run.finish(SchoolSyncRun.FAILED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(9), "network", "EduSoft timed out");
        db.persist(run);
        db.flush();

        mvc.perform(post("/school/sync-now").with(user(an)).with(csrf())).andExpect(redirectedUrl("/school"));

        mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.reason").value("requested"));
        assertThat(page("/school")).contains("waiting for your laptop");
    }

    @Test
    void syncNowNeedsTheFormsSecurityCode() throws Exception {
        mvc.perform(post("/school/sync-now").with(user(an))).andExpect(status().isForbidden());
    }

    // ---- Overview ------------------------------------------------------------------------

    @Test
    void schoolHomeShowsTodaysClassesAndWhatChanged() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        change(an, "timetable", "changed", "IT093IU Web Application Development: room A2.307 → A2.508");
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30)); // Tue 07:30 in Vietnam

        String html = page("/school");

        assertThat(html).contains("Today", "Tue 29/09", "08:00–10:30", "room A2.307 → A2.508");
    }

    @Test
    void schoolHomeDoesNotShowOtherUsersChanges() throws Exception {
        change(data.user("binh@example.com"), "exams", "added", "Binh's secret exam");

        assertThat(page("/school")).doesNotContain("Binh");
    }

    @Test
    void overviewShowsThe3LatestAnnouncements() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        for (int i = 0; i < 5; i++) {
            data.announce(course, "Note " + i, "t", LocalDateTime.of(2026, 9, 20 + i, 2, 0));
        }
        data.save(course);

        String html = page("/school");

        assertThat(html).contains("Note 4", "Note 2").doesNotContain("Note 1");
    }

    @Test
    void toSubmitListsWhatIHaventSubmittedWithALink() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Final report", LocalDateTime.of(2026, 11, 30, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Missed quiz", LocalDateTime.of(2026, 9, 25, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Long gone", LocalDateTime.of(2026, 9, 1, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Handed in", LocalDateTime.of(2026, 10, 3, 16, 59), "needs_grading", null, null, null);
        data.assign(course, "Marked", LocalDateTime.of(2026, 10, 4, 16, 59), "graded", 9.0, null, null);
        data.assign(course, "Excused", LocalDateTime.of(2026, 10, 5, 16, 59), "exempt", null, null, null);
        data.assign(course, "Attendance", null, "not_graded", null, null, null);
        data.save(course);
        SchoolBbCourse binhs = data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course");
        data.save(data.assign(binhs, "Binh's lab", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null));
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30));

        String toSubmit = section(page("/school"), "To submit");

        assertThat(texts(toSubmit, "item-title")).containsExactly("Missed quiz", "Lab 3", "Final report");
        String[] rows = toSubmit.split("<li ");
        assertThat(rows[1]).contains("Overdue");
        assertThat(rows[2]).doesNotContain("Overdue");
        assertThat(linkTo(toSubmit, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(toSubmit.split("Open assignment ↗", -1)).hasSize(4);
    }

    @Test
    void toSubmitSaysWhenNothingIsLeft() throws Exception {
        assertThat(page("/school")).contains("Nothing left to submit.");
    }

    @Test
    void theOverviewShowsTodaysOnlineClass() throws Exception {
        data.course(an, "MA026IU", "Probability, Statistic & Random Process",
                new Meeting(LocalDateTime.of(2026, 9, 24, 6, 15), LocalDateTime.of(2026, 9, 24, 8, 45), "A2.407"));
        SchoolBbCourse course = data.bbCourse(an, "MA026IU", "MA026IU on Blackboard");
        Integer courseId = data.save(data.announce(course, "ONLINE CLASS ON SEPTEMBER 24", "",
                LocalDateTime.of(2026, 9, 23, 16, 7)));
        clock.set(LocalDateTime.of(2026, 9, 24, 0, 30)); // Thu 07:30 in Vietnam

        String html = page("/school");

        Matcher item = Pattern.compile("<li class=\"item item-class item-changed\">(.*?)</li>", Pattern.DOTALL).matcher(html);
        assertThat(item.find()).isTrue();
        assertThat(item.group(1)).contains("<span class=\"badge badge-changed\">Online</span>");
        assertThat(item.group(1)).contains("<a href=\"/school/courses/" + courseId + "\">See announcement</a>");
    }

    // ---- Timetable, exams, tuition --------------------------------------------------------

    @Test
    void theTimetablePageLoadsThePinnedCalendarScript() throws Exception {
        String html = page("/school/timetable");

        assertThat(html).contains("id=\"calendar\"", "data-feed=\"/school/api/calendar\"",
                "fullcalendar@6.1.21/index.global.min.js", "integrity=\"sha384-", "src=\"/js/timetable.js\"");
    }

    @Test
    void theTimetableLegendExplainsTheNewColours() throws Exception {
        assertThat(page("/school/timetable")).contains("Online / make-up", "Cancelled");
    }

    @Test
    void theExamsPageListsUpcomingExams() throws Exception {
        data.exam(an, "IT093IU", "Web Application Development", LocalDateTime.of(2099, 12, 12, 1, 0), "A1.309", "final");

        assertThat(page("/school/exams")).contains("Web Application Development", "12/12/2099", "08:00", "A1.309");
    }

    @Test
    void theExamsPageSaysWhenNothingIsPublished() throws Exception {
        assertThat(page("/school/exams")).contains("No exams published yet");
    }

    @Test
    void theTuitionPageShowsBalanceAndDueDate() throws Exception {
        data.tuition(an, 12_500_000, LocalDate.of(2026, 10, 15), "Chưa đóng");

        assertThat(page("/school/tuition")).contains("12,500,000", "15/10/2026", "Chưa đóng");
    }

    @Test
    void theTuitionPageLinksToTheIuPaymentSiteInANewTab() throws Exception {
        assertThat(linkTo(page("/school/tuition"), "https://iupay.hcmiu.edu.vn/search/dhqt"))
                .contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
    }

    @Test
    void theTuitionPageSaysWhenNothingIsSynced() throws Exception {
        assertThat(page("/school/tuition")).contains("No tuition information yet");
    }

    // ---- Courses ---------------------------------------------------------------------------

    @Test
    void theCoursesPageListsOnlyMyCourses() throws Exception {
        data.save(data.bbCourse(an, "IT093IU", "Web Application Development"));
        data.save(data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course"));

        assertThat(page("/school/courses")).contains("Web Application Development").doesNotContain("Binh");
    }

    @Test
    void someoneElsesCoursePageIs404() throws Exception {
        Integer other = data.save(data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course"));

        mvc.perform(get("/school/courses/" + other).with(user(an))).andExpect(status().isNotFound());
    }

    @Test
    void theCoursePageShowsAllFourPartsWithEscapedText() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.announce(course, "Heads up", "<script>alert(1)</script> No class Thursday", LocalDateTime.of(2026, 9, 28, 2, 0));
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "graded", 8.5, 10.0, "Good work");
        data.material(course, "Week 5 slides.pdf", "file", "Week 5", LocalDateTime.of(2026, 9, 28, 1, 0));
        Integer courseId = data.save(course);

        String html = page("/school/courses/" + courseId);

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
        assertThat(html).contains("Heads up", "Lab 3", "Fri 02/10 23:59", "8.5/10", "Good work", "Week 5 slides.pdf");
        assertThat(linkTo(html, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(html.split("Open in Blackboard ↗", -1)).hasSize(5); // the course and its three items
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B test -Dtest=SchoolPagesTest)`
Expected: `Tests run: 28, Failures: 19, Errors: 0, Skipped: 0`. With no School pages yet, every logged-in page gets 404. Nine tests already pass for reasons that don't need the pages: the seven `schoolPagesNeedLogin` cases and `syncNowNeedsTheFormsSecurityCode` (Spring Security answers first), and `someoneElsesCoursePageIs404` (no page at all is also a 404; after Step 5 it is 404 because the course isn't yours).

- [ ] **Step 3: The clock, the menu entry and the template helpers**

`web/src/main/java/vn/edu/hcmiu/sla/core/ClockConfig.java`:

```java
package vn.edu.hcmiu.sla.core;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The site's clock, in UTC. Pages ask it for "now" ({@code LocalDateTime.now(clock)}) instead of the
 * computer's clock, so a test can set the time with its own {@code @Primary} Clock bean.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/SchoolModule.java`:

```java
package vn.edu.hcmiu.sla.school;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.hcmiu.sla.core.NavModule;

/** Puts School in the menu and on the dashboard. */
@Configuration
class SchoolModule {

    @Bean
    NavModule schoolMenu() {
        return new NavModule("School", "/school");
    }
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java` with (`number` and `grade` become public, for the templates):

```java
package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import vn.edu.hcmiu.sla.school.VietnamTime;

/**
 * The "What changed" feed: compare old and new data, describe the differences. The Java twin of
 * app/school/services/changes.py, with the same wording.
 *
 * <p>Times are UTC as stored and shown in Vietnam time. Only upcoming classes and exams count, so weeks
 * that are simply over aren't reported.
 */
public final class Changes {

    private Changes() {
    }

    static final int MAX_DATES = 3;
    static final int MATERIALS_NAMED = 3; // titles named in a "new materials" line

    /** One feed line. kind is added, removed or changed. */
    public record Change(String kind, String summary) {
    }

    public record Meeting(String courseCode, String courseName, LocalDateTime startAt, LocalDateTime endAt,
            String room) {
    }

    public record ExamInfo(String courseCode, String courseName, String examType, LocalDateTime startAt,
            String room) {
    }

    public record TuitionInfo(String termCode, long balance, LocalDate dueDate, String statusText) {
    }

    /** An announcement, assignment or material, reduced to what the feed compares. */
    public record BbItem(String kind, String course, String bbId, String title, LocalDateTime dueAt, String status,
            Double score, Double pointsPossible, String gradeText, String materialKind) {

        public static BbItem announcement(String course, String bbId, String title) {
            return new BbItem("announcement", course, bbId, title, null, null, null, null, null, null);
        }

        public static BbItem assignment(String course, String bbId, String title, LocalDateTime dueAt, String status,
                Double score, Double pointsPossible, String gradeText) {
            return new BbItem("assignment", course, bbId, title, dueAt, status, score, pointsPossible, gradeText, null);
        }

        public static BbItem material(String course, String bbId, String title, String materialKind) {
            return new BbItem("material", course, bbId, title, null, null, null, null, null, materialKind);
        }
    }

    /** What Blackboard held: the course names and everything in them. */
    public record BbState(List<String> courses, List<BbItem> items) {
    }

    /** "Tue 29/09 08:00" in Vietnam time. */
    private static String when(LocalDateTime utc) {
        return VietnamTime.when(utc);
    }

    private static String dates(Collection<LocalDateTime> moments) {
        List<LocalDateTime> sorted = moments.stream().sorted().toList();
        List<String> shown = sorted.stream().limit(MAX_DATES).map(Changes::when).toList();
        String text = String.join(", ", shown);
        if (sorted.size() > MAX_DATES) {
            text += " and " + (sorted.size() - MAX_DATES) + " more";
        }
        return text;
    }

    private static String count(long n, String singular) {
        return count(n, singular, singular + "s");
    }

    private static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static String day(LocalDate value) {
        return VietnamTime.fullDate(value);
    }

    private static String money(long vnd) {
        return String.format(Locale.ROOT, "%,d", vnd);
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static boolean upcoming(LocalDateTime start, LocalDateTime now) {
        return !start.isBefore(now);
    }

    // ---- Timetable --------------------------------------------------------------

    private record MeetingKey(String courseCode, LocalDateTime startAt) {
    }

    private record Kept(Meeting before, Meeting after) {
    }

    private record RoomMove(String from, String to) {
    }

    private static Map<MeetingKey, Meeting> upcomingMeetings(List<Meeting> meetings, LocalDateTime now) {
        Map<MeetingKey, Meeting> byKey = new LinkedHashMap<>();
        for (Meeting m : meetings) {
            if (upcoming(m.startAt(), now)) {
                byKey.put(new MeetingKey(m.courseCode(), m.startAt()), m);
            }
        }
        return byKey;
    }

    /** old is null on the first sync of a term (or while it had no classes). */
    public static List<Change> timetable(List<Meeting> old, List<Meeting> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing to announce yet
            }
            long courses = fresh.stream().map(Meeting::courseCode).distinct().count();
            long upcoming = fresh.stream().filter(m -> upcoming(m.startAt(), now)).count();
            return List.of(new Change("added", "Timetable loaded: " + count(courses, "course") + ", "
                    + count(upcoming, "upcoming class", "upcoming classes")));
        }

        Map<MeetingKey, Meeting> before = upcomingMeetings(old, now);
        Map<MeetingKey, Meeting> after = upcomingMeetings(fresh, now);
        Map<String, String> labels = new TreeMap<>();
        for (Meeting m : before.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }
        for (Meeting m : after.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }

        List<Change> changes = new ArrayList<>();
        labels.forEach((code, label) -> {
            List<LocalDateTime> removed = new ArrayList<>();
            List<Meeting> added = new ArrayList<>();
            List<Kept> kept = new ArrayList<>();
            before.forEach((key, meeting) -> {
                if (key.courseCode().equals(code)) {
                    if (after.containsKey(key)) {
                        kept.add(new Kept(meeting, after.get(key)));
                    } else {
                        removed.add(key.startAt());
                    }
                }
            });
            after.forEach((key, meeting) -> {
                if (key.courseCode().equals(code) && !before.containsKey(key)) {
                    added.add(meeting);
                }
            });

            if (!removed.isEmpty()) {
                String what = removed.size() == 1 ? "class cancelled" : removed.size() + " classes cancelled";
                changes.add(new Change("removed", label + ": " + what + " on " + dates(removed)));
            }

            if (added.size() == 1) {
                Meeting m = added.get(0);
                String room = m.room() == null || m.room().isEmpty() ? "" : " (" + m.room() + ")";
                changes.add(new Change("added", label + ": new class on " + when(m.startAt()) + room));
            } else if (!added.isEmpty()) {
                changes.add(new Change("added", label + ": " + added.size() + " new classes on "
                        + dates(added.stream().map(Meeting::startAt).toList())));
            }

            Map<RoomMove, List<LocalDateTime>> roomMoves = new LinkedHashMap<>();
            for (Kept pair : kept) {
                if (!Objects.equals(pair.before().room(), pair.after().room())) {
                    roomMoves.computeIfAbsent(new RoomMove(pair.before().room(), pair.after().room()),
                            move -> new ArrayList<>()).add(pair.after().startAt());
                }
            }
            roomMoves.entrySet().stream()
                    .sorted(Comparator.comparing(move -> Collections.min(move.getValue())))
                    .forEach(move -> changes.add(new Change("changed", label + ": room "
                            + orElse(move.getKey().from(), "?") + " → " + orElse(move.getKey().to(), "?")
                            + " on " + dates(move.getValue()))));

            kept.stream()
                    .sorted(Comparator.comparing(pair -> pair.after().startAt()))
                    .filter(pair -> !pair.before().endAt().equals(pair.after().endAt()))
                    .forEach(pair -> changes.add(new Change("changed", label + ": class on "
                            + when(pair.after().startAt()) + " now ends at "
                            + VietnamTime.clock(pair.after().endAt()))));
        });
        return changes;
    }

    // ---- Exams ------------------------------------------------------------------

    private record ExamKey(String courseCode, String examType) {
    }

    private static Map<ExamKey, ExamInfo> upcomingExams(List<ExamInfo> exams, LocalDateTime now) {
        Map<ExamKey, ExamInfo> byKey = new LinkedHashMap<>();
        for (ExamInfo e : exams) {
            if (upcoming(e.startAt(), now)) {
                byKey.put(new ExamKey(e.courseCode(), e.examType()), e);
            }
        }
        return byKey;
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word
                : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** old is null on the first sync of a term (or while no exams were published). */
    public static List<Change> exams(List<ExamInfo> old, List<ExamInfo> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing published yet; announced once exams appear
            }
            return List.of(new Change("added", "Exam schedule loaded: " + count(fresh.size(), "exam")));
        }

        Map<ExamKey, ExamInfo> before = upcomingExams(old, now);
        Map<ExamKey, ExamInfo> after = upcomingExams(fresh, now);
        Set<ExamKey> keys = new TreeSet<>(Comparator.comparing(ExamKey::courseCode).thenComparing(ExamKey::examType));
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        List<Change> changes = new ArrayList<>();
        for (ExamKey key : keys) {
            ExamInfo oldExam = before.get(key);
            ExamInfo newExam = after.get(key);
            ExamInfo exam = newExam != null ? newExam : oldExam;
            String label = exam.courseCode() + " " + exam.courseName();
            String type = exam.examType();
            if (oldExam == null) {
                String room = newExam.room() == null || newExam.room().isEmpty() ? "" : " (" + newExam.room() + ")";
                changes.add(new Change("added", "New " + type + " exam: " + label + ", " + when(newExam.startAt()) + room));
            } else if (newExam == null) {
                changes.add(new Change("removed", capitalize(type) + " exam removed: " + label));
            } else {
                if (!oldExam.startAt().equals(newExam.startAt())) {
                    changes.add(new Change("changed", label + " " + type + " exam moved: "
                            + when(oldExam.startAt()) + " → " + when(newExam.startAt())));
                }
                if (!Objects.equals(oldExam.room(), newExam.room())) {
                    changes.add(new Change("changed", label + " " + type + " exam room: "
                            + orElse(oldExam.room(), "?") + " → " + orElse(newExam.room(), "?")));
                }
            }
        }
        return changes;
    }

    // ---- Tuition ----------------------------------------------------------------

    /** old is null on the first sync of a term. */
    public static List<Change> tuition(TuitionInfo old, TuitionInfo fresh) {
        String title = "Tuition " + fresh.termCode();
        if (old == null) {
            String due = fresh.dueDate() == null ? "" : ", due " + day(fresh.dueDate());
            return List.of(new Change("added", title + ": balance " + money(fresh.balance()) + " VND" + due));
        }

        List<Change> changes = new ArrayList<>();
        if (old.balance() != fresh.balance()) {
            changes.add(new Change("changed", title + ": balance " + money(old.balance()) + " → "
                    + money(fresh.balance()) + " VND"));
        }
        if (!Objects.equals(old.dueDate(), fresh.dueDate())) {
            String text;
            if (old.dueDate() == null) {
                text = "due date " + day(fresh.dueDate());
            } else if (fresh.dueDate() == null) {
                text = "due date removed";
            } else {
                text = "due date " + day(old.dueDate()) + " → " + day(fresh.dueDate());
            }
            changes.add(new Change("changed", title + ": " + text));
        }
        if (!Objects.equals(old.statusText(), fresh.statusText())) {
            changes.add(new Change("changed", title + ": status " + orElse(old.statusText(), "-") + " → "
                    + orElse(fresh.statusText(), "-")));
        }
        return changes;
    }

    // ---- Blackboard -------------------------------------------------------------

    private record ItemKey(String kind, String bbId) {
    }

    /** Like Python's f"{value:g}": 6 significant digits, no trailing zeros (8.5, 10, 6.66667, 1e+06). */
    public static String number(double value) {
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(6, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (rounded.signum() != 0 && (exponent < -4 || exponent >= 6)) {
            String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
            return mantissa + String.format(Locale.ROOT, "e%+03d", exponent);
        }
        return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
    }

    /** "8.5/10", "8.5", the grade's text, or "graded". */
    public static String grade(Double score, Double pointsPossible, String gradeText) {
        if (score != null && pointsPossible != null && pointsPossible != 0) {
            return number(score) + "/" + number(pointsPossible);
        }
        if (score != null) {
            return number(score);
        }
        return orElse(gradeText, "graded");
    }

    private static String grade(BbItem item) {
        return grade(item.score(), item.pointsPossible(), item.gradeText());
    }

    private static String bbCounts(List<BbItem> items) {
        long announcements = items.stream().filter(i -> i.kind().equals("announcement")).count();
        long assignments = items.stream().filter(i -> i.kind().equals("assignment")).count();
        long materials = items.stream().filter(i -> i.kind().equals("material")).count();
        return count(announcements, "announcement") + ", " + count(assignments, "assignment") + ", "
                + count(materials, "material");
    }

    private static Change materialsLine(String course, List<String> titles) {
        if (titles.size() == 1) {
            return new Change("added", "New material · " + course + ": " + titles.get(0));
        }
        String more = titles.size() > MATERIALS_NAMED ? " and " + (titles.size() - MATERIALS_NAMED) + " more" : "";
        return new Change("added", titles.size() + " new materials · " + course + ": "
                + String.join(", ", titles.subList(0, Math.min(MATERIALS_NAMED, titles.size()))) + more);
    }

    /**
     * old is null on the first Blackboard sync. A newly seen course and a batch of new materials each give
     * one line, so a folder of uploads doesn't push the other news off the short list on the Overview.
     */
    public static List<Change> blackboard(BbState old, BbState fresh) {
        if (old == null) {
            if (fresh.courses().isEmpty()) {
                return List.of();
            }
            return List.of(new Change("added", "Blackboard loaded: " + count(fresh.courses().size(), "course") + ", "
                    + bbCounts(fresh.items())));
        }

        Set<String> known = new HashSet<>(old.courses());
        List<Change> changes = new ArrayList<>();
        for (String course : fresh.courses()) {
            if (!known.contains(course)) {
                changes.add(new Change("added", "New course on Blackboard · " + course + ": "
                        + bbCounts(fresh.items().stream().filter(i -> i.course().equals(course)).toList())));
            }
        }
        Map<ItemKey, BbItem> before = new LinkedHashMap<>();
        for (BbItem item : old.items()) {
            before.put(new ItemKey(item.kind(), item.bbId()), item);
        }
        Map<String, List<String>> newMaterials = new LinkedHashMap<>();
        for (BbItem item : fresh.items()) {
            if (!known.contains(item.course())) {
                continue;
            }
            BbItem previous = before.get(new ItemKey(item.kind(), item.bbId()));
            if (item.kind().equals("announcement") && previous == null) {
                changes.add(new Change("added", "New announcement · " + item.course() + ": " + item.title()));
            } else if (item.kind().equals("assignment") && previous == null) {
                String due = item.dueAt() == null ? "" : ", due " + when(item.dueAt());
                changes.add(new Change("added", "New assignment · " + item.course() + ": " + item.title() + due));
            } else if (item.kind().equals("assignment")) {
                if (item.dueAt() != null && previous.dueAt() != null && !item.dueAt().equals(previous.dueAt())) {
                    changes.add(new Change("changed", "Due date changed · " + item.course() + ", " + item.title() + ": "
                            + when(previous.dueAt()) + " → " + when(item.dueAt())));
                } else if (item.dueAt() != null && previous.dueAt() == null) {
                    changes.add(new Change("changed", "Due date set · " + item.course() + ", " + item.title() + ": "
                            + when(item.dueAt())));
                }
                if ("graded".equals(item.status())
                        && (!"graded".equals(previous.status()) || !Objects.equals(previous.score(), item.score()))) {
                    changes.add(new Change("changed", "New grade · " + item.course() + ", " + item.title() + ": "
                            + grade(item)));
                }
            } else if (item.kind().equals("material") && previous == null && !"folder".equals(item.materialKind())) {
                newMaterials.computeIfAbsent(item.course(), course -> new ArrayList<>()).add(item.title());
            }
        }
        newMaterials.forEach((course, titles) -> changes.add(materialsLine(course, titles)));
        return changes;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/SchoolFormat.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.sync.Changes;

/**
 * How the School pages write times, money and scores. Templates call it as {@code ${@schoolFormat.when(...)}};
 * times are UTC as stored and shown in Vietnam time.
 */
@Component
public class SchoolFormat {

    static final int PREVIEW_CHARACTERS = 120;
    static final Map<String, String> CHANGE_LABELS = Map.of("online", "Online", "makeup", "Make-up", "cancelled", "Cancelled");

    /** "Tue 29/09 08:00", or "never". */
    public String when(LocalDateTime utc) {
        return utc == null ? "never" : VietnamTime.when(utc);
    }

    /** "08:00". */
    public String clock(LocalDateTime utc) {
        return VietnamTime.clock(utc);
    }

    /** "29/09/2026", the Vietnam date of a UTC time. */
    public String date(LocalDateTime utc) {
        return VietnamTime.fullDate(VietnamTime.date(utc));
    }

    /** "15/10/2026". */
    public String day(LocalDate day) {
        return VietnamTime.fullDate(day);
    }

    /** "Tue 29/09". */
    public String dayLabel(LocalDate day) {
        return VietnamTime.dayLabel(day);
    }

    /** "12,500,000". */
    public String money(long vnd) {
        return String.format(Locale.ROOT, "%,d", vnd);
    }

    /** "8.5", "10", "6.66667" (as the What-changed lines write scores). */
    public String score(Double value) {
        return Changes.number(value);
    }

    /** An assignment's grade: "8.5/10", "8.5", the grade's text, or "graded". */
    public String grade(SchoolBbAssignment assignment) {
        return Changes.grade(assignment.getScore(), assignment.getPointsPossible(), assignment.getGradeText());
    }

    /** The badge for a class changed by an announcement. */
    public String changeLabel(String change) {
        return CHANGE_LABELS.get(change);
    }

    /** The first 120 characters, with "…" when there is more. */
    public String preview(String text) {
        if (text.codePointCount(0, text.length()) <= PREVIEW_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_CHARACTERS)) + "…";
    }
}
```

- [ ] **Step 4: The pages' queries and "Sync now"**

`web/src/main/java/vn/edu/hcmiu/sla/school/model/CourseCard.java`:

```java
package vn.edu.hcmiu.sla.school.model;

/** A Blackboard course on the Courses page: its name and how much it holds. */
public record CourseCard(Integer id, String name, String courseCode, long announcements, long assignments) {
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolExamRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Integer> {

    List<SchoolExam> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolExam e where e.userId = :userId and e.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    /** Exams starting in [start, end), in time order. */
    @Query("select e from SchoolExam e where e.userId = :userId and e.startAt >= :start and e.startAt < :end "
            + "order by e.startAt, e.id")
    List<SchoolExam> findStarting(Integer userId, LocalDateTime start, LocalDateTime end);

    Optional<SchoolExam> findFirstByUserIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(Integer userId,
            LocalDateTime now);

    List<SchoolExam> findByUserIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(Integer userId, LocalDateTime now);

    List<SchoolExam> findTop20ByUserIdAndStartAtBeforeOrderByStartAtDescIdDesc(Integer userId, LocalDateTime now);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAnnouncementRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAnnouncementRepository extends JpaRepository<SchoolBbAnnouncement, Integer> {

    List<SchoolBbAnnouncement> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAnnouncement a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    @Query("select a from SchoolBbAnnouncement a join fetch a.course where a.userId = :userId")
    List<SchoolBbAnnouncement> findWithCourse(Integer userId);

    /** The newest announcements first, with their course. */
    @Query("select a from SchoolBbAnnouncement a join fetch a.course where a.userId = :userId "
            + "order by a.postedAt desc, a.id desc")
    List<SchoolBbAnnouncement> findLatest(Integer userId, Limit limit);

    List<SchoolBbAnnouncement> findByCourseIdOrderById(Integer courseId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAssignmentRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAssignmentRepository extends JpaRepository<SchoolBbAssignment, Integer> {

    List<SchoolBbAssignment> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAssignment a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    /** Deadlines in [start, end), soonest first, with their course. */
    @Query("select a from SchoolBbAssignment a join fetch a.course where a.userId = :userId "
            + "and a.dueAt >= :start and a.dueAt < :end order by a.dueAt, a.id")
    List<SchoolBbAssignment> findDue(Integer userId, LocalDateTime start, LocalDateTime end);

    /** Not handed in yet and due since the given time, soonest first, with their course. */
    @Query("select a from SchoolBbAssignment a join fetch a.course where a.userId = :userId "
            + "and a.status = 'not_graded' and a.dueAt >= :since order by a.dueAt, a.id")
    List<SchoolBbAssignment> findToSubmit(Integer userId, LocalDateTime since);

    List<SchoolBbAssignment> findByCourseIdOrderById(Integer courseId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbCourseRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbCourseRepository extends JpaRepository<SchoolBbCourse, Integer> {

    List<SchoolBbCourse> findByUserIdOrderById(Integer userId);

    /** Deletes the user's Blackboard courses with their announcements, assignments and materials. */
    @Modifying
    @Query("delete from SchoolBbCourse c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);

    Optional<SchoolBbCourse> findByIdAndUserId(Integer id, Integer userId);

    /** The user's courses by name, with how many announcements and assignments each has. */
    @Query("select new vn.edu.hcmiu.sla.school.model.CourseCard(c.id, c.name, c.courseCode, "
            + "(select count(a) from SchoolBbAnnouncement a where a.course = c), "
            + "(select count(x) from SchoolBbAssignment x where x.course = c)) "
            + "from SchoolBbCourse c where c.userId = :userId order by c.name, c.id")
    List<CourseCard> findCards(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbMaterialRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbMaterialRepository extends JpaRepository<SchoolBbMaterial, Integer> {

    List<SchoolBbMaterial> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbMaterial m where m.userId = :userId")
    void deleteAllOfUser(Integer userId);

    List<SchoolBbMaterial> findByCourseIdOrderById(Integer courseId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolChangeRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolChangeRepository extends JpaRepository<SchoolChange, Integer> {

    List<SchoolChange> findBySyncRunIdOrderById(Integer syncRunId);

    List<SchoolChange> findTop10ByUserIdOrderByIdDesc(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncRunRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncRunRepository extends JpaRepository<SchoolSyncRun, Integer> {

    Optional<SchoolSyncRun> findFirstByUserIdOrderByStartedAtDescIdDesc(Integer userId);

    Optional<SchoolSyncRun> findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(Integer userId,
            Collection<String> statuses);

    List<SchoolSyncRun> findByUserIdAndStatus(Integer userId, String status);

    List<SchoolSyncRun> findTop10ByUserIdOrderByStartedAtDescIdDesc(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncDeviceRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncDeviceRepository extends JpaRepository<SchoolSyncDevice, Integer> {

    Optional<SchoolSyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** The devices that can still sync, oldest first. Cancelled ones stay (sync history refers to them). */
    List<SchoolSyncDevice> findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuitionRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolTuitionRepository extends JpaRepository<SchoolTuition, Integer> {

    Optional<SchoolTuition> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolTuition t where t.userId = :userId and t.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    List<SchoolTuition> findByUserIdOrderByTermCodeDesc(Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncRuns.java` with (adds `requestSync`):

```java
package vn.edu.hcmiu.sla.school.sync;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncSettings;
import vn.edu.hcmiu.sla.school.model.SchoolSyncSettingsRepository;

/** Sync run bookkeeping: settings, the latest runs, and starting a run. */
@Service
public class SyncRuns {

    static final String TIMED_OUT = "The sync didn't finish within 15 minutes.";

    /** Another run started less than 15 minutes ago and hasn't finished. */
    public static class RunInProgress extends RuntimeException {
    }

    /** The user's settings and whether a sync is due. */
    public record Check(SchoolSyncSettings settings, Scheduling.Decision decision) {
    }

    private final SchoolSyncSettingsRepository settings;
    private final SchoolSyncRunRepository runs;

    public SyncRuns(SchoolSyncSettingsRepository settings, SchoolSyncRunRepository runs) {
        this.settings = settings;
        this.runs = runs;
    }

    /** The user's sync settings, made with the default interval the first time. */
    @Transactional
    public SchoolSyncSettings settings(Integer userId) {
        return settings.findById(userId).orElseGet(
                () -> settings.save(new SchoolSyncSettings(userId, SchoolSyncSettings.DEFAULT_INTERVAL_HOURS)));
    }

    /** "Sync now": the laptop's next check-in is told a sync is due. */
    @Transactional
    public void requestSync(Integer userId, LocalDateTime now) {
        settings(userId).setSyncRequestedAt(now);
    }

    /** The newest run, or the newest with one of these statuses. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncRun> latestRun(Integer userId, String... statuses) {
        if (statuses.length == 0) {
            return runs.findFirstByUserIdOrderByStartedAtDescIdDesc(userId);
        }
        return runs.findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(userId, List.of(statuses));
    }

    @Transactional
    public Check check(Integer userId, LocalDateTime now) {
        SchoolSyncSettings mine = settings(userId);
        Optional<SchoolSyncRun> last = latestRun(userId);
        Optional<SchoolSyncRun> lastGood = latestRun(userId, SchoolSyncRun.SUCCESS, SchoolSyncRun.PARTIAL);
        Optional<SchoolSyncRun> running = latestRun(userId, SchoolSyncRun.RUNNING);
        return new Check(mine, Scheduling.decide(
                now,
                mine.getIntervalHours(),
                mine.getSyncRequestedAt(),
                last.map(SchoolSyncRun::getStartedAt).orElse(null),
                lastGood.map(SchoolSyncRun::getStartedAt).orElse(null),
                running.map(SchoolSyncRun::getStartedAt).orElse(null)));
    }

    /** Closes stuck runs as timed out, then opens a new one; {@link RunInProgress} if one is still going. */
    @Transactional
    public SchoolSyncRun start(SchoolSyncDevice device, String trigger, LocalDateTime now) {
        for (SchoolSyncRun run : runs.findByUserIdAndStatus(device.getUserId(), SchoolSyncRun.RUNNING)) {
            if (Duration.between(run.getStartedAt(), now).compareTo(Scheduling.RUN_TIMEOUT) < 0) {
                throw new RunInProgress();
            }
            run.finish(SchoolSyncRun.FAILED, now, "timeout", TIMED_OUT);
        }
        return runs.save(new SchoolSyncRun(device.getUserId(), device.getId(), trigger, now));
    }
}
```

- [ ] **Step 5: The controller and the templates**

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/SchoolController.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncement;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncementRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignmentRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterialRepository;
import vn.edu.hcmiu.sla.school.model.SchoolChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncSettings;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.schedule.Schedule;
import vn.edu.hcmiu.sla.school.sync.SyncRuns;

/** The School pages: Overview, Timetable, Courses, a course, Exams, Tuition, and "Sync now". */
@Controller
@RequestMapping("/school")
public class SchoolController {

    static final int OVERDUE_DAYS = 7; // a missed assignment stays in "To submit" this long

    private final Clock clock;
    private final SyncRuns syncRuns;
    private final Schedule schedule;
    private final SchoolSyncDeviceRepository devices;
    private final SchoolSyncRunRepository runs;
    private final SchoolExamRepository exams;
    private final SchoolChangeRepository changes;
    private final SchoolTuitionRepository tuition;
    private final SchoolBbCourseRepository bbCourses;
    private final SchoolBbAnnouncementRepository announcements;
    private final SchoolBbAssignmentRepository assignments;
    private final SchoolBbMaterialRepository materials;

    public SchoolController(Clock clock, SyncRuns syncRuns, Schedule schedule, SchoolSyncDeviceRepository devices,
            SchoolSyncRunRepository runs, SchoolExamRepository exams, SchoolChangeRepository changes,
            SchoolTuitionRepository tuition, SchoolBbCourseRepository bbCourses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments,
            SchoolBbMaterialRepository materials) {
        this.clock = clock;
        this.syncRuns = syncRuns;
        this.schedule = schedule;
        this.devices = devices;
        this.runs = runs;
        this.exams = exams;
        this.changes = changes;
        this.tuition = tuition;
        this.bbCourses = bbCourses;
        this.announcements = announcements;
        this.assignments = assignments;
        this.materials = materials;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private SyncStatus.Status status(Integer userId, LocalDateTime now) {
        SchoolSyncSettings settings = syncRuns.settings(userId);
        RunInfo latest = syncRuns.latestRun(userId).map(RunInfo::of).orElse(null);
        LocalDateTime lastGood = syncRuns.latestRun(userId, SchoolSyncRun.SUCCESS, SchoolSyncRun.PARTIAL)
                .map(SchoolSyncRun::getFinishedAt).orElse(null);
        List<SchoolSyncDevice> active = devices.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(userId);
        LocalDateTime lastSeen = active.stream().map(SchoolSyncDevice::getLastSeenAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return SyncStatus.describe(now, settings.getIntervalHours(), settings.getSyncRequestedAt(), latest, lastGood,
                !active.isEmpty(), lastSeen);
    }

    @GetMapping({"", "/"})
    String overview(@AuthenticationPrincipal AppUser user, Model model) {
        LocalDateTime now = now();
        LocalDate today = VietnamTime.date(now);
        model.addAttribute("status", status(user.id(), now));
        model.addAttribute("systemLines", SyncStatus.systemLines(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList(), now));
        model.addAttribute("today", today);
        model.addAttribute("todayItems", schedule.itemsOn(user.id(), today));
        model.addAttribute("tomorrowItems", schedule.itemsOn(user.id(), today.plusDays(1)));
        model.addAttribute("toSubmit", assignments.findToSubmit(user.id(), now.minusDays(OVERDUE_DAYS)));
        model.addAttribute("latestAnnouncements", announcements.findLatest(user.id(), Limit.of(3)));
        model.addAttribute("nextExam",
                exams.findFirstByUserIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(user.id(), now).orElse(null));
        model.addAttribute("changes", changes.findTop10ByUserIdOrderByIdDesc(user.id()));
        model.addAttribute("now", now);
        return "school/index";
    }

    @GetMapping("/timetable")
    String timetable() {
        return "school/timetable";
    }

    @GetMapping("/courses")
    String courses(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("courses", bbCourses.findCards(user.id()));
        return "school/courses";
    }

    @GetMapping("/courses/{courseId}")
    String course(@AuthenticationPrincipal AppUser user, @PathVariable int courseId, Model model) {
        SchoolBbCourse course = bbCourses.findByIdAndUserId(courseId, user.id()).orElseThrow(SchoolController::notFound);
        model.addAttribute("course", course);
        // Newest first; soonest first; newest first. Items without a time go last.
        model.addAttribute("announcements", announcements.findByCourseIdOrderById(courseId).stream()
                .sorted(Comparator.comparing(SchoolBbAnnouncement::getPostedAt,
                        Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())).reversed())
                .toList());
        model.addAttribute("assignments", assignments.findByCourseIdOrderById(courseId).stream()
                .sorted(Comparator.comparing(SchoolBbAssignment::getDueAt,
                        Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .toList());
        model.addAttribute("materials", materials.findByCourseIdOrderById(courseId).stream()
                .sorted(Comparator.comparing(SchoolBbMaterial::getCreatedAt,
                        Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())).reversed())
                .toList());
        model.addAttribute("now", now());
        return "school/course";
    }

    @GetMapping("/exams")
    String exams(@AuthenticationPrincipal AppUser user, Model model) {
        LocalDateTime now = now();
        model.addAttribute("upcoming", exams.findByUserIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(user.id(), now));
        model.addAttribute("past", exams.findTop20ByUserIdAndStartAtBeforeOrderByStartAtDescIdDesc(user.id(), now));
        model.addAttribute("labels", Schedule.EXAM_LABELS);
        return "school/exams";
    }

    @GetMapping("/tuition")
    String tuition(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("rows", tuition.findByUserIdOrderByTermCodeDesc(user.id()));
        return "school/tuition";
    }

    @PostMapping("/sync-now")
    String syncNow(@AuthenticationPrincipal AppUser user, RedirectAttributes redirect) {
        syncRuns.requestSync(user.id(), now());
        Flash.success(redirect, "Sync requested. Your laptop will pick it up at its next check-in.");
        return "redirect:/school";
    }
}
```

`web/src/main/resources/templates/school/fragments.html`:

```html
<!doctype html>
<!--/* Pieces shared by the School pages: the school menu, the sync status card, and one class or exam. */-->
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<body>

<!--/* The school menu; current is the page's name: overview, timetable, courses, exams, tuition or devices. */-->
<nav th:fragment="subnav(current)" class="subnav" aria-label="School pages">
  <a th:href="@{/school}" th:attr="aria-current=${current == 'overview' ? 'page' : null}">Overview</a>
  <a th:href="@{/school/timetable}" th:attr="aria-current=${current == 'timetable' ? 'page' : null}">Timetable</a>
  <a th:href="@{/school/courses}" th:attr="aria-current=${current == 'courses' ? 'page' : null}">Courses</a>
  <a th:href="@{/school/exams}" th:attr="aria-current=${current == 'exams' ? 'page' : null}">Exams</a>
  <a th:href="@{/school/tuition}" th:attr="aria-current=${current == 'tuition' ? 'page' : null}">Tuition</a>
  <a th:href="@{/school/devices}" th:attr="aria-current=${current == 'devices' ? 'page' : null}">Devices</a>
</nav>

<!--/* Needs ${status} (SyncStatus.Status) and ${systemLines}. */-->
<section th:fragment="status-card" th:class="|card status-card status-${status.state}|" aria-live="polite">
  <p class="status-headline" th:text="${status.headline}">Synced at 14:05</p>
  <p class="status-detail" th:if="${status.detail != null}" th:text="${status.detail}">What to do.</p>
  <ul class="system-lines" th:if="${!systemLines.isEmpty()}">
    <li th:each="line : ${systemLines}" th:class="|system-${line.state}|"><strong th:text="|${line.name}:|">EduSoft:</strong>
      <th:block th:text="${line.text}">synced at 14:05</th:block></li>
  </ul>
  <p class="status-detail" th:if="${status.lastSyncedAt != null and status.state != 'success' and status.state != 'partial'}"
     th:text="|Last good sync: ${@schoolFormat.when(status.lastSyncedAt)}|">Last good sync: Tue 29/09 08:00</p>
  <p class="status-warning" th:if="${status.laptopWarning != null}" th:text="${status.laptopWarning}">Your laptop…</p>
  <form th:if="${status.state != 'no_device'}" method="post" th:action="@{/school/sync-now}">
    <button type="submit" class="button">Sync now</button>
  </form>
</section>

<!--/* One class or exam (Schedule.Item). A class changed by an announcement gets a badge and a link to it. */-->
<li th:fragment="item(item)" th:with="look=${item.change == 'cancelled' ? 'cancelled' : 'changed'}"
    th:class="|item item-${item.kind}${item.change != null ? ' item-' + look : ''}|">
  <span class="item-time" th:text="${item.allDay ? 'All day' : @schoolFormat.clock(item.startAt)
      + (item.endAt != null ? '–' + @schoolFormat.clock(item.endAt) : '')}">08:00–10:30</span>
  <span class="item-title">
    <th:block th:if="${item.change != null}"><span th:class="|badge badge-${look}|"
        th:text="${@schoolFormat.changeLabel(item.change)}">Online</span> </th:block>
    <th:block th:if="${item.label != null}"><strong th:text="|${item.label}:|">Final exam:</strong> </th:block><th:block
        th:text="${item.title}">Web Application Development</th:block>
  </span>
  <span class="item-meta">
    <th:block th:text="${item.code}">IT093IU</th:block>
    <th:block th:if="${item.room != null and !item.room.isEmpty() and item.change != 'online'}">
      · <span th:if="${item.room.toUpperCase().startsWith('ONLINE')}" class="badge">Online</span><th:block
          th:unless="${item.room.toUpperCase().startsWith('ONLINE')}" th:text="${item.room}">A2.508</th:block>
    </th:block>
    <th:block th:if="${item.bbCourseId != null}">
      · <a th:href="@{/school/courses/{id}(id=${item.bbCourseId})}">See announcement</a>
    </th:block>
  </span>
</li>

</body>
</html>
```

`web/src/main/resources/templates/school/index.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>School · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>School</h1>
  <nav th:replace="~{school/fragments :: subnav('overview')}"></nav>
  <section th:replace="~{school/fragments :: status-card}"></section>

  <div class="two-columns">
    <section class="card">
      <h2>Today <span class="muted" th:text="${@schoolFormat.dayLabel(today)}">Tue 29/09</span></h2>
      <ul class="items" th:unless="${todayItems.isEmpty()}">
        <!--/* th:replace runs before th:each on one element, so the loop is on a block around it. */-->
        <th:block th:each="item : ${todayItems}"><li th:replace="~{school/fragments :: item(${item})}"></li></th:block>
      </ul>
      <p class="muted" th:if="${todayItems.isEmpty()}">No classes or exams today.</p>
      <h2>Tomorrow</h2>
      <ul class="items" th:unless="${tomorrowItems.isEmpty()}">
        <th:block th:each="item : ${tomorrowItems}"><li th:replace="~{school/fragments :: item(${item})}"></li></th:block>
      </ul>
      <p class="muted" th:if="${tomorrowItems.isEmpty()}">No classes or exams tomorrow.</p>
      <p><a th:href="@{/school/timetable}">Open the calendar →</a></p>
    </section>

    <section class="card">
      <h2>To submit</h2>
      <ul class="items" th:unless="${toSubmit.isEmpty()}">
        <li th:each="a : ${toSubmit}" th:with="overdue=${a.dueAt.isBefore(now)}"
            th:class="|item item-due${overdue ? ' is-overdue' : ''}|">
          <span class="item-time"><th:block th:text="${@schoolFormat.when(a.dueAt)}">Fri 02/10 23:59</th:block><th:block
              th:if="${overdue}"> <span class="badge badge-overdue">Overdue</span></th:block></span>
          <span class="item-title" th:text="${a.name}">Lab 3</span>
          <span class="item-meta"><th:block th:text="${a.course.name}">Web Application Development</th:block> ·
            <a th:href="${a.url}" target="_blank" rel="noopener noreferrer">Open assignment ↗</a></span>
        </li>
      </ul>
      <p class="muted" th:if="${toSubmit.isEmpty()}">Nothing left to submit.</p>

      <h2>Latest announcements</h2>
      <ul class="changes" th:unless="${latestAnnouncements.isEmpty()}">
        <li th:each="a : ${latestAnnouncements}"><a th:href="@{/school/courses/{id}(id=${a.course.id})}"
            th:text="${a.course.name}">Web Application Development</a>: <th:block th:text="${a.title}">No class</th:block>
          <span class="muted" th:text="${@schoolFormat.preview(a.text)}">Class is cancelled.</span></li>
      </ul>
      <p class="muted" th:if="${latestAnnouncements.isEmpty()}">No announcements yet.</p>

      <h2>Next exam</h2>
      <p th:if="${nextExam != null}"><strong th:text="${nextExam.courseName}">Web Application Development</strong>
        (<th:block th:text="${nextExam.examType}">final</th:block>)<br>
        <th:block th:text="|${@schoolFormat.date(nextExam.startAt)} at ${@schoolFormat.clock(nextExam.startAt)}|">12/12/2026
          at 08:00</th:block><th:block th:if="${nextExam.room != null}" th:text="| · ${nextExam.room}|"> · A1.101</th:block></p>
      <p class="muted" th:if="${nextExam == null}">No upcoming exams published yet.</p>

      <h2>What changed</h2>
      <ul class="changes" th:unless="${changes.isEmpty()}">
        <li th:each="change : ${changes}" th:class="|change change-${change.kind}|" th:text="${change.summary}">
          IT093IU Web Application Development: room A2.307 → A2.508</li>
      </ul>
      <p class="muted" th:if="${changes.isEmpty()}">Nothing yet. Changes appear here after each sync.</p>
    </section>
  </div>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/timetable.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Timetable · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Timetable</h1>
  <nav th:replace="~{school/fragments :: subnav('timetable')}"></nav>

  <p class="legend">
    <span class="legend-item legend-class">Class</span>
    <span class="legend-item legend-exam">Exam</span>
    <span class="legend-item legend-due">Deadline</span>
    <span class="legend-item legend-changed">Online / make-up</span>
    <span class="legend-item legend-cancelled">Cancelled</span>
    <span class="muted">All times are Vietnam time.</span>
  </p>

  <div class="card calendar-card">
    <div id="calendar" th:attr="data-feed=@{/school/api/calendar}"></div>
    <noscript>The calendar needs JavaScript. Your classes for today and tomorrow are on the Overview page.</noscript>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/fullcalendar@6.1.21/index.global.min.js"
          integrity="sha384-WDvnzcla8X1CQM97EnYyl4OoTCvmMFp5lBiVNO3IjVdvLMOUjwt+iuYb/Mru5A9v"
          crossorigin="anonymous"></script>
  <script th:src="@{/js/timetable.js}"></script>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/courses.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Courses · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Courses</h1>
  <nav th:replace="~{school/fragments :: subnav('courses')}"></nav>
  <div class="module-grid" th:unless="${courses.isEmpty()}">
    <a th:each="course : ${courses}" class="card module-card" th:href="@{/school/courses/{id}(id=${course.id})}">
      <h2 th:text="${course.name}">Web Application Development</h2>
      <p class="muted"
         th:text="|${course.courseCode ?: ''} · ${course.announcements} announcements · ${course.assignments} assignments|">
        IT093IU · 3 announcements · 2 assignments</p>
    </a>
  </div>
  <section class="card" th:if="${courses.isEmpty()}"><p>No Blackboard courses yet. Set up Blackboard on your laptop with
    <code>sla-agent setup --blackboard</code>; your courses appear after the next sync.</p></section>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/course.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title th:text="|${course.name} · School-Life-Assistant|">Course · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1 th:text="${course.name}">Web Application Development</h1>
  <nav th:replace="~{school/fragments :: subnav('courses')}"></nav>
  <p><a th:href="${course.url}" target="_blank" rel="noopener noreferrer">Open in Blackboard ↗</a></p>

  <section class="card">
    <h2>Announcements</h2>
    <article class="bb-item" th:each="a : ${announcements}">
      <h3 th:text="${a.title}">No class on Thursday</h3>
      <p class="muted" th:text="${a.postedAt != null ? @schoolFormat.when(a.postedAt) : ''}">Mon 28/09 09:00</p>
      <p class="bb-text" th:text="${a.text}">Class is cancelled.</p>
      <p><a th:href="${a.url}" target="_blank" rel="noopener noreferrer">Open in Blackboard ↗</a></p>
    </article>
    <p class="muted" th:if="${announcements.isEmpty()}">No announcements.</p>
  </section>

  <section class="card">
    <h2>Assignments &amp; grades</h2>
    <article th:each="a : ${assignments}"
             th:with="overdue=${a.dueAt != null and a.dueAt.isBefore(now) and a.status == 'not_graded'}"
             th:class="|bb-item${overdue ? ' is-overdue' : ''}|">
      <h3 th:text="${a.name}">Lab 3</h3>
      <p>
        <th:block th:if="${a.dueAt != null}">Due <th:block th:text="${@schoolFormat.when(a.dueAt)}">Fri 02/10 23:59</th:block><th:block
            th:if="${overdue}"> <span class="badge badge-warning">Overdue</span></th:block></th:block><th:block
            th:if="${a.dueAt == null}">No due date</th:block>
        · <th:block th:switch="${a.status}"><th:block th:case="'graded'">Grade: <strong
            th:text="${@schoolFormat.grade(a)}">8.5/10</strong></th:block><th:block
            th:case="'needs_grading'">Submitted, waiting for a grade</th:block><th:block
            th:case="'exempt'">Exempt</th:block><th:block th:case="*">Not graded yet</th:block></th:block>
      </p>
      <p class="bb-text muted" th:if="${a.feedback != null and !a.feedback.isEmpty()}"
         th:text="|Feedback: ${a.feedback}|">Feedback: Good work</p>
      <p><a th:href="${a.url}" target="_blank" rel="noopener noreferrer">Open in Blackboard ↗</a></p>
    </article>
    <p class="muted" th:if="${assignments.isEmpty()}">No assignments.</p>
  </section>

  <section class="card">
    <h2>Materials</h2>
    <ul class="bb-materials" th:unless="${materials.isEmpty()}">
      <li th:each="m : ${materials}">
        <strong th:text="${m.title}">Week 5 slides.pdf</strong>
        <span class="muted"><th:block th:text="${m.kind}">file</th:block><th:block th:if="${!m.path.isEmpty()}"
            th:text="| · ${m.path}|"> · Week 5</th:block><th:block th:if="${m.createdAt != null}"
            th:text="| · added ${@schoolFormat.date(m.createdAt)}|"> · added 28/09/2026</th:block></span>
        · <a th:href="${m.url}" target="_blank" rel="noopener noreferrer">Open in Blackboard ↗</a>
      </li>
    </ul>
    <p class="muted" th:if="${materials.isEmpty()}">No materials.</p>
  </section>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/exams.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Exams · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Exams</h1>
  <nav th:replace="~{school/fragments :: subnav('exams')}"></nav>

  <section class="card">
    <h2>Upcoming</h2>
    <ul class="exam-list" th:unless="${upcoming.isEmpty()}">
      <li th:each="exam : ${upcoming}">
        <strong th:text="${exam.courseName}">Web Application Development</strong>
        <span class="muted" th:text="${exam.courseCode}">IT093IU</span><br>
        <th:block th:text="|${labels.getOrDefault(exam.examType, 'Exam')} · ${@schoolFormat.date(exam.startAt)} at ${@schoolFormat.clock(exam.startAt)}|">
          Final exam · 12/12/2026 at 08:00</th:block>
        <th:block th:if="${exam.durationMin != null}" th:text="| · ${exam.durationMin} min|"> · 90 min</th:block>
        <th:block th:if="${exam.room != null and !exam.room.isEmpty()}" th:text="| · room ${exam.room}|"> · room A1.101</th:block>
        <th:block th:if="${exam.notes != null and !exam.notes.isEmpty()}"><br><span class="muted"
            th:text="${exam.notes}">Bring your student card.</span></th:block>
      </li>
    </ul>
    <p th:if="${upcoming.isEmpty()}">No exams published yet for this semester. EduSoft usually publishes midterm and final exam
      schedules later in the semester; they'll appear here after the next sync.</p>
  </section>

  <section class="card" th:unless="${past.isEmpty()}">
    <h2>Past</h2>
    <ul class="exam-list is-past">
      <li th:each="exam : ${past}"
          th:text="|${exam.courseName} · ${labels.getOrDefault(exam.examType, 'Exam')} · ${@schoolFormat.date(exam.startAt)}|">
        Web Application Development · Midterm exam · 28/10/2026</li>
    </ul>
  </section>
</main>
</body>
</html>
```

`web/src/main/resources/templates/school/tuition.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Tuition · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Tuition</h1>
  <nav th:replace="~{school/fragments :: subnav('tuition')}"></nav>

  <section class="card pay-card">
    <div>
      <h2>Pay your tuition</h2>
      <p class="muted">Opens IU's official payment site, IUPay, in a new tab.</p>
    </div>
    <a class="button" href="https://iupay.hcmiu.edu.vn/search/dhqt" target="_blank" rel="noopener noreferrer">
      Pay on IUPay ↗
    </a>
  </section>

  <section class="card" th:each="row : ${rows}">
    <h2 th:text="|Semester ${row.termCode}|">Semester 20261</h2>
    <dl class="facts">
      <dt>Amount due</dt><dd th:text="|${@schoolFormat.money(row.amountDue)} VND|">12,500,000 VND</dd>
      <dt>Paid</dt><dd th:text="|${@schoolFormat.money(row.amountPaid)} VND|">0 VND</dd>
      <dt>Balance</dt><dd><strong th:text="|${@schoolFormat.money(row.balance)} VND|">12,500,000 VND</strong></dd>
      <dt>Due date</dt><dd th:text="${row.dueDate != null ? @schoolFormat.day(row.dueDate) : 'not shown by EduSoft'}">15/10/2026</dd>
      <th:block th:if="${row.statusText != null and !row.statusText.isEmpty()}"><dt>Status</dt><dd
          th:text="${row.statusText}">Chưa đóng</dd></th:block>
    </dl>
  </section>
  <section class="card" th:if="${rows.isEmpty()}">
    <p>No tuition information yet. The tuition part of the sync isn't finished: EduSoft's tuition
       report still has to be located.</p>
  </section>
</main>
</body>
</html>
```

Copy the timetable script unchanged from the Python site:

```bash
mkdir -p web/src/main/resources/static/js && cp app/static/js/timetable.js web/src/main/resources/static/js/timetable.js
```

- [ ] **Step 6: Run the test to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest=SchoolPagesTest)`
Expected: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 7: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 292, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` (`LayoutTest` still counts three module cards: School now has its real link).

- [ ] **Step 8: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/core/ClockConfig.java web/src/main/java/vn/edu/hcmiu/sla/school web/src/main/resources/templates/school web/src/main/resources/static/js/timetable.js web/src/test/java/vn/edu/hcmiu/sla/school/TestClock.java web/src/test/java/vn/edu/hcmiu/sla/school/pages/SchoolPagesTest.java
git commit -m "feat(web): the School pages in Java: Overview, Timetable, Courses, Exams, Tuition and Sync now"
```

---

### Task 5: The Devices page

A laptop is added on this page; its key is shown once, with `Cache-Control: no-store`. A device can be renamed, or cancelled so its key stops working at once; cancelled devices leave the list. Another user's device is 404. `DevicesPageTest` twins the device cases of `tests/test_school_pages.py`, plus renaming to nothing and a missing CSRF token.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/DeviceForm.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/pages/DevicesController.java`, `web/src/main/resources/templates/school/devices.html`
- Modify (replaced): `web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeys.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncDeviceRepository.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java`

**Interfaces:**
- Consumes: `DeviceKeys.create` (stage 2a); `SchoolTestData` (Task 3); `SchoolFormat`, the `Clock` bean and `fragments.html :: subnav` (Task 4); `Text.strip` (stage 2a); `Flash` (stage 1).
- Produces: `DeviceKeys.active(userId)`, `own(userId, deviceId)`, `rename(userId, deviceId, name)`, `revoke(userId, deviceId, now)` (each `Optional<SchoolSyncDevice>` except `active`); `SchoolSyncDeviceRepository.findByIdAndUserId`; pages `GET/POST /school/devices`, `POST /school/devices/{id}/rename`, `POST /school/devices/{id}/revoke`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/** The Devices page: Java twin of the device tests in tests/test_school_pages.py. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DevicesPageTest {

    static final Pattern KEY = Pattern.compile("sla_[A-Za-z0-9_\\-]{40,}");

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    DeviceKeys deviceKeys;

    @Autowired
    SchoolSyncDeviceRepository devices;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    ResultActions addDevice(String name) throws Exception {
        return mvc.perform(post("/school/devices").with(user(an)).with(csrf()).param("name", name));
    }

    static String keyIn(String html) {
        Matcher match = KEY.matcher(html);
        return match.find() ? match.group() : null;
    }

    List<SchoolSyncDevice> all() {
        db.flush();
        db.clear();
        return devices.findAll(Sort.by("id"));
    }

    ResultActions check(String key) throws Exception {
        return mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key));
    }

    String devicesPage() throws Exception {
        return mvc.perform(get("/school/devices").with(user(an))).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewDeviceKeyIsShownOnceAndWorks() throws Exception {
        String html = addDevice("My laptop")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String key = keyIn(html);

        assertThat(key).isNotNull();
        check(key).andExpect(status().isOk());
        assertThat(devicesPage()).doesNotContain(key);
    }

    @Test
    void aDeviceNeedsAName() throws Exception {
        String html = addDevice("   ").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(keyIn(html)).isNull();
        assertThat(html).contains("This field is required.");
        assertThat(all()).isEmpty();
    }

    @Test
    void cancellingADeviceStopsItsKey() throws Exception {
        String key = keyIn(addDevice("My laptop").andReturn().getResponse().getContentAsString());
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/revoke").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/devices"))
                .andExpect(flash().attribute("flashes", List.of(new Flash("message", "“My laptop” can no longer sync."))));

        assertThat(all().get(0).getRevokedAt()).isNotNull();
        check(key).andExpect(status().isUnauthorized());
    }

    @Test
    void aCancelledDeviceDisappearsFromTheList() throws Exception {
        addDevice("Old laptop");
        addDevice("New laptop");
        Integer oldId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + oldId + "/revoke").with(user(an)).with(csrf()));

        assertThat(devicesPage()).doesNotContain("Old laptop").contains("New laptop");
    }

    @Test
    void renamingADevice() throws Exception {
        addDevice("My laptop");
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/rename").with(user(an)).with(csrf()).param("name", "Dorm laptop"))
                .andExpect(flash().attribute("flashes", List.of(new Flash("message", "Device renamed."))));

        assertThat(all().get(0).getName()).isEqualTo("Dorm laptop");
    }

    @Test
    void aDeviceCantBeRenamedToNothing() throws Exception {
        addDevice("My laptop");
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/rename").with(user(an)).with(csrf()).param("name", " "))
                .andExpect(flash().attribute("flashes",
                        List.of(new Flash("error", "A device name is required (up to 100 characters)."))));

        assertThat(all().get(0).getName()).isEqualTo("My laptop");
    }

    @ParameterizedTest
    @CsvSource({"revoke, ''", "rename, Mine now"})
    void nobodyCanChangeAnotherUsersDevice(String action, String name) throws Exception {
        AppUser binh = data.user("binh@example.com");
        String otherKey = deviceKeys.create(binh.id(), "Binh's laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
        Integer otherId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + otherId + "/" + action).with(user(an)).with(csrf()).param("name", name))
                .andExpect(status().isNotFound());

        SchoolSyncDevice device = all().get(0);
        assertThat(device.getName()).isEqualTo("Binh's laptop");
        assertThat(device.getRevokedAt()).isNull();
        check(otherKey).andExpect(status().isOk());
    }

    @Test
    void theDevicesPageListsOnlyMyDevices() throws Exception {
        deviceKeys.create(data.user("binh@example.com").id(), "Binh's laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        addDevice("An's laptop");

        assertThat(devicesPage()).contains("An&#39;s laptop").doesNotContain("Binh");
    }

    @Test
    void formsWithoutTheirSecurityCodeAreRefused() throws Exception {
        mvc.perform(post("/school/devices").with(user(an)).param("name", "My laptop")).andExpect(status().isForbidden());

        assertThat(all()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B test -Dtest=DevicesPageTest)`
Expected: `Tests run: 10, Failures: 3, Errors: 4, Skipped: 0`. With no `/school/devices` yet, pages and forms get 404. Three tests already pass for reasons that don't need the page: `formsWithoutTheirSecurityCodeAreRefused` (Spring Security refuses the form first), and both `nobodyCanChangeAnotherUsersDevice` cases (no page at all is also a 404; after Step 4 it is 404 because the device isn't yours).

- [ ] **Step 3: The device list, rename and cancel**

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncDeviceRepository.java` with:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncDeviceRepository extends JpaRepository<SchoolSyncDevice, Integer> {

    Optional<SchoolSyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** The devices that can still sync, oldest first. Cancelled ones stay (sync history refers to them). */
    List<SchoolSyncDevice> findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(Integer userId);

    Optional<SchoolSyncDevice> findByIdAndUserId(Integer id, Integer userId);
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeys.java` with:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;

/**
 * Device keys: how the laptop agent proves which user it syncs for. The raw key is shown to the user
 * once; the database keeps only its SHA-256 hash, so a leaked database can't be used to upload data.
 * Keys look the same as the Python site's, so a laptop set up there keeps working.
 */
@Service
public class DeviceKeys {

    public static final String KEY_PREFIX = "sla_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new device and its raw key, which is shown once and never stored. */
    public record NewDevice(SchoolSyncDevice device, String rawKey) {
    }

    private final SchoolSyncDeviceRepository devices;

    public DeviceKeys(SchoolSyncDeviceRepository devices) {
        this.devices = devices;
    }

    public static String hashKey(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Like Python's secrets.token_urlsafe(32): 32 random bytes, URL-safe Base64 without padding. */
    static String newRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public NewDevice create(Integer userId, String name, LocalDateTime now) {
        String rawKey = newRawKey();
        SchoolSyncDevice device = devices.save(new SchoolSyncDevice(userId, name, hashKey(rawKey), now));
        return new NewDevice(device, rawKey);
    }

    /** The devices that can still sync, oldest first. Cancelled ones stay (sync history refers to them). */
    @Transactional(readOnly = true)
    public List<SchoolSyncDevice> active(Integer userId) {
        return devices.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(userId);
    }

    /** The user's device, or empty for another user's or an unknown one. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncDevice> own(Integer userId, int deviceId) {
        return devices.findByIdAndUserId(deviceId, userId);
    }

    @Transactional
    public Optional<SchoolSyncDevice> rename(Integer userId, int deviceId, String name) {
        Optional<SchoolSyncDevice> device = devices.findByIdAndUserId(deviceId, userId);
        device.ifPresent(found -> found.setName(name));
        return device;
    }

    /** Cancels the device: its key stops working at once. */
    @Transactional
    public Optional<SchoolSyncDevice> revoke(Integer userId, int deviceId, LocalDateTime now) {
        Optional<SchoolSyncDevice> device = devices.findByIdAndUserId(deviceId, userId);
        device.filter(found -> found.getRevokedAt() == null).ifPresent(found -> found.setRevokedAt(now));
        return device;
    }

    /** The active device for this key, or empty. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncDevice> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return Optional.empty();
        }
        return devices.findByTokenHashAndRevokedAtIsNull(hashKey(rawKey));
    }

    /** Like {@link #authenticate}, and records that the laptop checked in now. */
    @Transactional
    public Optional<SchoolSyncDevice> checkIn(String rawKey, LocalDateTime now) {
        Optional<SchoolSyncDevice> device = authenticate(rawKey);
        device.ifPresent(found -> found.setLastSeenAt(now));
        return device;
    }
}
```

- [ ] **Step 4: The form, the controller and the template**

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/DeviceForm.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import vn.edu.hcmiu.sla.core.Text;

/** The device name on the Devices page, with the same rules and messages as the Python site. */
public class DeviceForm {

    @NotBlank(message = "This field is required.")
    @Size(max = 100, message = "Field cannot be longer than 100 characters.")
    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : Text.strip(name);
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/DevicesController.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * The Devices page: add a laptop (its key is shown once), rename it, or cancel it so its key stops
 * working. Another user's device is 404.
 */
@Controller
@RequestMapping("/school/devices")
public class DevicesController {

    private final Clock clock;
    private final DeviceKeys deviceKeys;

    public DevicesController(Clock clock, DeviceKeys deviceKeys) {
        this.clock = clock;
        this.deviceKeys = deviceKeys;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private String page(AppUser user, Model model) {
        model.addAttribute("devices", deviceKeys.active(user.id()));
        return "school/devices";
    }

    @GetMapping
    String devices(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("form", new DeviceForm());
        return page(user, model);
    }

    @PostMapping
    String add(@AuthenticationPrincipal AppUser user, @Valid @ModelAttribute("form") DeviceForm form,
            BindingResult result, Model model, HttpServletResponse response) {
        if (!result.hasErrors()) {
            String rawKey = deviceKeys.create(user.id(), form.getName(), LocalDateTime.now(clock)).rawKey();
            model.addAttribute("newKey", rawKey);
            model.addAttribute("form", new DeviceForm());
            response.setHeader("Cache-Control", "no-store"); // the key is shown once; the browser keeps no copy
        }
        return page(user, model);
    }

    @PostMapping("/{deviceId}/rename")
    String rename(@AuthenticationPrincipal AppUser user, @PathVariable int deviceId,
            @Valid @ModelAttribute("form") DeviceForm form, BindingResult result, RedirectAttributes redirect) {
        deviceKeys.own(user.id(), deviceId).orElseThrow(DevicesController::notFound);
        if (result.hasErrors()) {
            Flash.error(redirect, "A device name is required (up to 100 characters).");
        } else {
            deviceKeys.rename(user.id(), deviceId, form.getName());
            Flash.success(redirect, "Device renamed.");
        }
        return "redirect:/school/devices";
    }

    @PostMapping("/{deviceId}/revoke")
    String revoke(@AuthenticationPrincipal AppUser user, @PathVariable int deviceId, RedirectAttributes redirect) {
        SchoolSyncDevice device = deviceKeys.revoke(user.id(), deviceId, LocalDateTime.now(clock))
                .orElseThrow(DevicesController::notFound);
        Flash.success(redirect, "“" + device.getName() + "” can no longer sync.");
        return "redirect:/school/devices";
    }
}
```

`web/src/main/resources/templates/school/devices.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Devices · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Devices</h1>
  <nav th:replace="~{school/fragments :: subnav('devices')}"></nav>
  <p>A device is a laptop running <code>sla-agent</code>. It keeps your EduSoft password in Windows
     Credential Manager and uploads only your timetable, exams and tuition.</p>

  <th:block th:if="${newKey != null}">
    <section class="card key-card">
      <h2>Your new device key</h2>
      <p><strong>Copy it now. It won't be shown again.</strong> Then run <code>sla-agent setup</code>
         on the laptop and paste it when asked.</p>
      <div class="key-row">
        <code id="new-key" class="key" th:text="${newKey}">sla_…</code>
        <button type="button" class="button" id="copy-key">Copy</button>
      </div>
    </section>
    <script>
      document.getElementById("copy-key").addEventListener("click", function () {
        var key = document.getElementById("new-key").textContent;
        navigator.clipboard.writeText(key).then(function () {
          document.getElementById("copy-key").textContent = "Copied";
        });
      });
    </script>
  </th:block>

  <section class="card">
    <h2>Add a device</h2>
    <form method="post" th:action="@{/school/devices}" th:object="${form}" novalidate>
      <div class="field">
        <label for="name">Device name</label>
        <input id="name" type="text" th:field="*{name}" placeholder="e.g. My laptop">
        <p class="field-error" th:each="error : ${#fields.errors('name')}" th:text="${error}">This field is required.</p>
      </div>
      <button type="submit" class="button">Add device</button>
    </form>
  </section>

  <section class="card" th:unless="${devices.isEmpty()}">
    <h2>Your devices</h2>
    <ul class="device-list">
      <li class="device" th:each="device : ${devices}">
        <div>
          <strong th:text="${device.name}">My laptop</strong>
          <span class="muted" th:text="|Last check-in: ${@schoolFormat.when(device.lastSeenAt)}|">Last check-in: never</span>
        </div>
        <div class="device-actions">
          <form method="post" th:action="@{/school/devices/{id}/rename(id=${device.id})}" class="inline-form">
            <label class="visually-hidden" th:for="|rename-${device.id}|">New name</label>
            <input th:id="|rename-${device.id}|" name="name" th:value="${device.name}" maxlength="100" required>
            <button type="submit" class="link-button">Rename</button>
          </form>
          <form method="post" th:action="@{/school/devices/{id}/revoke(id=${device.id})}" class="inline-form"
                onsubmit="return confirm('Cancel this device? It will stop syncing immediately.');">
            <button type="submit" class="link-button danger">Cancel device</button>
          </form>
        </div>
      </li>
    </ul>
  </section>
</main>
</body>
</html>
```

- [ ] **Step 5: Run the test to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest=DevicesPageTest)`
Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 302, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school web/src/main/resources/templates/school/devices.html web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java
git commit -m "feat(web): the Devices page in Java: add a laptop, rename it, cancel it"
```

---

### Task 6: README, spec, and checks on MySQL and in a browser

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-26-java-website-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new for later tasks.

- [ ] **Step 1: README**

In `README.md`, section "The Java website (`web/`)", the first paragraph ends with a sentence about what the Java site has so far.

Old:

```text
So far it has login, the shared layout, and the School module's sync API for the laptop agent; the School pages come next.
```

New:

```text
So far it has login, the shared layout, and the School module: the sync API for the laptop agent and the School pages. The laptop agent still syncs into the Python site until the switch.
```

- [ ] **Step 2: Spec status**

In `docs/superpowers/specs/2026-09-26-java-website-design.md`, replace the status line.

Old:

```text
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stage 2a, the sync API and saving, built (docs/superpowers/plans/2026-09-27-java-stage2a-sync-api.md); stage 2b (School pages) and stage 3 to come
```

New:

```text
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stage 2 built in two parts, the sync API and saving (docs/superpowers/plans/2026-09-27-java-stage2a-sync-api.md) and the School pages (docs/superpowers/plans/2026-09-27-java-stage2b-school-pages.md); stage 3 to come
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-09-26-java-website-design.md
git commit -m "docs: the Java School pages and stage 2's status"
```

- [ ] **Step 4: Every test on a real MySQL server, without touching the student's**

As in stage 2a: a private, throwaway MySQL 8.4 server started from the installed program, with its own data folder in the scratchpad (`SCRATCH`), on port 3310, with `--no-defaults`. It never touches the MySQL service on port 3306.

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 8.4/bin"
"$MYSQL/mysqld.exe" --no-defaults --initialize-insecure --basedir="C:/Program Files/MySQL/MySQL Server 8.4" --datadir="$(cygpath -w "$SCRATCH/mysqldata")"
```

Start it in the background (a background shell task, not `&`):

```bash
"$MYSQL/mysqld.exe" --no-defaults --basedir="C:/Program Files/MySQL/MySQL Server 8.4" --datadir="$(cygpath -w "$SCRATCH/mysqldata")" --port=3310 --bind-address=127.0.0.1 --mysqlx=OFF --console
```

When its output says `ready for connections`:

```bash
"$MYSQL/mysql.exe" --no-defaults -uroot -h127.0.0.1 -P3310 -e "CREATE DATABASE sla_web_test CHARACTER SET utf8mb4"
(cd web && SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3310/sla_web_test" SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= ./mvnw -B test)
"$MYSQL/mysqladmin.exe" --no-defaults -uroot -h127.0.0.1 -P3310 shutdown
```

Expected: `Tests run: 302, Failures: 0, Errors: 0, Skipped: 0`, and the log shows `Database version: 8.4.8`. When the background task has ended, `rm -rf "$SCRATCH/mysqldata"`; port 3310 is free and the service on 3306 still runs.

- [ ] **Step 5: Every new page in a real browser**

Start the site on a throwaway in-memory database, with the test settings (it never touches MySQL), in the background:

```bash
(cd web && ./mvnw -B spring-boot:test-run -Dspring-boot.run.arguments="--server.port=8099 --server.servlet.session.cookie.secure=false")
```

When it says `Started SlaWebApplication`, save this as `$SCRATCH/java_school_check.py` and run it with the scratch Playwright environment used in stage 1 (`PYTHONIOENCODING=utf-8 "$SCRATCH/pwenv/Scripts/python.exe" "$SCRATCH/java_school_check.py" "$SCRATCH/shots2b"`, after `mkdir -p "$SCRATCH/shots2b"`). It registers an account and adds a device on the Devices page. Then it uploads made-up data dated around today through the real sync API with that device's key: classes today, tomorrow and later, an exam, tuition, and two Blackboard courses, one announcing tomorrow's class as online. Finally it opens every School page at 1400×1000 and 390×844, in light and dark mode:

```python
"""Browser check of the Java School pages (stage 2b) in Edge, on the throwaway in-memory database at :8099.

Registers an account, adds a device on the Devices page, uploads made-up data dated around today through the
real sync API with that device's key, then opens every School page at desktop and phone size, light and dark.
Usage: python java_school_check.py <screenshot folder>
"""
import json
import sys
import time
import urllib.request
from datetime import date, datetime, timedelta, timezone

from playwright.sync_api import sync_playwright

OUT, BASE = sys.argv[1], "http://127.0.0.1:8099"
BB = "https://blackboard.hcmiu.edu.vn"
VN = timezone(timedelta(hours=7))
SIZES = {"desktop": {"width": 1400, "height": 1000}, "phone": {"width": 390, "height": 844}}
READ = """() => ({width: document.documentElement.scrollWidth, page: getComputedStyle(document.body).backgroundColor,
                  text: getComputedStyle(document.body).color})"""
problems, notes = [], []


def at(day, hhmm):
    hour, minute = map(int, hhmm.split(":"))
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=VN).isoformat()


def api(key, path, body=None):
    request = urllib.request.Request(f"{BASE}/api/school/sync{path}", method="POST" if body is not None else "GET",
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def payload():
    today = datetime.now(VN).date()
    tomorrow, later = today + timedelta(days=1), today + timedelta(days=2)
    month_day = tomorrow.strftime("%B ") + str(tomorrow.day)
    return {
        "schema_version": 1,
        "timetable": {"status": "ok", "data": {"term_code": "20261", "courses": [
            {"course_code": "IT093IU", "course_name": "Web Application Development", "group": "01", "credits": 4,
             "meetings": [{"start_at": at(d, "08:00"), "end_at": at(d, "10:30"), "room": "A2.307"}
                          for d in (today, later, today + timedelta(days=7))]},
            {"course_code": "MA026IU", "course_name": "Probability, Statistic & Random Process",
             "meetings": [{"start_at": at(d, "13:15"), "end_at": at(d, "15:45"), "room": "A2.407"}
                          for d in (tomorrow, tomorrow + timedelta(days=7))]}]}},
        "exams": {"status": "ok", "data": {"term_code": "20261", "exams": [
            {"course_code": "IT093IU", "course_name": "Web Application Development", "exam_type": "midterm",
             "start_at": at(today + timedelta(days=20), "08:00"), "duration_min": 90, "room": "A1.101"}]}},
        "tuition": {"status": "ok", "data": {"term_code": "20261", "amount_due": 12500000, "amount_paid": 0,
                                             "balance": 12500000, "due_date": (today + timedelta(days=18)).isoformat(),
                                             "status_text": "Chưa đóng"}},
        "blackboard": {"status": "ok", "data": {"courses": [
            {"bb_id": "_101_1", "course_code": "IT093IU", "name": "Web Application Development", "url": f"{BB}/c1",
             "announcements": [{"bb_id": "_501_1", "title": "Lab room", "text": "Labs are in A2.508 this week.\n"
                                "Bring your laptop.", "posted_at": datetime.now(timezone.utc).isoformat(), "url": f"{BB}/a1"}],
             "assignments": [
                 {"bb_id": "_701_1", "name": "Lab 3", "due_at": at(later, "23:59"), "status": "not_graded", "url": f"{BB}/x1"},
                 {"bb_id": "_702_1", "name": "Missed quiz", "due_at": at(today - timedelta(days=2), "23:59"),
                  "status": "not_graded", "url": f"{BB}/x2"},
                 {"bb_id": "_703_1", "name": "Lab 2", "due_at": at(today - timedelta(days=5), "23:59"), "status": "graded",
                  "score": 8.5, "points_possible": 10, "feedback": "Good work", "url": f"{BB}/x3"}],
             "materials": [{"bb_id": "_902_1", "title": "Week 5 slides.pdf", "kind": "file", "path": "Week 5",
                            "created_at": datetime.now(timezone.utc).isoformat(), "url": f"{BB}/m1"}]},
            {"bb_id": "_102_1", "course_code": "MA026IU", "name": "Probability, Statistic & Random Process",
             "url": f"{BB}/c2",
             "announcements": [{"bb_id": "_601_1", "title": f"ONLINE CLASS ON {month_day.upper()}",
                                "text": f"Dear all, the class on {month_day} is online on MS Teams.",
                                "posted_at": datetime.now(timezone.utc).isoformat(), "url": f"{BB}/a2"}]}]}},
    }


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    setup = browser.new_page()
    email = f"an.school.{int(time.time())}@example.com"
    setup.goto(f"{BASE}/auth/register")
    setup.fill("#email", email); setup.fill("#displayName", "An")
    setup.fill("#password", "correct-horse-8"); setup.fill("#confirm", "correct-horse-8")
    setup.click("button[type=submit]")
    setup.goto(f"{BASE}/school")
    notes.append(f"before any device: {setup.locator('.status-headline').inner_text()!r}")
    setup.goto(f"{BASE}/school/devices")
    setup.fill("#name", "My laptop")
    setup.click("form[action='/school/devices'] button[type=submit]")
    key = setup.locator("#new-key").inner_text()
    setup.screenshot(path=f"{OUT}/school-desktop-light-devices-new-key.png", full_page=True)
    notes.append(f"check-in: {api(key, '/check')}")
    run_id = api(key, "/runs", {"trigger": "manual"})["run_id"]
    notes.append(f"finish: {api(key, f'/runs/{run_id}/finish', payload())}")
    cookies = setup.context.cookies()
    setup.goto(f"{BASE}/school/courses")
    course_link = setup.locator("a.module-card").first.get_attribute("href")
    setup.close()

    pages = ["/school", "/school/timetable", "/school/courses", course_link, "/school/exams", "/school/tuition",
             "/school/devices"]
    for scheme in ("light", "dark"):
        for size, viewport in SIZES.items():
            tag = f"{size}-{scheme}"
            context = browser.new_context(viewport=viewport, color_scheme=scheme)
            context.add_cookies(cookies)
            page = context.new_page()
            page.on("console", lambda m, t=tag: problems.append(f"{t} console {m.type}: {m.text}")
                    if m.type in ("error", "warning") else None)
            page.on("pageerror", lambda e, t=tag: problems.append(f"{t} page error: {e}"))
            page.on("response", lambda r, t=tag: problems.append(f"{t} {r.status} {r.url}") if r.status >= 400 else None)
            for path in pages:
                page.goto(f"{BASE}{path}")
                if path == "/school/timetable":
                    page.wait_for_selector(".fc-event", timeout=10000)
                info = page.evaluate(READ)
                if info["width"] > viewport["width"]:
                    problems.append(f"{tag} {path}: page {info['width']}px wide on a {viewport['width']}px screen")
                name = path.strip("/").replace("/", "-") or "school"
                page.screenshot(path=f"{OUT}/school-{tag}-{name}.png", full_page=True)
                if scheme == "light" and size == "desktop":
                    notes.append(f"{path}: bg={info['page']} text={info['text']}")
            if scheme == "light" and size == "desktop":
                page.goto(f"{BASE}/school")
                notes.append("overview items: " + " | ".join(page.locator("ul.items li").all_inner_texts()))
                notes.append("status: " + page.locator(".status-card").inner_text().replace("\n", " / "))
                page.goto(f"{BASE}/school/timetable")
                page.wait_for_selector(".fc-event", timeout=10000)
                notes.append("calendar events: " + " | ".join(page.locator(".fc-event").all_inner_texts()[:12]))
            context.close()
    browser.close()

print("\n".join(notes))
print("problems:", problems or "none")
```

Expected: `finish: {'status': 'success'}`; the Overview's items include today's class, tomorrow's class with the `Online` badge and `See announcement`, and the overdue `Missed quiz`; the status reads `Synced at …` with an EduSoft and a Blackboard line; every page `bg=rgb(246, 247, 249) text=rgb(0, 0, 0)`; `problems: none`. Look at the screenshots in `$SCRATCH/shots2b`: the same look as the Python site's School pages, on the phone too.

Stop the background site, then make sure no Java process still listens on port 8099:

```bash
netstat -ano | grep ":8099 .*LISTENING"
```

Expected: nothing; otherwise `taskkill //F //PID <pid>`.

- [ ] **Step 6: For the student (optional): your own data**

With the Java site running normally (`cd web && ./mvnw spring-boot:run`, on your database and port 8080), log in at http://127.0.0.1:8080/school with your usual account. Its School pages should show the same timetable, exams, tuition, courses and changes as http://127.0.0.1:5000/school/. Only you can log in as yourself, so this check is yours.
