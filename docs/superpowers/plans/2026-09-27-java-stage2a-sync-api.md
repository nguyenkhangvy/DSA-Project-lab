# Java Stage 2a: Sync API and Saving Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Java website accepts the laptop agent's uploads exactly like the Python website: the three sync addresses, the device-key check, the data-format checks, saving each part, and the "What changed" lines.

**Architecture:** Two new packages in `web/`. `vn.edu.hcmiu.sla.school.model` maps the School tables the Python site already made (JPA classes plus Spring Data repositories; no new tables). `vn.edu.hcmiu.sla.school.sync` holds the upload format as Java records read by a strict Jackson reader, the "is a sync due?" rule, device keys, sync runs, saving with the feed texts, and the REST controller behind its own stateless security chain. Example uploads in `contract/samples/` are checked by both the Python and the Java tests, so the two sides can't drift. The School pages are stage 2b (a separate plan).

**Tech Stack:** Java 17, Spring Boot 4.1.1 (Spring MVC, Spring Security 7, Spring Data JPA / Hibernate 7 with JSON columns, Bean Validation), Jackson 3 (`tools.jackson`, already part of Spring Boot 4), JUnit 5 + MockMvc, H2 for tests; pytest on the Python side.

**Spec:** `docs/superpowers/specs/2026-09-26-java-website-design.md` — §5.1 (sync API), §5.2 (data format and shared samples), the saving part of §5.3, §5.4, §6, §8.

**Tried first:** every file below was built and run in a scratch copy of `web/` before this plan was written: 166 Java tests passed on H2 and on a real MySQL 8.4 server, the new classes passed Hibernate's check against the student's real tables, and the Python suite passed with the shared samples (425 tests).

## Global Constraints

- Java 17 language level: no Java 21 methods such as `List.getFirst()` / `getLast()`.
- No new dependencies; `web/pom.xml` stays as stage 1 left it.
- No new tables and no Flyway migration in this stage. The Python site stays in daily use and changes no tables (spec §6).
- Sync API exactly as spec §5.1: `GET /api/school/sync/check`, `POST /api/school/sync/runs`, `POST /api/school/sync/runs/{id}/finish`; `Authorization: Bearer <device key>`; 401 `{"error": "invalid_device_key"}`; bodies up to 5,000,000 bytes, else 413 `{"error": "payload_too_large"}`; 422 `{"error": "invalid_payload", "details": [{"loc": [...], "msg": "..."}]}` that never repeats the input; 201 `{"run_id": …}`; 409 `{"error": "run_in_progress"}` / `{"error": "run_not_running"}`; 404 for another user's or an unknown run; 200 `{"status": …}`; `{"due": …, "reason": …, "interval_hours": …}`.
- Upload format = `contract/sla_contract/schema.py`, schema version 1: same fields and limits, unknown fields refused, text trimmed, times must carry an offset, Blackboard links must start with `https://blackboard.hcmiu.edu.vn/`.
- Times are stored as UTC without an offset (`LocalDateTime`, as the Python site stores them) and shown in Vietnam time (+07:00) in feed texts.
- "What changed" texts word for word as `app/school/services/changes.py`.
- Tests never touch the real database: H2 by default; GitHub also runs them on a throwaway MySQL.
- Never print `.env` values, the student ID, or the device key.
- Imports grouped as in stage 1: static; `java`; `jakarta`; `org`; `tools`; `vn` — one blank line between groups.
- Commands below are for Git Bash, run from the repository root.

## Review Focus

The failure modes most likely to bite that the spec implies but a quick read of the tests might miss, and the test that pins each:

1. **MySQL isn't H2.** MySQL re-sorts the keys inside JSON columns (shortest first), and a `FLOAT` column would change a score like 6.666666667; code or tests that rely on either break only on MySQL. Pinned by `SchoolTablesTest.aRunKeepsHowEachPartWent` and `longTextsAndExactScoresAreKept` (Task 1); Task 8 runs the whole suite on MySQL.
2. **Uploads exactly as the Python agent writes them:** letters with diacritics escaped as `\uXXXX`, UTC times written with `Z` and fractions of a second, whole numbers for decimal fields (`"credits": 4`). Pinned by `Payloads.bytes` (writes like the agent), `SyncContractTest.timesAsPydanticWritesThemAreAccepted` (Task 3) and `SyncApiTest.aHeavySemesterOfBlackboardDataInVietnameseIsAccepted` (Task 7).
3. **The API's open door stays small.** The sync API's security chain lets requests through without login or CSRF token; it must cover only `/api/school/sync/**`. Pinned by `SyncApiTest.theApiNeedsNoLoginAndNoCsrfTokenButTheRestOfTheSiteStillDoes` (Task 7).
4. **Personal data in error answers.** A 422 must say where and why, never repeat the value. Pinned by `SyncContractTest.unknownFieldsAreRejectedSoNoExtraPersonalDataGetsIn` (Task 3) and `SyncApiTest.anInvalidUploadGets422WithoutRepeatingItAndLeavesTheRunRunning` (Task 7).
5. **The same data twice makes no news.** Syncing identical data again must add no feed lines (a score read back differently would announce a "New grade" every sync). Pinned by `IngestTest.anUnchangedBlackboardSyncAddsNoFeedLines` (Task 6) and `SchoolTablesTest.longTextsAndExactScoresAreKept` (Task 1).

## Decisions made while trying it out

- **The device key is checked by a Spring MVC interceptor**, like the Python site's `before_request`, behind a separate `SecurityFilterChain` for `/api/school/sync/**` that is stateless, without CSRF and without a login redirect. Simpler for the team to read than a custom Spring Security authentication provider.
- **The JSON reader is our own `JsonMapper`**, not Spring's shared one, so its strict settings (unknown fields refused, no fractions for whole numbers, text trimmed, text must be a JSON string, offsets kept) can't change other JSON in the site.
- **404 answers carry `{"error": "not_found"}`** (the Python site sent its HTML 404 page). The agent only reads the status code.
- **Lists that are missing or `null` count as empty** (pydantic refuses an explicit `null` list). Only the agent sends uploads, and it never sends `null` lists.
- **`school_events` isn't mapped**: the Python site never uses that table.
- **`stripSpaces` moves from `AppUserDetailsService` to `core/Text.strip`**, because the School module needs it too.
- **The Python test helpers read the shared samples** instead of keeping their own copies of the same uploads.

## File Structure

```
contract/samples/                              example uploads both test suites check (Task 2)
  finish-edusoft.json                          timetable, exams, tuition all ok (the old full_payload())
  finish-blackboard.json                       a Blackboard part (its data is the old blackboard_payload())
  finish-parts-failed.json                     some parts failed
  finish-whole-run-error.json                  the whole run failed
  invalid/*.json                               six uploads that must be refused, each for one reason
contract/sla_contract/schema.py                docstring points at the Java twin (Task 2)
tests/helpers.py, tests/test_contract.py       Python tests read the samples (Task 2)
web/src/main/java/vn/edu/hcmiu/sla/
  core/Text.java                               strip() like Python's str.strip() (Task 3)
  auth/AppUserDetailsService.java              uses Text.strip (Task 3)
  auth/RegisterForm.java                       uses Text.strip (Task 3)
  school/model/School*.java                    one class per School table (Task 1)
  school/model/School*Repository.java          one repository per class (Task 1)
  school/sync/SyncContract.java                the upload format as records (Task 3)
  school/sync/SyncJson.java                    strict JSON reading, 5 MB limit, error details (Task 3)
  school/sync/Changes.java                     "What changed" texts (Task 4)
  school/sync/Scheduling.java                  is a sync due? (Task 5)
  school/sync/Ingest.java                      saving a finished run (Task 6)
  school/sync/DeviceKeys.java                  device keys (Task 7)
  school/sync/SyncRuns.java                    settings, latest runs, starting a run (Task 7)
  school/sync/DeviceKeyInterceptor.java        the key check on every API request (Task 7)
  school/sync/SyncApiConfig.java               the API's own security chain (Task 7)
  school/sync/SyncApiController.java           the three addresses (Task 7)
web/src/test/java/vn/edu/hcmiu/sla/school/
  model/SchoolTablesTest.java                  (Task 1)
  sync/Payloads.java, sync/SyncContractTest.java   (Task 3)
  sync/ChangesTest.java                        (Task 4)
  sync/SchedulingTest.java                     (Task 5)
  sync/IngestTest.java                         (Task 6)
  sync/SyncApiTest.java                        (Task 7)
README.md, docs/superpowers/specs/2026-09-26-java-website-design.md   (Task 8)
```

Test counts: stage 1 left 42 Java tests. After each task: Task 1 → 45, Task 3 → 89, Task 4 → 123, Task 5 → 136, Task 6 → 148, Task 7 → 166. Python: 415 now, 425 after Task 2.

---

### Task 1: The School tables as Java classes

The Python site's Alembic migrations made these tables; Flyway's `V1__baseline.sql` (stage 1) describes them. Each class maps one table exactly, and Hibernate's `ddl-auto=validate` compares every class with the table whenever a test starts Spring. `text` and `feedback` are MySQL `TEXT`, hence `columnDefinition = "TEXT"`; `items` and `sections` are JSON columns (`@JdbcTypeCode(SqlTypes.JSON)`, written and read by Jackson 3); `trigger` is a MySQL keyword, hence the backticks.

**Files:**
- Create (in `web/src/main/java/vn/edu/hcmiu/sla/school/model/`): `SchoolCourse.java`, `SchoolClassMeeting.java`, `SchoolExam.java`, `SchoolTuition.java`, `SchoolSyncDevice.java`, `SchoolSyncSettings.java`, `SchoolSyncRun.java`, `SchoolChange.java`, `SchoolBbCourse.java`, `SchoolBbAnnouncement.java`, `SchoolBbAssignment.java`, `SchoolBbMaterial.java`, and `SchoolCourseRepository.java`, `SchoolClassMeetingRepository.java`, `SchoolExamRepository.java`, `SchoolTuitionRepository.java`, `SchoolSyncDeviceRepository.java`, `SchoolSyncSettingsRepository.java`, `SchoolSyncRunRepository.java`, `SchoolChangeRepository.java`, `SchoolBbCourseRepository.java`, `SchoolBbAnnouncementRepository.java`, `SchoolBbAssignmentRepository.java`, `SchoolBbMaterialRepository.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`

**Interfaces:**
- Consumes: `vn.edu.hcmiu.sla.auth.User(String email, String displayName, String passwordHash, LocalDateTime createdAt)` and `UserRepository` (stage 1).
- Produces (package `vn.edu.hcmiu.sla.school.model`; every class has getters for its columns; all times are UTC `LocalDateTime`; user ids are `Integer`):
  - `SchoolCourse(Integer userId, String termCode, String courseCode, String courseName, String groupCode, BigDecimal credits, String lecturer)`; `addMeeting(LocalDateTime startAt, LocalDateTime endAt, String room)`; `getMeetings()`
  - `SchoolClassMeeting`: `getCourse()`, `getStartAt()`, `getEndAt()`, `getRoom()` (made through `SchoolCourse.addMeeting`)
  - `SchoolExam(Integer userId, String termCode, String courseCode, String courseName, String examType, LocalDateTime startAt, Integer durationMin, String room, String notes)`
  - `SchoolTuition(Integer userId, String termCode, long amountDue, long amountPaid, long balance, LocalDate dueDate, String statusText, List<SchoolTuition.Item> items)`; `record SchoolTuition.Item(String description, long amount)`
  - `SchoolSyncDevice(Integer userId, String name, String tokenHash, LocalDateTime createdAt)`; `setName`, `setLastSeenAt`, `setRevokedAt`
  - `SchoolSyncSettings(Integer userId, int intervalHours)`; `DEFAULT_INTERVAL_HOURS = 12`; `setIntervalHours`, `setSyncRequestedAt`
  - `SchoolSyncRun(Integer userId, Integer deviceId, String trigger, LocalDateTime startedAt)` (status `running`); constants `RUNNING`, `SUCCESS`, `PARTIAL`, `FAILED`; `finish(String status, LocalDateTime finishedAt, String errorCode, String errorMessage)`; `setStartedAt`; `Map<String, Map<String, String>> getSections()` / `setSections(...)`
  - `SchoolChange(Integer userId, Integer syncRunId, String section, String kind, String summary, LocalDateTime createdAt)`; `setSeenAt`
  - `SchoolBbCourse(Integer userId, String bbId, String courseCode, String name, String url)`; `getAnnouncements()`, `getAssignments()`, `getMaterials()` (saved with the course)
  - `SchoolBbAnnouncement(SchoolBbCourse course, String bbId, String title, String text, LocalDateTime postedAt, String url)`
  - `SchoolBbAssignment(SchoolBbCourse course, String bbId, String name, LocalDateTime dueAt, Double pointsPossible, Double score, String gradeText, String status, String feedback, String url)`
  - `SchoolBbMaterial(SchoolBbCourse course, String bbId, String title, String kind, String path, LocalDateTime createdAt, String url)`
  - Repositories (all `JpaRepository<…, Integer>`): `SchoolCourseRepository.existsByUserIdAndTermCode(userId, termCode)`, `deleteTerm(userId, termCode)`; `SchoolClassMeetingRepository.findTerm(userId, termCode)` (with each meeting's course), `deleteTerm(userId, termCode)`; `SchoolExamRepository.findByUserIdAndTermCode`, `deleteTerm`; `SchoolTuitionRepository.findByUserIdAndTermCode` (`Optional`), `deleteTerm`; `SchoolSyncDeviceRepository.findByTokenHashAndRevokedAtIsNull(hash)`; `SchoolSyncSettingsRepository` (id = user id); `SchoolSyncRunRepository.findFirstByUserIdOrderByStartedAtDescIdDesc(userId)`, `findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(userId, statuses)`, `findByUserIdAndStatus(userId, status)`; `SchoolChangeRepository.findBySyncRunIdOrderById(runId)`; `SchoolBbCourseRepository`, `SchoolBbAnnouncementRepository`, `SchoolBbAssignmentRepository`, `SchoolBbMaterialRepository`: each `findByUserIdOrderById(userId)` and `deleteAllOfUser(userId)`

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/model/SchoolTablesTest.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;

/** The School classes fit the tables the Python site made, including the JSON and TEXT columns. */
@SpringBootTest
@Transactional
class SchoolTablesTest {

    static final LocalDateTime SEPT_28 = LocalDateTime.of(2026, 9, 28, 1, 0);

    @Autowired
    EntityManager db;

    @Autowired
    UserRepository users;

    Integer userId;

    @BeforeEach
    void user() {
        userId = users.save(new User("an@example.com", "An", "x", SEPT_28)).getId();
    }

    <T> T reloaded(T row, Object id) {
        db.flush();
        db.clear();
        @SuppressWarnings("unchecked")
        T again = (T) db.find(row.getClass(), id);
        return again;
    }

    @Test
    void tuitionItemsAreKeptAsJson() {
        SchoolTuition tuition = new SchoolTuition(userId, "20261", 12_500_000, 0, 12_500_000,
                LocalDate.of(2026, 10, 15), "Chưa đóng", List.of(new SchoolTuition.Item("Học phí", 12_500_000)));
        db.persist(tuition);

        SchoolTuition again = reloaded(tuition, tuition.getId());

        assertThat(again.getItems()).containsExactly(new SchoolTuition.Item("Học phí", 12_500_000));
        assertThat(again.getStatusText()).isEqualTo("Chưa đóng");
    }

    @Test
    void aRunKeepsHowEachPartWent() {
        SchoolSyncRun run = new SchoolSyncRun(userId, null, "manual", SEPT_28);
        run.setSections(Map.of("timetable", Map.of("status", "ok"),
                "tuition", Map.of("status", "failed", "error_code", "edusoft_changed")));
        db.persist(run);

        SchoolSyncRun again = reloaded(run, run.getId());

        // MySQL keeps JSON keys in its own order (shortest first), so pages must not rely on the order.
        assertThat(again.getSections().keySet()).containsExactlyInAnyOrder("timetable", "tuition");
        assertThat(again.getSections().get("tuition")).containsEntry("error_code", "edusoft_changed");
        assertThat(again.getTrigger()).isEqualTo("manual");
    }

    @Test
    void longTextsAndExactScoresAreKept() {
        SchoolBbCourse course = new SchoolBbCourse(userId, "_101_1", "IT093IU", "Web", "https://blackboard.hcmiu.edu.vn/x");
        String text = "Thông báo: lớp học bù vào thứ Năm. ".repeat(140);
        course.getAnnouncements().add(new SchoolBbAnnouncement(course, "_501_1", "Hi", text, SEPT_28, course.getUrl()));
        course.getAssignments().add(new SchoolBbAssignment(course, "_701_1", "Lab 3", null, 10.0, 6.666666667, null,
                "graded", "Good work ".repeat(100), course.getUrl()));
        db.persist(course);

        SchoolBbCourse again = reloaded(course, course.getId());

        assertThat(again.getAnnouncements().get(0).getText()).isEqualTo(text);
        assertThat(again.getAssignments().get(0).getScore()).isEqualTo(6.666666667);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=SchoolTablesTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `SchoolTuition`, `SchoolSyncRun`, `SchoolBbCourse`.

- [ ] **Step 3: Write the table classes**

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolCourse.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** One course of a term, from EduSoft's timetable. */
@Entity
@Table(name = "school_courses")
public class SchoolCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    @Column(name = "course_name", nullable = false, length = 255)
    private String courseName;

    @Column(name = "group_code", length = 20)
    private String groupCode;

    @Column(precision = 4, scale = 1)
    private BigDecimal credits;

    @Column(length = 255)
    private String lecturer;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolClassMeeting> meetings = new ArrayList<>();

    protected SchoolCourse() {
    }

    public SchoolCourse(Integer userId, String termCode, String courseCode, String courseName, String groupCode,
            BigDecimal credits, String lecturer) {
        this.userId = userId;
        this.termCode = termCode;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.groupCode = groupCode;
        this.credits = credits;
        this.lecturer = lecturer;
    }

    /** Times are UTC. */
    public void addMeeting(LocalDateTime startAt, LocalDateTime endAt, String room) {
        meetings.add(new SchoolClassMeeting(userId, this, startAt, endAt, room));
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTermCode() {
        return termCode;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public BigDecimal getCredits() {
        return credits;
    }

    public String getLecturer() {
        return lecturer;
    }

    public List<SchoolClassMeeting> getMeetings() {
        return meetings;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolClassMeeting.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One real class session (EduSoft's week pattern expanded to dates). Times are UTC. */
@Entity
@Table(name = "school_class_meetings")
public class SchoolClassMeeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolCourse course;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(length = 50)
    private String room;

    protected SchoolClassMeeting() {
    }

    SchoolClassMeeting(Integer userId, SchoolCourse course, LocalDateTime startAt, LocalDateTime endAt, String room) {
        this.userId = userId;
        this.course = course;
        this.startAt = startAt;
        this.endAt = endAt;
        this.room = room;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolCourse getCourse() {
        return course;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public String getRoom() {
        return room;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolExam.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One exam from EduSoft's exam schedule. The start time is UTC. */
@Entity
@Table(name = "school_exams")
public class SchoolExam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    @Column(name = "course_name", nullable = false, length = 255)
    private String courseName;

    @Column(name = "exam_type", nullable = false, length = 10)
    private String examType; // midterm / final / other

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(length = 50)
    private String room;

    @Column(length = 500)
    private String notes;

    protected SchoolExam() {
    }

    public SchoolExam(Integer userId, String termCode, String courseCode, String courseName, String examType,
            LocalDateTime startAt, Integer durationMin, String room, String notes) {
        this.userId = userId;
        this.termCode = termCode;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.examType = examType;
        this.startAt = startAt;
        this.durationMin = durationMin;
        this.room = room;
        this.notes = notes;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTermCode() {
        return termCode;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getExamType() {
        return examType;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public String getRoom() {
        return room;
    }

    public String getNotes() {
        return notes;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuition.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A term's tuition from EduSoft. Amounts are VND; a negative balance means overpaid. */
@Entity
@Table(name = "school_tuition")
public class SchoolTuition {

    /** One line of the tuition bill, kept in the JSON column {@code items}. */
    public record Item(String description, long amount) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "amount_due", nullable = false)
    private long amountDue;

    @Column(name = "amount_paid", nullable = false)
    private long amountPaid;

    @Column(nullable = false)
    private long balance;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status_text", length = 255)
    private String statusText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Item> items;

    protected SchoolTuition() {
    }

    public SchoolTuition(Integer userId, String termCode, long amountDue, long amountPaid, long balance,
            LocalDate dueDate, String statusText, List<Item> items) {
        this.userId = userId;
        this.termCode = termCode;
        this.amountDue = amountDue;
        this.amountPaid = amountPaid;
        this.balance = balance;
        this.dueDate = dueDate;
        this.statusText = statusText;
        this.items = items;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTermCode() {
        return termCode;
    }

    public long getAmountDue() {
        return amountDue;
    }

    public long getAmountPaid() {
        return amountPaid;
    }

    public long getBalance() {
        return balance;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getStatusText() {
        return statusText;
    }

    public List<Item> getItems() {
        return items;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncDevice.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A laptop allowed to upload EduSoft and Blackboard data. Only the key's SHA-256 hash is stored. */
@Entity
@Table(name = "school_sync_devices")
public class SchoolSyncDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected SchoolSyncDevice() {
    }

    public SchoolSyncDevice(Integer userId, String name, String tokenHash, LocalDateTime createdAt) {
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncSettings.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How often the laptop syncs, and when "Sync now" was last pressed. One row per user. */
@Entity
@Table(name = "school_sync_settings")
public class SchoolSyncSettings {

    public static final int DEFAULT_INTERVAL_HOURS = 12;

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours;

    @Column(name = "sync_requested_at")
    private LocalDateTime syncRequestedAt; // UTC

    protected SchoolSyncSettings() {
    }

    public SchoolSyncSettings(Integer userId, int intervalHours) {
        this.userId = userId;
        this.intervalHours = intervalHours;
    }

    public Integer getUserId() {
        return userId;
    }

    public int getIntervalHours() {
        return intervalHours;
    }

    public void setIntervalHours(int intervalHours) {
        this.intervalHours = intervalHours;
    }

    public LocalDateTime getSyncRequestedAt() {
        return syncRequestedAt;
    }

    public void setSyncRequestedAt(LocalDateTime syncRequestedAt) {
        this.syncRequestedAt = syncRequestedAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncRun.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One sync by the laptop: when it ran, how it ended, and how each part went. Times are UTC. */
@Entity
@Table(name = "school_sync_runs")
public class SchoolSyncRun {

    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "device_id")
    private Integer deviceId; // empty once the device is deleted

    @Column(name = "`trigger`", nullable = false, length = 20) // quoted: TRIGGER is a MySQL keyword
    private String trigger; // scheduled / manual / import

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(nullable = false, length = 10)
    private String status; // running / success / partial / failed

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Per part: {"timetable": {"status": "ok"}, "tuition": {"status": "failed", "error_code": …}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Map<String, String>> sections;

    protected SchoolSyncRun() {
    }

    public SchoolSyncRun(Integer userId, Integer deviceId, String trigger, LocalDateTime startedAt) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.status = RUNNING;
    }

    /** Ends the run. */
    public void finish(String status, LocalDateTime finishedAt, String errorCode, String errorMessage) {
        this.status = status;
        this.finishedAt = finishedAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public String getTrigger() {
        return trigger;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Map<String, String>> getSections() {
        return sections;
    }

    public void setSections(Map<String, Map<String, String>> sections) {
        this.sections = sections;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolChange.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One line in the "What changed" feed. */
@Entity
@Table(name = "school_changes")
public class SchoolChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "sync_run_id", nullable = false)
    private Integer syncRunId;

    @Column(nullable = false, length = 20)
    private String section; // timetable / exams / tuition / blackboard

    @Column(nullable = false, length = 10)
    private String kind; // added / removed / changed

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    @Column(name = "seen_at")
    private LocalDateTime seenAt;

    protected SchoolChange() {
    }

    public SchoolChange(Integer userId, Integer syncRunId, String section, String kind, String summary,
            LocalDateTime createdAt) {
        this.userId = userId;
        this.syncRunId = syncRunId;
        this.section = section;
        this.kind = kind;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getSyncRunId() {
        return syncRunId;
    }

    public String getSection() {
        return section;
    }

    public String getKind() {
        return kind;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(LocalDateTime seenAt) {
        this.seenAt = seenAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbCourse.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** A Blackboard course, with its announcements, assignments and materials. */
@Entity
@Table(name = "school_bb_courses")
public class SchoolBbCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(name = "course_code", length = 20)
    private String courseCode;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 500)
    private String url;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbAnnouncement> announcements = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbAssignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbMaterial> materials = new ArrayList<>();

    protected SchoolBbCourse() {
    }

    public SchoolBbCourse(Integer userId, String bbId, String courseCode, String name, String url) {
        this.userId = userId;
        this.bbId = bbId;
        this.courseCode = courseCode;
        this.name = name;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getBbId() {
        return bbId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public List<SchoolBbAnnouncement> getAnnouncements() {
        return announcements;
    }

    public List<SchoolBbAssignment> getAssignments() {
        return assignments;
    }

    public List<SchoolBbMaterial> getMaterials() {
        return materials;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAnnouncement.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An announcement in a Blackboard course. */
@Entity
@Table(name = "school_bb_announcements")
public class SchoolBbAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolBbCourse course;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "posted_at")
    private LocalDateTime postedAt; // UTC

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbAnnouncement() {
    }

    public SchoolBbAnnouncement(SchoolBbCourse course, String bbId, String title, String text,
            LocalDateTime postedAt, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolBbCourse getCourse() {
        return course;
    }

    public String getBbId() {
        return bbId;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public LocalDateTime getPostedAt() {
        return postedAt;
    }

    public String getUrl() {
        return url;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAssignment.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An assignment in a Blackboard course, with its grade once there is one. */
@Entity
@Table(name = "school_bb_assignments")
public class SchoolBbAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolBbCourse course;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "due_at")
    private LocalDateTime dueAt; // UTC

    // DOUBLE: MySQL's FLOAT keeps about 7 digits, so a score like 6.666666667 would change on every read.
    @Column(name = "points_possible")
    private Double pointsPossible;

    private Double score;

    @Column(name = "grade_text", length = 50)
    private String gradeText;

    @Column(nullable = false, length = 20)
    private String status; // not_graded / needs_grading / graded / exempt

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbAssignment() {
    }

    public SchoolBbAssignment(SchoolBbCourse course, String bbId, String name, LocalDateTime dueAt,
            Double pointsPossible, Double score, String gradeText, String status, String feedback, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.name = name;
        this.dueAt = dueAt;
        this.pointsPossible = pointsPossible;
        this.score = score;
        this.gradeText = gradeText;
        this.status = status;
        this.feedback = feedback;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolBbCourse getCourse() {
        return course;
    }

    public String getBbId() {
        return bbId;
    }

    public String getName() {
        return name;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public Double getPointsPossible() {
        return pointsPossible;
    }

    public Double getScore() {
        return score;
    }

    public String getGradeText() {
        return gradeText;
    }

    public String getStatus() {
        return status;
    }

    public String getFeedback() {
        return feedback;
    }

    public String getUrl() {
        return url;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbMaterial.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A file, folder, link or page in a Blackboard course's content. */
@Entity
@Table(name = "school_bb_materials")
public class SchoolBbMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolBbCourse course;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 10)
    private String kind; // file / folder / link / document / other

    @Column(nullable = false, length = 500)
    private String path; // the folders it is in, e.g. "Week 5"

    @Column(name = "created_at")
    private LocalDateTime createdAt; // UTC

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbMaterial() {
    }

    public SchoolBbMaterial(SchoolBbCourse course, String bbId, String title, String kind, String path,
            LocalDateTime createdAt, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.title = title;
        this.kind = kind;
        this.path = path;
        this.createdAt = createdAt;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolBbCourse getCourse() {
        return course;
    }

    public String getBbId() {
        return bbId;
    }

    public String getTitle() {
        return title;
    }

    public String getKind() {
        return kind;
    }

    public String getPath() {
        return path;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getUrl() {
        return url;
    }
}
```

- [ ] **Step 4: Write the repositories**

Deletes that replace a whole term use one `delete … where …` statement (`@Modifying @Query`), not Spring's `deleteBy…` methods, which load and delete rows one by one.

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolCourseRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolCourseRepository extends JpaRepository<SchoolCourse, Integer> {

    boolean existsByUserIdAndTermCode(Integer userId, String termCode);

    /** Deletes a term's courses; delete their classes first. */
    @Modifying
    @Query("delete from SchoolCourse c where c.userId = :userId and c.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolClassMeetingRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

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
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolExamRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Integer> {

    List<SchoolExam> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolExam e where e.userId = :userId and e.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuitionRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolTuitionRepository extends JpaRepository<SchoolTuition, Integer> {

    Optional<SchoolTuition> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolTuition t where t.userId = :userId and t.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncDeviceRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncDeviceRepository extends JpaRepository<SchoolSyncDevice, Integer> {

    Optional<SchoolSyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncSettingsRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncSettingsRepository extends JpaRepository<SchoolSyncSettings, Integer> {
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolSyncRunRepository.java`:

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
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolChangeRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolChangeRepository extends JpaRepository<SchoolChange, Integer> {

    List<SchoolChange> findBySyncRunIdOrderById(Integer syncRunId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbCourseRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbCourseRepository extends JpaRepository<SchoolBbCourse, Integer> {

    List<SchoolBbCourse> findByUserIdOrderById(Integer userId);

    /** Deletes the user's Blackboard courses with their announcements, assignments and materials. */
    @Modifying
    @Query("delete from SchoolBbCourse c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAnnouncementRepository.java`:

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
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbAssignmentRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAssignmentRepository extends JpaRepository<SchoolBbAssignment, Integer> {

    List<SchoolBbAssignment> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAssignment a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolBbMaterialRepository.java`:

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
}
```

- [ ] **Step 5: Run the test to make sure it passes**

Run: `(cd web && ./mvnw -B -q test -Dtest=SchoolTablesTest)`
Expected: exit code 0; `web/target/surefire-reports/vn.edu.hcmiu.sla.school.model.SchoolTablesTest.txt` says `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run every test**

Every test that starts Spring now also checks the 12 new classes against the tables.

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/model web/src/test/java/vn/edu/hcmiu/sla/school/model
git commit -m "feat(web): the School tables as Java classes"
```

---

### Task 2: Shared sample uploads, checked by the Python tests

Spec §5.2: `contract/samples/` holds example uploads that both sides check. The valid ones are exactly the uploads the Python tests already use (`full_payload()`, `blackboard_payload()`), so the Python helpers now read them from the files. Each file in `invalid/` is wrong for one reason only (checked while writing this plan: a fixed copy of each is accepted).

**Files:**
- Create: `contract/samples/finish-edusoft.json`, `contract/samples/finish-blackboard.json`, `contract/samples/finish-parts-failed.json`, `contract/samples/finish-whole-run-error.json`, `contract/samples/invalid/class-ends-before-it-starts.json`, `contract/samples/invalid/link-to-another-site.json`, `contract/samples/invalid/ok-part-without-data.json`, `contract/samples/invalid/time-without-timezone.json`, `contract/samples/invalid/unknown-field.json`, `contract/samples/invalid/whole-run-error-with-data.json`
- Modify: `tests/helpers.py` (replaced), `tests/test_contract.py` (replaced), `contract/sla_contract/schema.py` (docstring)

**Interfaces:**
- Consumes: nothing new.
- Produces: the sample files (Task 3's Java tests read them through `Payloads`); `tests.helpers.SAMPLES` (a `Path`); `full_payload()` returns `finish-edusoft.json`, `blackboard_payload()` returns the `blackboard.data` of `finish-blackboard.json`.

- [ ] **Step 1: Point the Python tests at the samples**

Replace `tests/helpers.py` with:

```python
import json
import os
from pathlib import Path

from app.config import load_config

# Tests use a throwaway in-memory SQLite database by default.
# CI sets TEST_DATABASE_URL to a real MySQL database.
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", "sqlite://")


def make_config(**overrides):
    config = load_config({"SECRET_KEY": "test-secret", "DATABASE_URL": TEST_DATABASE_URL})
    config.update(TESTING=True, WTF_CSRF_ENABLED=False)
    config.update(overrides)
    return config


def register(client, email="an@example.com", display_name="An", password="correct-horse", confirm=None):
    return client.post(
        "/auth/register",
        data={
            "email": email,
            "display_name": display_name,
            "password": password,
            "confirm": password if confirm is None else confirm,
        },
    )


def login(client, email="an@example.com", password="correct-horse", next_url=None):
    query = {} if next_url is None else {"next": next_url}
    return client.post("/auth/login", query_string=query, data={"email": email, "password": password})


def logout(client):
    return client.post("/auth/logout")


def make_user(app, email="an@example.com", display_name="An"):
    from app.auth.models import User
    from app.extensions import db

    with app.app_context():
        user = User(email=email, display_name=display_name)
        user.set_password("correct-horse")
        db.session.add(user)
        db.session.commit()
        return user.id


def make_device(app, user_id, name="My laptop"):
    """Create a sync device the way the Devices page does; returns the raw key."""
    from app.extensions import db
    from app.school.services.devices import create_device

    with app.app_context():
        _, raw_key = create_device(user_id, name)
        db.session.commit()
        return raw_key


def api(client, method, path, key=None, json=None, **kwargs):
    headers = {} if key is None else {"Authorization": f"Bearer {key}"}
    return client.open(f"/api/school/sync{path}", method=method, headers=headers, json=json, **kwargs)


# Uploads from contract/samples/, which the Java website's tests read too.
SAMPLES = Path(__file__).resolve().parents[1] / "contract" / "samples"
BB = "https://blackboard.hcmiu.edu.vn"


def _sample(name):
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


# A complete, valid upload from the agent. Times are Vietnam time (+07:00).
def full_payload():
    return _sample("finish-edusoft.json")


# A valid Blackboard section as the agent uploads it.
def blackboard_payload():
    return _sample("finish-blackboard.json")["blackboard"]["data"]
```

Replace `tests/test_contract.py` with (the two tests at the end are new):

```python
import copy
import json

import pytest
from pydantic import ValidationError

from sla_contract.schema import FinishRun
from tests.helpers import BB, SAMPLES, blackboard_payload, full_payload


def test_a_complete_upload_is_accepted():
    finish = FinishRun.model_validate(full_payload())

    meeting = finish.timetable.data.courses[0].meetings[0]
    assert meeting.start_at.utcoffset().total_seconds() == 7 * 3600
    assert finish.tuition.data.balance == 12500000


def test_times_without_a_timezone_are_rejected():
    payload = full_payload()
    payload["exams"]["data"]["exams"][0]["start_at"] = "2026-12-12T08:00:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_class_that_ends_before_it_starts_is_rejected():
    payload = full_payload()
    meeting = payload["timetable"]["data"]["courses"][0]["meetings"][0]
    meeting["end_at"] = "2026-09-29T07:00:00+07:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "path",
    [
        ("date_of_birth",),
        ("timetable", "data", "student_id"),
        ("timetable", "data", "courses", 0, "student_name"),
        ("tuition", "data", "bank_account"),
    ],
)
def test_unknown_fields_are_rejected_so_no_extra_personal_data_gets_in(path):
    payload = full_payload()
    target = payload
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = "something personal"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "section",
    [
        {"status": "ok"},
        {"status": "failed", "error_message": "Tuition table not found"},
        {"status": "failed", "error_code": "made_up_code", "error_message": "x"},
        {"status": "done", "data": {}},
    ],
    ids=["ok-without-data", "failed-without-code", "unknown-error-code", "unknown-status"],
)
def test_each_part_is_either_ok_with_data_or_failed_with_a_reason(section):
    payload = full_payload()
    payload["tuition"] = section

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_whole_run_failure_carries_no_data():
    payload = full_payload()
    payload["error_code"] = "bad_credentials"
    payload["error_message"] = "EduSoft rejected the password"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_an_upload_with_neither_data_nor_an_error_is_rejected():
    with pytest.raises(ValidationError):
        FinishRun.model_validate({"schema_version": 1})


def _failed(code="edusoft_changed"):
    return {"status": "failed", "error_code": code, "error_message": "Table not found"}


@pytest.mark.parametrize(
    "changes, expected",
    [
        ({}, "success"),
        ({"tuition": _failed()}, "partial"),
        ({"timetable": _failed(), "exams": _failed(), "tuition": _failed()}, "failed"),
        ({"exams": None, "tuition": None}, "success"),
        (
            {"timetable": None, "exams": None, "tuition": None,
             "error_code": "bad_credentials", "error_message": "EduSoft rejected the password"},
            "failed",
        ),
    ],
    ids=["all-ok", "one-failed", "all-failed", "only-timetable-sent", "whole-run-error"],
)
def test_overall_status(changes, expected):
    payload = copy.deepcopy(full_payload())
    for key, value in changes.items():
        if value is None:
            payload.pop(key, None)
        else:
            payload[key] = value

    assert FinishRun.model_validate(payload).overall_status() == expected


def test_a_blackboard_section_is_accepted_next_to_edusoft():
    payload = full_payload()
    payload["blackboard"] = {"status": "ok", "data": blackboard_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "blackboard"]
    assert finish.blackboard.data.courses[0].assignments[0].score == 8.5


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["courses"][0]["announcements"][0].update(url="https://evil.example/x"),
        lambda p: p["courses"][0]["announcements"][0].update(posted_at="2026-09-28T02:00:00"),
        lambda p: p["courses"][0]["assignments"][0].update(status="done"),
        lambda p: p["courses"][0]["materials"][0].update(kind="video"),
        lambda p: p["courses"][0].update(student_email="s@example.com"),
        lambda p: p["courses"][0]["announcements"][0].update(text="x" * 5001),
    ],
    ids=["link-to-another-site", "naive-time", "unknown-status", "unknown-kind", "extra-field", "text-too-long"],
)
def test_bad_blackboard_data_is_rejected(change):
    data = blackboard_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"blackboard": {"status": "ok", "data": data}})


def test_a_failed_blackboard_part_can_say_its_format_changed():
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "blackboard": {"status": "failed", "error_code": "source_changed", "error_message": "Unexpected format"},
    })

    assert finish.overall_status() == "partial"


# ---- contract/samples/: the Java website's tests check the same files ----------


@pytest.mark.parametrize("path", sorted(SAMPLES.glob("*.json")), ids=lambda path: path.name)
def test_every_shared_sample_is_accepted(path):
    FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))


@pytest.mark.parametrize("path", sorted((SAMPLES / "invalid").glob("*.json")), ids=lambda path: path.name)
def test_every_shared_invalid_sample_is_refused(path):
    with pytest.raises(ValidationError):
        FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `.venv/Scripts/python.exe -m pytest tests/test_contract.py`
Expected: `25 failed, 1 passed, 2 skipped`, the failures with `FileNotFoundError` for `contract/samples/finish-edusoft.json` (the two new tests are skipped: `got empty parameter set`).

- [ ] **Step 3: Add the samples**

`contract/samples/finish-edusoft.json`:

```json
{
  "schema_version": 1,
  "timetable": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "term_name": "Semester 1, 2026-2027",
      "courses": [
        {
          "course_code": "IT093IU",
          "course_name": "Web Application Development",
          "group": "01",
          "credits": 4,
          "lecturer": "Nguyen Van A",
          "meetings": [
            {
              "start_at": "2026-09-29T08:00:00+07:00",
              "end_at": "2026-09-29T10:30:00+07:00",
              "room": "A2.307"
            }
          ]
        }
      ]
    }
  },
  "exams": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "exams": [
        {
          "course_code": "IT093IU",
          "course_name": "Web Application Development",
          "exam_type": "final",
          "start_at": "2026-12-12T08:00:00+07:00",
          "duration_min": 90,
          "room": "A1.101"
        }
      ]
    }
  },
  "tuition": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "amount_due": 12500000,
      "amount_paid": 0,
      "balance": 12500000,
      "due_date": "2026-10-15",
      "status_text": "Chưa đóng"
    }
  }
}
```

`contract/samples/finish-blackboard.json`:

```json
{
  "schema_version": 1,
  "blackboard": {
    "status": "ok",
    "data": {
      "courses": [
        {
          "bb_id": "_101_1",
          "course_code": "IT093IU",
          "name": "Web Application Development",
          "url": "https://blackboard.hcmiu.edu.vn/webapps/blackboard/execute/launcher?type=Course&id=_101_1&url=",
          "announcements": [
            {
              "bb_id": "_501_1",
              "title": "No class on Thursday",
              "text": "Class is cancelled.",
              "posted_at": "2026-09-28T02:00:00+00:00",
              "url": "https://blackboard.hcmiu.edu.vn/x"
            }
          ],
          "assignments": [
            {
              "bb_id": "_701_1",
              "name": "Lab 3",
              "due_at": "2026-10-02T16:59:00+00:00",
              "points_possible": 10,
              "score": 8.5,
              "grade_text": "8.5",
              "status": "graded",
              "feedback": "Good work",
              "url": "https://blackboard.hcmiu.edu.vn/x"
            }
          ],
          "materials": [
            {
              "bb_id": "_902_1",
              "title": "Week 5 slides.pdf",
              "kind": "file",
              "path": "Week 5",
              "created_at": "2026-09-28T01:00:00+00:00",
              "url": "https://blackboard.hcmiu.edu.vn/x"
            }
          ]
        }
      ]
    }
  }
}
```

`contract/samples/finish-parts-failed.json`:

```json
{
  "schema_version": 1,
  "timetable": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "term_name": "Semester 1, 2026-2027",
      "courses": [
        {
          "course_code": "IT093IU",
          "course_name": "Web Application Development",
          "group": "01",
          "credits": 4,
          "lecturer": "Nguyen Van A",
          "meetings": [
            {
              "start_at": "2026-09-29T08:00:00+07:00",
              "end_at": "2026-09-29T10:30:00+07:00",
              "room": "A2.307"
            }
          ]
        }
      ]
    }
  },
  "exams": {
    "status": "failed",
    "error_code": "edusoft_changed",
    "error_message": "Exam table not found"
  },
  "tuition": {
    "status": "failed",
    "error_code": "network",
    "error_message": "EduSoft timed out"
  },
  "blackboard": {
    "status": "failed",
    "error_code": "source_changed",
    "error_message": "Unexpected format"
  }
}
```

`contract/samples/finish-whole-run-error.json`:

```json
{
  "schema_version": 1,
  "error_code": "bad_credentials",
  "error_message": "EduSoft rejected the password"
}
```

`contract/samples/invalid/class-ends-before-it-starts.json`:

```json
{
  "schema_version": 1,
  "timetable": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "courses": [
        {
          "course_code": "IT093IU",
          "course_name": "Web Application Development",
          "meetings": [
            {
              "start_at": "2026-09-29T08:00:00+07:00",
              "end_at": "2026-09-29T07:00:00+07:00"
            }
          ]
        }
      ]
    }
  }
}
```

`contract/samples/invalid/link-to-another-site.json`:

```json
{
  "schema_version": 1,
  "blackboard": {
    "status": "ok",
    "data": {
      "courses": [
        {
          "bb_id": "_101_1",
          "name": "Web Application Development",
          "url": "https://evil.example/course"
        }
      ]
    }
  }
}
```

`contract/samples/invalid/ok-part-without-data.json`:

```json
{
  "schema_version": 1,
  "tuition": {
    "status": "ok"
  }
}
```

`contract/samples/invalid/time-without-timezone.json`:

```json
{
  "schema_version": 1,
  "exams": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "exams": [
        {
          "course_code": "IT093IU",
          "course_name": "Web Application Development",
          "exam_type": "final",
          "start_at": "2026-12-12T08:00:00"
        }
      ]
    }
  }
}
```

`contract/samples/invalid/unknown-field.json`:

```json
{
  "schema_version": 1,
  "tuition": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "amount_due": 12500000,
      "amount_paid": 0,
      "balance": 12500000,
      "bank_account": "0123456789"
    }
  }
}
```

`contract/samples/invalid/whole-run-error-with-data.json`:

```json
{
  "schema_version": 1,
  "error_code": "bad_credentials",
  "error_message": "EduSoft rejected the password",
  "tuition": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "amount_due": 12500000,
      "amount_paid": 0,
      "balance": 12500000
    }
  }
}
```

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: `425 passed` (415 before, plus 4 accepted and 6 refused samples).

- [ ] **Step 5: Point the Python format at its Java twin**

In `contract/sla_contract/schema.py`, replace the module docstring's last sentence.

Old:

```python
Both sides import this file, so they always agree on the format. Unknown
fields are rejected: only what is listed here can leave the laptop.
"""
```

New:

```python
Both sides import this file, so they always agree on the format. Unknown
fields are rejected: only what is listed here can leave the laptop.

The Java website reads the same format with
web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java: change both
together. contract/samples/ holds example uploads that both test suites check.
"""
```

Run: `.venv/Scripts/python.exe -m pytest tests/test_contract.py`
Expected: `36 passed`.

- [ ] **Step 6: Commit**

```bash
git add contract/samples tests/helpers.py tests/test_contract.py contract/sla_contract/schema.py
git commit -m "test: shared sample uploads in contract/samples, checked by the Python tests"
```

---

### Task 3: The upload format in Java

`SyncContract` is the Java twin of `schema.py`: one record per model, with the same limits as Bean Validation annotations. `SyncJson` reads a body with its own strict Jackson 3 mapper (snake_case names, unknown fields refused, whole numbers must be whole, text must be a JSON string and is trimmed like pydantic's `str_strip_whitespace`, offsets kept as sent), then validates the record. Its errors carry `loc` and a fixed message, never the value. The test helper `Payloads` reads the shared samples and writes JSON the way the agent's Python does (`\uXXXX` for letters with diacritics).

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/core/Text.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncJson.java`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/auth/AppUserDetailsService.java` (replaced), `web/src/main/java/vn/edu/hcmiu/sla/auth/RegisterForm.java` (replaced)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`, `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`

**Interfaces:**
- Consumes: `contract/samples/` (Task 2), read from `web/` as `../contract/samples` (Maven runs tests in `web/`).
- Produces:
  - `vn.edu.hcmiu.sla.core.Text.strip(String)`: like Python's `str.strip()`, non-breaking spaces included.
  - `SyncContract.StartRun(String trigger)`; `SyncContract.FinishRun(Integer schemaVersion, String errorCode, String errorMessage, Section<Timetable> timetable, Section<Exams> exams, Section<Tuition> tuition, Section<Blackboard> blackboard)` with `Map<String, Section<?>> sections()` (sent parts in the order timetable, exams, tuition, blackboard) and `String overallStatus()` (`success` / `partial` / `failed`); `Section<T extends Record>(String status, T data, String errorCode, String errorMessage)` with `boolean ok()`; records `Timetable(termCode, termName, courses)`, `Course(courseCode, courseName, group, Double credits, lecturer, meetings)`, `ClassMeeting(OffsetDateTime startAt, OffsetDateTime endAt, room)`, `Exams(termCode, exams)`, `Exam(courseCode, courseName, examType, OffsetDateTime startAt, Integer durationMin, room, notes)`, `Tuition(termCode, Long amountDue, Long amountPaid, Long balance, LocalDate dueDate, statusText, items)`, `TuitionItem(description, Long amount)`, `Blackboard(courses)`, `BbCourse(bbId, courseCode, name, url, announcements, assignments, materials)`, `BbAnnouncement(bbId, title, text, OffsetDateTime postedAt, url)`, `BbAssignment(bbId, name, OffsetDateTime dueAt, Double pointsPossible, Double score, gradeText, status, feedback, url)`, `BbMaterial(bbId, title, kind, path, OffsetDateTime createdAt, url)`.
  - `SyncJson` (a Spring bean): `MAX_UPLOAD_BYTES = 5_000_000`; `byte[] body(HttpServletRequest)` throws `SyncJson.TooLarge`; `<T> T read(byte[] body, Class<T> type)` throws `SyncJson.Invalid` whose `getDetails()` is a `List<Map<String, Object>>` of `{"loc": [...], "msg": "..."}`.
  - Test helper `Payloads` (package-private, test sources): `SAMPLES`, `BB`, `fullPayload()`, `blackboardPayload()`, `ok(data)`, `failed(errorCode, message)`, `at(root, path…)` (the map at a path of keys and list positions), `list(root, path…)`, `bytes(payload)`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uploads for tests, from contract/samples/ (the Python tests use the same files), as plain maps and
 * lists that a test can change before sending.
 */
final class Payloads {

    static final Path SAMPLES = Path.of("..", "contract", "samples");
    static final String BB = "https://blackboard.hcmiu.edu.vn";

    /** Writes JSON like the agent's Python does: letters with diacritics as \\uXXXX. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private Payloads() {
    }

    /** A complete, valid upload from the agent. Times are Vietnam time (+07:00). */
    static Map<String, Object> fullPayload() {
        return read("finish-edusoft.json");
    }

    /** A valid Blackboard section's data, as the agent uploads it. */
    static Map<String, Object> blackboardPayload() {
        return at(read("finish-blackboard.json"), "blackboard", "data");
    }

    static Map<String, Object> ok(Object data) {
        return Map.of("status", "ok", "data", data);
    }

    static Map<String, Object> failed(String errorCode, String message) {
        return Map.of("status", "failed", "error_code", errorCode, "error_message", message);
    }

    /** The map inside {@code root} at a path of keys and list positions, e.g. at(p, "timetable", "data"). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> at(Object root, Object... path) {
        Object here = root;
        for (Object step : path) {
            here = step instanceof Integer index ? ((List<Object>) here).get(index) : ((Map<String, Object>) here).get(step);
        }
        return (Map<String, Object>) here;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object root, Object... path) {
        Map<String, Object> parent = at(root, java.util.Arrays.copyOf(path, path.length - 1));
        return (List<Object>) parent.get(path[path.length - 1]);
    }

    static byte[] bytes(Object payload) {
        return JSON.writeValueAsBytes(payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String name) {
        try {
            return JSON.readValue(Files.readAllBytes(SAMPLES.resolve(name)), Map.class);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.edu.hcmiu.sla.school.sync.Payloads.BB;
import static vn.edu.hcmiu.sla.school.sync.Payloads.at;
import static vn.edu.hcmiu.sla.school.sync.Payloads.blackboardPayload;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;

/** Java twin of tests/test_contract.py: the website accepts and refuses exactly what the agent's format allows. */
class SyncContractTest {

    final SyncJson json = new SyncJson(Validation.buildDefaultValidatorFactory().getValidator());

    FinishRun read(Map<String, Object> payload) {
        return json.read(bytes(payload), FinishRun.class);
    }

    void assertRefused(Map<String, Object> payload) {
        assertThatThrownBy(() -> read(payload)).isInstanceOf(SyncJson.Invalid.class);
    }

    @Test
    void aCompleteUploadIsAccepted() {
        FinishRun finish = read(fullPayload());

        var meeting = finish.timetable().data().courses().get(0).meetings().get(0);
        assertThat(meeting.startAt().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThat(finish.tuition().data().balance()).isEqualTo(12_500_000L);
        assertThat(finish.tuition().data().statusText()).isEqualTo("Chưa đóng");
    }

    @Test
    void timesWithoutATimezoneAreRejected() {
        Map<String, Object> payload = fullPayload();
        at(payload, "exams", "data", "exams", 0).put("start_at", "2026-12-12T08:00:00");

        assertRefused(payload);
    }

    @Test
    void aClassThatEndsBeforeItStartsIsRejected() {
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data", "courses", 0, "meetings", 0).put("end_at", "2026-09-29T07:00:00+07:00");

        assertRefused(payload);
    }

    static Stream<List<Object>> personalFields() {
        return Stream.of(
                List.of("date_of_birth"),
                List.of("timetable", "data", "student_id"),
                List.of("timetable", "data", "courses", 0, "student_name"),
                List.of("tuition", "data", "bank_account"));
    }

    @ParameterizedTest
    @MethodSource("personalFields")
    void unknownFieldsAreRejectedSoNoExtraPersonalDataGetsIn(List<Object> path) {
        Map<String, Object> payload = fullPayload();
        at(payload, path.subList(0, path.size() - 1).toArray()).put((String) path.get(path.size() - 1), "something personal");

        assertThatThrownBy(() -> read(payload))
                .isInstanceOfSatisfying(SyncJson.Invalid.class, error -> {
                    assertThat(error.getDetails()).containsExactly(Map.of("loc", path, "msg", "Extra inputs are not permitted"));
                    assertThat(error.getDetails().toString()).doesNotContain("something personal");
                });
    }

    static Stream<Arguments> badParts() {
        return Stream.of(
                Arguments.of("ok-without-data", Map.of("status", "ok")),
                Arguments.of("failed-without-code", Map.of("status", "failed", "error_message", "Tuition table not found")),
                Arguments.of("unknown-error-code", failed("made_up_code", "x")),
                Arguments.of("unknown-status", Map.of("status", "done", "data", Map.of())),
                Arguments.of("ok-with-an-error", Map.of("status", "ok", "data", at(fullPayload(), "tuition", "data"),
                        "error_code", "unknown")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badParts")
    void eachPartIsEitherOkWithDataOrFailedWithAReason(String name, Map<String, Object> part) {
        Map<String, Object> payload = fullPayload();
        payload.put("tuition", part);

        assertRefused(payload);
    }

    @Test
    void aWholeRunFailureCarriesNoData() {
        Map<String, Object> payload = fullPayload();
        payload.put("error_code", "bad_credentials");
        payload.put("error_message", "EduSoft rejected the password");

        assertRefused(payload);
    }

    @Test
    void anUploadWithNeitherDataNorAnErrorIsRejected() {
        assertRefused(new HashMap<>(Map.of("schema_version", 1)));
    }

    @Test
    void anotherSchemaVersionIsRejected() {
        Map<String, Object> payload = fullPayload();
        payload.put("schema_version", 2);

        assertRefused(payload);
    }

    static Map<String, Object> failedPart() {
        return failed("edusoft_changed", "Table not found");
    }

    static Stream<Arguments> overallStatuses() {
        Map<String, Object> wholeRunError = new HashMap<>();
        wholeRunError.put("timetable", null);
        wholeRunError.put("exams", null);
        wholeRunError.put("tuition", null);
        wholeRunError.put("error_code", "bad_credentials");
        wholeRunError.put("error_message", "EduSoft rejected the password");
        Map<String, Object> onlyTimetable = new HashMap<>();
        onlyTimetable.put("exams", null);
        onlyTimetable.put("tuition", null);
        return Stream.of(
                Arguments.of("all-ok", Map.of(), "success"),
                Arguments.of("one-failed", Map.of("tuition", failedPart()), "partial"),
                Arguments.of("all-failed", Map.of("timetable", failedPart(), "exams", failedPart(), "tuition", failedPart()),
                        "failed"),
                Arguments.of("only-timetable-sent", onlyTimetable, "success"),
                Arguments.of("whole-run-error", wholeRunError, "failed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overallStatuses")
    void overallStatus(String name, Map<String, Object> changes, String expected) {
        Map<String, Object> payload = fullPayload();
        changes.forEach((key, value) -> {
            if (value == null) {
                payload.remove(key);
            } else {
                payload.put(key, value);
            }
        });

        assertThat(read(payload).overallStatus()).isEqualTo(expected);
    }

    @Test
    void aBlackboardSectionIsAcceptedNextToEduSoft() {
        Map<String, Object> payload = fullPayload();
        payload.put("blackboard", ok(blackboardPayload()));

        FinishRun finish = read(payload);

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "tuition", "blackboard");
        assertThat(finish.blackboard().data().courses().get(0).assignments().get(0).score()).isEqualTo(8.5);
    }

    static Stream<Arguments> badBlackboardData() {
        return Stream.<Arguments>of(
                Arguments.of("link-to-another-site",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "announcements", 0).put("url", "https://evil.example/x")),
                Arguments.of("link-that-only-starts-on-another-line",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "announcements", 0).put("url", "x\n" + BB + "/x")),
                Arguments.of("naive-time",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "announcements", 0).put("posted_at", "2026-09-28T02:00:00")),
                Arguments.of("unknown-status",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "assignments", 0).put("status", "done")),
                Arguments.of("unknown-kind",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "materials", 0).put("kind", "video")),
                Arguments.of("extra-field",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0).put("student_email", "s@example.com")),
                Arguments.of("text-too-long",
                        (Consumer<Map<String, Object>>) p -> at(p, "courses", 0, "announcements", 0).put("text", "x".repeat(5001))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badBlackboardData")
    void badBlackboardDataIsRejected(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> data = blackboardPayload();
        change.accept(data);

        assertRefused(new HashMap<>(Map.of("blackboard", ok(data))));
    }

    @Test
    void aFailedBlackboardPartCanSayItsFormatChanged() {
        FinishRun finish = read(Map.of(
                "timetable", fullPayload().get("timetable"),
                "blackboard", failed("source_changed", "Unexpected format")));

        assertThat(finish.overallStatus()).isEqualTo("partial");
    }

    // ---- contract/samples/: the Python tests check the same files --------------

    static Stream<Path> samples(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    static Stream<Path> validSamples() throws IOException {
        return samples(Payloads.SAMPLES);
    }

    static Stream<Path> invalidSamples() throws IOException {
        return samples(Payloads.SAMPLES.resolve("invalid"));
    }

    @ParameterizedTest
    @MethodSource("validSamples")
    void everySharedSampleIsAccepted(Path file) throws IOException {
        assertThat(json.read(Files.readAllBytes(file), FinishRun.class).overallStatus()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("invalidSamples")
    void everySharedInvalidSampleIsRefused(Path file) throws IOException {
        byte[] body = Files.readAllBytes(file);

        assertThatThrownBy(() -> json.read(body, FinishRun.class)).isInstanceOf(SyncJson.Invalid.class);
    }

    // ---- Java-side details of reading JSON the way pydantic does ------------------

    @Test
    void textIsTrimmedAndBlankTextCountsAsMissing() {
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data", "courses", 0).put("course_code", " IT093IU ");

        assertThat(read(payload).timetable().data().courses().get(0).courseCode()).isEqualTo("IT093IU");

        at(payload, "timetable", "data", "courses", 0).put("course_name", "   ");
        assertRefused(payload);
    }

    @Test
    void timesAsPydanticWritesThemAreAccepted() {
        Map<String, Object> data = blackboardPayload();
        at(data, "courses", 0, "announcements", 0).put("posted_at", "2026-09-28T02:00:00Z");
        at(data, "courses", 0, "materials", 0).put("created_at", "2026-09-28T01:00:00.250000Z");

        var course = read(new HashMap<>(Map.of("blackboard", ok(data)))).blackboard().data().courses().get(0);

        assertThat(course.announcements().get(0).postedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(course.materials().get(0).createdAt().getNano()).isEqualTo(250_000_000);
    }

    @Test
    void numbersAreNotTextAndFractionsAreNotWholeNumbers() {
        Map<String, Object> numberAsText = fullPayload();
        at(numberAsText, "timetable", "data", "courses", 0).put("course_code", 93);
        Map<String, Object> fraction = fullPayload();
        at(fraction, "tuition", "data").put("amount_due", 12.5);

        assertRefused(numberAsText);
        assertRefused(fraction);
    }

    @Test
    void anErrorSaysWhereInSnakeCase() {
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data", "courses", 0).put("course_code", "X".repeat(21));

        assertThatThrownBy(() -> read(payload))
                .isInstanceOfSatisfying(SyncJson.Invalid.class, error -> assertThat(error.getDetails())
                        .extracting(detail -> detail.get("loc"))
                        .containsExactly(List.of("timetable", "data", "courses", 0, "course_code")));
    }

    @Test
    void brokenJsonIsRefused() {
        assertThatThrownBy(() -> json.read("{\"trigger\": ".getBytes(), SyncContract.StartRun.class))
                .isInstanceOf(SyncJson.Invalid.class);
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncContractTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `SyncJson` and `SyncContract`.

- [ ] **Step 3: Write the text helper, the format and the reader**

`web/src/main/java/vn/edu/hcmiu/sla/core/Text.java`:

```java
package vn.edu.hcmiu.sla.core;

/** Small text helpers shared by every module. */
public final class Text {

    private Text() {
    }

    /** Like Python's str.strip(): also removes non-breaking spaces pasted from Word, Outlook or a web page. */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The data the laptop agent sends to the website after a sync: the Java twin of
 * contract/sla_contract/schema.py, which the agent uses. Change both together.
 *
 * <p>{@link SyncJson} reads it: field names are snake_case in JSON (course_code), unknown fields are
 * refused so nothing extra can leave the laptop, text is trimmed, and times must carry their offset.
 */
public final class SyncContract {

    private SyncContract() {
    }

    static final String ERROR_CODES =
            "bad_credentials|session_expired|network|edusoft_changed|source_changed|extra_verification|unknown";
    static final String BLACKBOARD_URL = "(?s)https://blackboard\\.hcmiu\\.edu\\.vn/.*";

    public record StartRun(@NotNull @Pattern(regexp = "scheduled|manual|import") String trigger) {
    }

    // ---- EduSoft ----------------------------------------------------------------

    public record ClassMeeting(@NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
            @Size(max = 50) String room) {

        @AssertTrue(message = "end_at must be after start_at")
        boolean isEndAfterStart() {
            return startAt == null || endAt == null || endAt.isAfter(startAt);
        }
    }

    public record Course(
            @NotNull @Size(min = 1, max = 20) String courseCode,
            @NotNull @Size(min = 1, max = 255) String courseName,
            @Size(max = 20) String group,
            @PositiveOrZero @DecimalMax("50") Double credits,
            @Size(max = 255) String lecturer,
            @Size(max = 200) List<@Valid ClassMeeting> meetings) {

        public Course {
            meetings = meetings == null ? List.of() : meetings;
        }
    }

    public record Timetable(
            @NotNull @Size(min = 1, max = 20) String termCode,
            @Size(max = 100) String termName,
            @NotNull @Size(max = 40) List<@Valid Course> courses) {
    }

    public record Exam(
            @NotNull @Size(min = 1, max = 20) String courseCode,
            @NotNull @Size(min = 1, max = 255) String courseName,
            @NotNull @Pattern(regexp = "midterm|final|other") String examType,
            @NotNull OffsetDateTime startAt,
            @Min(1) @Max(600) Integer durationMin,
            @Size(max = 50) String room,
            @Size(max = 500) String notes) {
    }

    public record Exams(
            @NotNull @Size(min = 1, max = 20) String termCode,
            @NotNull @Size(max = 60) List<@Valid Exam> exams) {
    }

    /** Amounts are VND. */
    public record TuitionItem(@NotNull @Size(min = 1, max = 255) String description, @NotNull Long amount) {
    }

    /** Amounts are VND; a negative balance means overpaid. */
    public record Tuition(
            @NotNull @Size(min = 1, max = 20) String termCode,
            @NotNull @PositiveOrZero Long amountDue,
            @NotNull @PositiveOrZero Long amountPaid,
            @NotNull Long balance,
            LocalDate dueDate,
            @Size(max = 255) String statusText,
            @Size(max = 60) List<@Valid TuitionItem> items) {

        public Tuition {
            items = items == null ? List.of() : items;
        }
    }

    // ---- Blackboard -------------------------------------------------------------

    public record BbAnnouncement(
            @NotNull @Size(min = 1, max = 64) String bbId,
            @NotNull @Size(min = 1, max = 255) String title,
            @Size(max = 5000) String text,
            OffsetDateTime postedAt,
            @NotNull @Size(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {

        public BbAnnouncement {
            text = text == null ? "" : text;
        }
    }

    public record BbAssignment(
            @NotNull @Size(min = 1, max = 64) String bbId,
            @NotNull @Size(min = 1, max = 255) String name,
            OffsetDateTime dueAt,
            @PositiveOrZero Double pointsPossible,
            Double score,
            @Size(max = 50) String gradeText,
            @NotNull @Pattern(regexp = "not_graded|needs_grading|graded|exempt") String status,
            @Size(max = 1000) String feedback,
            @NotNull @Size(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {
    }

    public record BbMaterial(
            @NotNull @Size(min = 1, max = 64) String bbId,
            @NotNull @Size(min = 1, max = 255) String title,
            @NotNull @Pattern(regexp = "file|folder|link|document|other") String kind,
            @Size(max = 500) String path,
            OffsetDateTime createdAt,
            @NotNull @Size(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {

        public BbMaterial {
            path = path == null ? "" : path;
        }
    }

    public record BbCourse(
            @NotNull @Size(min = 1, max = 64) String bbId,
            @Size(min = 1, max = 20) String courseCode,
            @NotNull @Size(min = 1, max = 255) String name,
            @NotNull @Size(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url,
            @Size(max = 300) List<@Valid BbAnnouncement> announcements,
            @Size(max = 300) List<@Valid BbAssignment> assignments,
            @Size(max = 1000) List<@Valid BbMaterial> materials) {

        public BbCourse {
            announcements = announcements == null ? List.of() : announcements;
            assignments = assignments == null ? List.of() : assignments;
            materials = materials == null ? List.of() : materials;
        }
    }

    public record Blackboard(@NotNull @Size(max = 40) List<@Valid BbCourse> courses) {
    }

    // ---- A whole sync -----------------------------------------------------------

    /** One part of a sync: {"status": "ok", "data": …} or {"status": "failed", "error_code": …, "error_message": …}. */
    public record Section<T extends Record>(
            @NotNull @Pattern(regexp = "ok|failed") String status,
            @Valid T data,
            @Pattern(regexp = ERROR_CODES) String errorCode,
            @Size(min = 1, max = 500) String errorMessage) {

        @AssertTrue(message = "an ok part carries only data; a failed part carries only error_code and error_message")
        boolean isComplete() {
            if ("ok".equals(status)) {
                return data != null && errorCode == null && errorMessage == null;
            }
            return data == null && errorCode != null && errorMessage != null;
        }

        public boolean ok() {
            return "ok".equals(status);
        }
    }

    /** The result of one sync: either a whole-run error, or a result per part. */
    public record FinishRun(
            Integer schemaVersion,
            @Pattern(regexp = ERROR_CODES) String errorCode,
            @Size(min = 1, max = 500) String errorMessage,
            @Valid Section<Timetable> timetable,
            @Valid Section<Exams> exams,
            @Valid Section<Tuition> tuition,
            @Valid Section<Blackboard> blackboard) {

        @AssertTrue(message = "schema_version must be 1")
        boolean isVersion1() {
            return schemaVersion == null || schemaVersion == 1;
        }

        @AssertTrue(message = "error_message is required with error_code")
        boolean isErrorExplained() {
            return errorCode == null || errorMessage != null;
        }

        @AssertTrue(message = "a whole-run error cannot carry section results")
        boolean isErrorWithoutSections() {
            return errorCode == null || sections().isEmpty();
        }

        @AssertTrue(message = "send either error_code or at least one section")
        boolean isErrorOrSections() {
            return errorCode != null || !sections().isEmpty();
        }

        /** The parts that were sent, by name, in the order timetable, exams, tuition, blackboard. */
        public Map<String, Section<?>> sections() {
            Map<String, Section<?>> sent = new LinkedHashMap<>();
            if (timetable != null) {
                sent.put("timetable", timetable);
            }
            if (exams != null) {
                sent.put("exams", exams);
            }
            if (tuition != null) {
                sent.put("tuition", tuition);
            }
            if (blackboard != null) {
                sent.put("blackboard", blackboard);
            }
            return sent;
        }

        /** success, partial or failed. */
        public String overallStatus() {
            if (errorCode != null) {
                return "failed";
            }
            boolean anyOk = sections().values().stream().anyMatch(Section::ok);
            boolean anyFailed = sections().values().stream().anyMatch(section -> !section.ok());
            if (anyOk && !anyFailed) {
                return "success";
            }
            return anyOk ? "partial" : "failed";
        }
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncJson.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import vn.edu.hcmiu.sla.core.Text;

/**
 * Reads the agent's JSON into {@link SyncContract} records as strictly as pydantic does on the Python
 * side, and checks every rule. Errors say where and why, but never repeat what was sent.
 */
@Component
public class SyncJson {

    /** A full semester of Blackboard text, JSON-escaped, stays well under this. */
    public static final int MAX_UPLOAD_BYTES = 5_000_000;

    /** The body is bigger than {@link #MAX_UPLOAD_BYTES}. */
    public static class TooLarge extends RuntimeException {
    }

    /** The body isn't valid. Each detail is {"loc": [where…], "msg": why}. */
    public static class Invalid extends RuntimeException {

        private final List<Map<String, Object>> details;

        Invalid(List<Map<String, Object>> details) {
            super("invalid_payload");
            this.details = details;
        }

        public List<Map<String, Object>> getDetails() {
            return details;
        }
    }

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) // keep +07:00 as sent
            .addModule(new SimpleModule().addDeserializer(String.class, new StrippedString()))
            .build();

    private final Validator validator;

    public SyncJson(Validator validator) {
        this.validator = validator;
    }

    /** The request's body, or {@link TooLarge}. */
    public byte[] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            throw new TooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (body.length > MAX_UPLOAD_BYTES) {
            throw new TooLarge();
        }
        return body;
    }

    /** The body as a checked record, or {@link Invalid}. */
    public <T> T read(byte[] body, Class<T> type) {
        T value;
        try {
            value = mapper.readValue(body, type);
        } catch (StreamReadException error) {
            throw new Invalid(List.of(detail(List.of(), "Invalid JSON")));
        } catch (JacksonException error) {
            String message = error instanceof UnrecognizedPropertyException
                    ? "Extra inputs are not permitted"
                    : "Input has the wrong type or format";
            throw new Invalid(List.of(detail(location(error), message)));
        }
        if (value == null) {
            throw new Invalid(List.of(detail(List.of(), "Input should be an object")));
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (ConstraintViolation<T> violation : validator.validate(value)) {
            details.add(detail(location(violation.getPropertyPath()), violation.getMessage()));
        }
        if (!details.isEmpty()) {
            throw new Invalid(details);
        }
        return value;
    }

    private static Map<String, Object> detail(List<Object> loc, String msg) {
        return Map.of("loc", loc, "msg", msg);
    }

    private static List<Object> location(JacksonException error) {
        List<Object> loc = new ArrayList<>();
        for (JacksonException.Reference step : error.getPath()) {
            loc.add(step.getPropertyName() != null ? step.getPropertyName() : step.getIndex());
        }
        return loc;
    }

    private static List<Object> location(Path path) {
        List<Object> loc = new ArrayList<>();
        for (Path.Node node : path) {
            if (node.getIndex() != null) {
                loc.add(node.getIndex());
            }
            if (node.getName() != null) {
                loc.add(node.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT));
            }
        }
        return loc;
    }

    /** Text must be a JSON string, and is trimmed like pydantic's str_strip_whitespace. */
    static class StrippedString extends StdScalarDeserializer<String> {

        StrippedString() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return Text.strip(parser.getString());
        }
    }
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncContractTest)`
Expected: exit code 0; `web/target/surefire-reports/vn.edu.hcmiu.sla.school.sync.SyncContractTest.txt` says `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`. No `HV000271` / `HV000272` warnings in the output (`@Valid` sits on the list's element type, as Hibernate Validator 9 wants).

- [ ] **Step 5: Let login and register use the shared helper**

Replace `web/src/main/java/vn/edu/hcmiu/sla/auth/AppUserDetailsService.java` with:

```java
package vn.edu.hcmiu.sla.auth;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import vn.edu.hcmiu.sla.core.Text;

/** Finds the account for the email typed on the login page (trimmed and lower-cased, as at register). */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(normalizeEmail(email))
                .map(AppUser::of)
                .orElseThrow(() -> new UsernameNotFoundException("No account for that email"));
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : Text.strip(email).toLowerCase(Locale.ROOT);
    }
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/auth/RegisterForm.java` with:

```java
package vn.edu.hcmiu.sla.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import vn.edu.hcmiu.sla.core.Text;

/** The register page's fields, with the same rules and messages as the Python site. */
public class RegisterForm {

    static final String REQUIRED = "This field is required.";

    @NotBlank(message = REQUIRED)
    @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email address.")
    @Size(max = 255, message = "Field cannot be longer than 255 characters.")
    private String email = "";

    @NotBlank(message = REQUIRED)
    @Size(max = 100, message = "Field cannot be longer than 100 characters.")
    private String displayName = "";

    @Size(min = 8, max = 128, message = "Field must be between 8 and 128 characters long.")
    private String password = "";

    @NotBlank(message = REQUIRED)
    private String confirm = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = AppUserDetailsService.normalizeEmail(email);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : Text.strip(displayName);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getConfirm() {
        return confirm;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm == null ? "" : confirm;
    }
}
```

Run: `(cd web && ./mvnw -B -q test -Dtest='LoginTest,RegisterTest')`
Expected: exit code 0 (13 tests, including the two non-breaking-space tests).

- [ ] **Step 6: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/core/Text.java web/src/main/java/vn/edu/hcmiu/sla/auth web/src/main/java/vn/edu/hcmiu/sla/school/sync web/src/test/java/vn/edu/hcmiu/sla/school/sync
git commit -m "feat(web): the agent's upload format in Java, read as strictly as pydantic"
```

---

### Task 4: The "What changed" texts

A direct port of `app/school/services/changes.py`: the same rules and wording. `ChangesTest` is the Java twin of `tests/test_school_changes.py` with two more cases (a class that now ends later; scores written like Python's `f"{x:g}"`, whose expected texts were printed by the Python code).

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/ChangesTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (all static, in `Changes`): records `Change(String kind, String summary)`, `Meeting(courseCode, courseName, LocalDateTime startAt, LocalDateTime endAt, room)`, `ExamInfo(courseCode, courseName, examType, LocalDateTime startAt, room)`, `TuitionInfo(termCode, long balance, LocalDate dueDate, statusText)`, `BbItem` (factories `announcement(course, bbId, title)`, `assignment(course, bbId, title, dueAt, status, Double score, Double pointsPossible, gradeText)`, `material(course, bbId, title, materialKind)`), `BbState(List<String> courses, List<BbItem> items)`; methods `List<Change> timetable(List<Meeting> old, List<Meeting> fresh, LocalDateTime now)`, `exams(List<ExamInfo> old, List<ExamInfo> fresh, LocalDateTime now)`, `tuition(TuitionInfo old, TuitionInfo fresh)`, `blackboard(BbState old, BbState fresh)` (`old` is `null` on a first sync); package-private `when(LocalDateTime)`, `number(double)`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/ChangesTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.edu.hcmiu.sla.school.sync.Changes.BbItem;
import vn.edu.hcmiu.sla.school.sync.Changes.BbState;
import vn.edu.hcmiu.sla.school.sync.Changes.Change;
import vn.edu.hcmiu.sla.school.sync.Changes.ExamInfo;
import vn.edu.hcmiu.sla.school.sync.Changes.Meeting;
import vn.edu.hcmiu.sla.school.sync.Changes.TuitionInfo;

/** Java twin of tests/test_school_changes.py: the same feed lines, word for word. */
class ChangesTest {

    // Times are UTC, as stored. 01:00 UTC is 08:00 in Vietnam.
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 0, 0); // Mon 28/09 07:00 Vietnam time
    static final LocalDateTime TUE = LocalDateTime.of(2026, 9, 29, 1, 0); // Tue 29/09 08:00 VN
    static final LocalDateTime THU = LocalDateTime.of(2026, 10, 1, 1, 0); // Thu 01/10 08:00 VN
    static final LocalDateTime SAT = LocalDateTime.of(2026, 10, 3, 1, 0); // Sat 03/10 08:00 VN
    static final LocalDateTime LAST_WEEK = LocalDateTime.of(2026, 9, 22, 1, 0);

    static Meeting web(LocalDateTime start) {
        return web(start, "A2.307");
    }

    static Meeting web(LocalDateTime start, String room) {
        return new Meeting("IT093IU", "Web Application Development", start, start.plusHours(2), room);
    }

    static Change line(String kind, String summary) {
        return new Change(kind, summary);
    }

    // ---- Timetable ------------------------------------------------------------

    @Test
    void firstTimetableSyncGivesOneSummaryLine() {
        assertThat(Changes.timetable(null, List.of(web(LAST_WEEK), web(TUE), web(THU)), NOW))
                .containsExactly(line("added", "Timetable loaded: 1 course, 2 upcoming classes"));
    }

    @Test
    void noChangesMeansNoLines() {
        assertThat(Changes.timetable(List.of(web(TUE), web(THU)), List.of(web(TUE), web(THU)), NOW)).isEmpty();
    }

    @Test
    void aRoomChangeIsReportedInVietnamTime() {
        assertThat(Changes.timetable(List.of(web(TUE)), List.of(web(TUE, "LA1.605")), NOW)).containsExactly(
                line("changed", "IT093IU Web Application Development: room A2.307 → LA1.605 on Tue 29/09 08:00"));
    }

    @Test
    void theSameRoomChangeForSeveralClassesIsOneLine() {
        assertThat(Changes.timetable(List.of(web(TUE), web(THU)), List.of(web(TUE, "LA1.605"), web(THU, "LA1.605")), NOW))
                .containsExactly(line("changed", "IT093IU Web Application Development: room A2.307 → LA1.605 on "
                        + "Tue 29/09 08:00, Thu 01/10 08:00"));
    }

    @Test
    void cancelledAndExtraClasses() {
        assertThat(Changes.timetable(List.of(web(TUE), web(THU)), List.of(web(THU), web(SAT, "A1.101")), NOW))
                .containsExactly(
                        line("removed", "IT093IU Web Application Development: class cancelled on Tue 29/09 08:00"),
                        line("added", "IT093IU Web Application Development: new class on Sat 03/10 08:00 (A1.101)"));
    }

    @Test
    void aClassThatNowEndsLater() {
        Meeting longer = new Meeting("IT093IU", "Web Application Development", TUE, TUE.plusHours(3), "A2.307");

        assertThat(Changes.timetable(List.of(web(TUE)), List.of(longer), NOW)).containsExactly(
                line("changed", "IT093IU Web Application Development: class on Tue 29/09 08:00 now ends at 11:00"));
    }

    @Test
    void pastClassesDisappearingFromEduSoftAreNotReported() {
        assertThat(Changes.timetable(List.of(web(LAST_WEEK), web(TUE)), List.of(web(TUE)), NOW)).isEmpty();
    }

    @Test
    void longDateListsAreShortened() {
        List<Meeting> weeks = IntStream.of(1, 8, 15, 22, 29)
                .mapToObj(day -> web(LocalDateTime.of(2026, 10, day, 1, 0))).toList();

        assertThat(Changes.timetable(weeks, List.of(), NOW)).containsExactly(
                line("removed", "IT093IU Web Application Development: 5 classes cancelled on "
                        + "Thu 01/10 08:00, Thu 08/10 08:00, Thu 15/10 08:00 and 2 more"));
    }

    // ---- Exams ----------------------------------------------------------------

    static final ExamInfo FINAL = new ExamInfo("IT093IU", "Web Application Development", "final",
            LocalDateTime.of(2026, 12, 12, 1, 0), "A1.101");

    @Test
    void firstExamSyncGivesOneSummaryLine() {
        assertThat(Changes.exams(null, List.of(FINAL), NOW)).containsExactly(line("added", "Exam schedule loaded: 1 exam"));
    }

    @Test
    void aNewExam() {
        assertThat(Changes.exams(List.of(), List.of(FINAL), NOW)).containsExactly(
                line("added", "New final exam: IT093IU Web Application Development, Sat 12/12 08:00 (A1.101)"));
    }

    @Test
    void aMovedExam() {
        ExamInfo moved = new ExamInfo("IT093IU", "Web Application Development", "final",
                LocalDateTime.of(2026, 12, 14, 6, 0), "A1.101");

        assertThat(Changes.exams(List.of(FINAL), List.of(moved), NOW)).containsExactly(
                line("changed", "IT093IU Web Application Development final exam moved: Sat 12/12 08:00 → Mon 14/12 13:00"));
    }

    @Test
    void anExamRoomChange() {
        ExamInfo otherRoom = new ExamInfo("IT093IU", "Web Application Development", "final", FINAL.startAt(), "A1.202");

        assertThat(Changes.exams(List.of(FINAL), List.of(otherRoom), NOW)).containsExactly(
                line("changed", "IT093IU Web Application Development final exam room: A1.101 → A1.202"));
    }

    @Test
    void aRemovedExam() {
        assertThat(Changes.exams(List.of(FINAL), List.of(), NOW)).containsExactly(
                line("removed", "Final exam removed: IT093IU Web Application Development"));
    }

    @Test
    void anEmptyExamScheduleIsNotAnnouncedAgainAndAgain() {
        assertThat(Changes.exams(null, List.of(), NOW)).isEmpty();
    }

    @Test
    void anEmptyTimetableIsNotAnnouncedAgainAndAgain() {
        assertThat(Changes.timetable(null, List.of(), NOW)).isEmpty();
    }

    // ---- Tuition --------------------------------------------------------------

    static final TuitionInfo UNPAID = new TuitionInfo("20261", 12_500_000, LocalDate.of(2026, 10, 15), "Chưa đóng");

    @Test
    void firstTuitionSync() {
        assertThat(Changes.tuition(null, UNPAID)).containsExactly(
                line("added", "Tuition 20261: balance 12,500,000 VND, due 15/10/2026"));
    }

    @Test
    void tuitionPaid() {
        TuitionInfo paid = new TuitionInfo("20261", 0, UNPAID.dueDate(), "Đã đóng");

        assertThat(Changes.tuition(UNPAID, paid)).containsExactly(
                line("changed", "Tuition 20261: balance 12,500,000 → 0 VND"),
                line("changed", "Tuition 20261: status Chưa đóng → Đã đóng"));
    }

    @Test
    void tuitionDueDateMoved() {
        TuitionInfo moved = new TuitionInfo("20261", UNPAID.balance(), LocalDate.of(2026, 10, 20), UNPAID.statusText());

        assertThat(Changes.tuition(UNPAID, moved)).containsExactly(
                line("changed", "Tuition 20261: due date 15/10/2026 → 20/10/2026"));
    }

    @Test
    void unchangedTuitionGivesNoLines() {
        assertThat(Changes.tuition(UNPAID, UNPAID)).isEmpty();
    }

    // ---- Blackboard -----------------------------------------------------------

    static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 2, 16, 59); // Fri 02/10 23:59 Vietnam

    static BbState webApp(BbItem... items) {
        return new BbState(List.of("Web App"), List.of(items));
    }

    @Test
    void firstBlackboardSyncGivesOneSummaryLine() {
        BbState fresh = webApp(BbItem.announcement("Web App", "a1", "Hi"),
                BbItem.assignment("Web App", "x1", "Lab 3", DUE, null, null, null, null));

        assertThat(Changes.blackboard(null, fresh)).containsExactly(
                line("added", "Blackboard loaded: 1 course, 1 announcement, 1 assignment, 0 materials"));
    }

    @Test
    void noBlackboardCoursesMeansNoLine() {
        assertThat(Changes.blackboard(null, new BbState(List.of(), List.of()))).isEmpty();
    }

    @Test
    void newAnnouncementAssignmentAndMaterial() {
        BbState fresh = webApp(BbItem.announcement("Web App", "a1", "No class on Thursday"),
                BbItem.assignment("Web App", "x1", "Lab 3", DUE, null, null, null, null),
                BbItem.material("Web App", "m1", "Week 5 slides.pdf", "file"),
                BbItem.material("Web App", "m2", "Week 5", "folder"));

        assertThat(Changes.blackboard(webApp(), fresh)).containsExactly(
                line("added", "New announcement · Web App: No class on Thursday"),
                line("added", "New assignment · Web App: Lab 3, due Fri 02/10 23:59"),
                line("added", "New material · Web App: Week 5 slides.pdf"));
    }

    @Test
    void aMovedDeadlineAndANewGrade() {
        BbItem before = BbItem.assignment("Web App", "x1", "Lab 3", DUE, "not_graded", null, 10.0, null);
        BbItem after = BbItem.assignment("Web App", "x1", "Lab 3", LocalDateTime.of(2026, 10, 5, 16, 59), "graded",
                8.5, 10.0, null);

        assertThat(Changes.blackboard(webApp(before), webApp(after))).containsExactly(
                line("changed", "Due date changed · Web App, Lab 3: Fri 02/10 23:59 → Mon 05/10 23:59"),
                line("changed", "New grade · Web App, Lab 3: 8.5/10"));
    }

    // A lecturer uploading a folder of files, or a newly seen course, must not push the rest
    // (e.g. a cancelled class) off the Overview's short "What changed" list.

    @Test
    void manyNewMaterialsInACourseMakeOneLine() {
        BbItem[] slides = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> BbItem.material("Web App", "_" + i + "_1", "Slides " + i + ".pdf", "file"))
                .toArray(BbItem[]::new);

        assertThat(Changes.blackboard(webApp(), webApp(slides))).containsExactly(
                line("added", "10 new materials · Web App: Slides 1.pdf, Slides 2.pdf, Slides 3.pdf and 7 more"));
    }

    @Test
    void aCourseSeenForTheFirstTimeIsOneLine() {
        List<BbItem> physics = List.of(BbItem.announcement("Physics 4", "_1_1", "Welcome"),
                BbItem.assignment("Physics 4", "_2_1", "HW 1", null, null, null, null, null),
                BbItem.material("Physics 4", "_3_1", "Syllabus.pdf", "file"),
                BbItem.material("Physics 4", "_4_1", "Week 1", "folder"));

        assertThat(Changes.blackboard(webApp(), new BbState(List.of("Web App", "Physics 4"), physics))).containsExactly(
                line("added", "New course on Blackboard · Physics 4: 1 announcement, 1 assignment, 2 materials"));
    }

    @ParameterizedTest
    @CsvSource({"8.5, 8.5", "10.0, 10", "0.0, 0", "6.666666667, 6.66667", "123456.7, 123457", "1000000, 1e+06",
            "0.0001, 0.0001", "0.00001, 1e-05", "-2.5, -2.5"})
    void scoresAreWrittenLikePython(double score, String expected) {
        assertThat(Changes.number(score)).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=ChangesTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `Changes`.

- [ ] **Step 3: Write the feed texts**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    static final ZoneOffset VIETNAM = ZoneOffset.ofHours(7);
    static final int MAX_DATES = 3;
    static final int MATERIALS_NAMED = 3; // titles named in a "new materials" line

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

    static OffsetDateTime toVietnam(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(VIETNAM);
    }

    /** "Tue 29/09 08:00" in Vietnam time. */
    static String when(LocalDateTime utc) {
        return WHEN.format(toVietnam(utc));
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
        return DAY.format(value);
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
                            + TIME.format(toVietnam(pair.after().endAt())))));
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

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B -q test -Dtest=ChangesTest)`
Expected: exit code 0; the report says `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 123, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/sync/Changes.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/ChangesTest.java
git commit -m "feat(web): the What changed texts in Java, word for word as the Python site"
```

---

### Task 5: Is a sync due?

A direct port of `app/school/services/scheduling.py`; the test table is `tests/test_school_scheduling.py` row for row.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Scheduling.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SchedulingTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Scheduling.RUN_TIMEOUT` (15 minutes), `MIN_MANUAL_GAP` (5 minutes), `MIN_SCHEDULED_GAP` (1 hour); `record Scheduling.Decision(boolean due, String reason)`; `Scheduling.decide(LocalDateTime now, int intervalHours, LocalDateTime syncRequestedAt, LocalDateTime lastAttemptStartedAt, LocalDateTime lastSuccessStartedAt, LocalDateTime runningSince)`.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SchedulingTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Java twin of tests/test_school_scheduling.py. */
class SchedulingTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    /** "11:55" on 1 October 2026, or null for an empty cell. */
    static LocalDateTime t(String hhmm) {
        return hhmm == null ? null : LocalDateTime.of(NOW.toLocalDate(), LocalTime.parse(hhmm));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            first ever check                                            |       |       |       |       | true  | never
            a sync is running                                           |       | 11:55 | 00:00 | 11:55 | false | running
            a run stuck for 20 minutes no longer blocks Sync now        | 11:50 | 11:40 | 00:00 | 11:40 | true  | requested
            Sync now pressed after the last attempt                     | 11:59 | 11:00 | 11:00 |       | true  | requested
            Sync now pressed but the last attempt was 4 minutes ago     | 11:59 | 11:56 | 09:00 |       | false | too_soon
            Sync now pressed exactly 5 minutes after the last attempt   | 11:59 | 11:55 | 09:00 |       | true  | requested
            an old Sync now request that was already served             | 10:00 | 10:05 | 10:05 |       | false | not_due
            12h interval passed since the last success                  |       | 00:00 | 00:00 |       | true  | interval
            12h interval not yet passed                                 |       | 00:01 | 00:01 |       | false | not_due
            last sync failed 30 minutes ago: wait for the hourly retry  |       | 11:30 | 00:00 |       | false | too_soon
            last sync failed an hour ago: retry                         |       | 11:00 | 00:00 |       | true  | interval
            never succeeded, last attempt failed 2 hours ago            |       | 10:00 |       |       | true  | interval
            """)
    void decide(String name, String requested, String lastAttempt, String lastSuccess, String runningSince,
            boolean due, String reason) {
        Scheduling.Decision decision = Scheduling.decide(NOW, 12, t(requested), t(lastAttempt), t(lastSuccess),
                t(runningSince));

        assertThat(decision).isEqualTo(new Scheduling.Decision(due, reason));
    }

    @Test
    void aSixHourIntervalIsDueSoonerThanTwelve() {
        assertThat(Scheduling.decide(NOW, 6, null, t("05:00"), t("05:00"), null).due()).isTrue();
        assertThat(Scheduling.decide(NOW, 12, null, t("05:00"), t("05:00"), null).due()).isFalse();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=SchedulingTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `Scheduling`.

- [ ] **Step 3: Write the rule**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Scheduling.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.time.Duration;
import java.time.LocalDateTime;

/** When should the laptop agent sync? A pure function, so it is easy to test. All times are UTC. */
public final class Scheduling {

    private Scheduling() {
    }

    public static final Duration RUN_TIMEOUT = Duration.ofMinutes(15); // a run older than this is treated as stuck
    public static final Duration MIN_MANUAL_GAP = Duration.ofMinutes(5); // "Sync now" can't start syncs closer than this
    public static final Duration MIN_SCHEDULED_GAP = Duration.ofHours(1); // failed scheduled syncs retry at most hourly

    /** reason: never / running / requested / too_soon / interval / not_due. */
    public record Decision(boolean due, String reason) {
    }

    public static Decision decide(LocalDateTime now, int intervalHours, LocalDateTime syncRequestedAt,
            LocalDateTime lastAttemptStartedAt, LocalDateTime lastSuccessStartedAt, LocalDateTime runningSince) {
        if (runningSince != null && Duration.between(runningSince, now).compareTo(RUN_TIMEOUT) < 0) {
            return new Decision(false, "running");
        }

        Duration sinceAttempt = lastAttemptStartedAt == null ? null : Duration.between(lastAttemptStartedAt, now);

        if (syncRequestedAt != null
                && (lastAttemptStartedAt == null || syncRequestedAt.isAfter(lastAttemptStartedAt))) {
            if (sinceAttempt != null && sinceAttempt.compareTo(MIN_MANUAL_GAP) < 0) {
                return new Decision(false, "too_soon");
            }
            return new Decision(true, "requested");
        }

        if (lastAttemptStartedAt == null) {
            return new Decision(true, "never");
        }

        Duration interval = Duration.ofHours(intervalHours);
        if (lastSuccessStartedAt == null || Duration.between(lastSuccessStartedAt, now).compareTo(interval) >= 0) {
            if (sinceAttempt.compareTo(MIN_SCHEDULED_GAP) < 0) {
                return new Decision(false, "too_soon");
            }
            return new Decision(true, "interval");
        }

        return new Decision(false, "not_due");
    }
}
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B -q test -Dtest=SchedulingTest)`
Expected: exit code 0; the report says `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 136, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/sync/Scheduling.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/SchedulingTest.java
git commit -m "feat(web): the is-a-sync-due rule in Java"
```

---

### Task 6: Saving a finished run

A direct port of `app/school/services/ingest.py`. `finishRun` ends the run, and for each part that arrived ok: reads the old rows, deletes that term's rows (Blackboard: all the user's), saves the new ones, and records the feed lines; a failed part only goes into the run's `sections`. It runs in one transaction. `IngestTest` is the Java twin of `tests/test_school_ingest.py` and `tests/test_school_blackboard_ingest.py`, plus one case for Review Focus 5. The Python tests went through the API; these open a run and call `Ingest` directly, so the API can come in Task 7.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`

**Interfaces:**
- Consumes: the Task 1 classes and repositories; `SyncContract` and `SyncJson` (Task 3); `Changes` (Task 4); test helper `Payloads` (Task 3).
- Produces: `Ingest` (a Spring bean): `String finishRun(Integer runId, FinishRun payload, LocalDateTime now)` returns the run's new status; `static LocalDateTime toUtc(OffsetDateTime)` (null stays null).

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.edu.hcmiu.sla.school.sync.Payloads.BB;
import static vn.edu.hcmiu.sla.school.sync.Payloads.at;
import static vn.edu.hcmiu.sla.school.sync.Payloads.blackboardPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.failed;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.list;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncementRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignmentRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterialRepository;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeeting;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeetingRepository;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionRepository;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;

/**
 * Java twin of tests/test_school_ingest.py and tests/test_school_blackboard_ingest.py. The Python tests
 * sync through the API; here a test opens a run and hands the upload to {@link Ingest} directly (the API
 * itself is tested in SyncApiTest).
 */
@SpringBootTest
@Transactional
class IngestTest {

    static final Sort BY_ID = Sort.by("id");

    @Autowired
    UserRepository users;

    @Autowired
    SyncJson json;

    @Autowired
    Ingest ingest;

    @Autowired
    SchoolSyncRunRepository runs;

    @Autowired
    SchoolChangeRepository changes;

    @Autowired
    SchoolCourseRepository courses;

    @Autowired
    SchoolClassMeetingRepository meetings;

    @Autowired
    SchoolExamRepository exams;

    @Autowired
    SchoolTuitionRepository tuition;

    @Autowired
    SchoolBbCourseRepository bbCourses;

    @Autowired
    SchoolBbAnnouncementRepository bbAnnouncements;

    @Autowired
    SchoolBbAssignmentRepository bbAssignments;

    @Autowired
    SchoolBbMaterialRepository bbMaterials;

    Integer userId;

    Integer makeUser(String email) {
        return users.save(new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    @BeforeEach
    void user() {
        userId = makeUser("an@example.com");
    }

    /** Opens a run for this user and finishes it with this upload, as the agent would; returns its status. */
    String sync(Integer userId, Object payload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SchoolSyncRun run = runs.save(new SchoolSyncRun(userId, null, "manual", now));
        return ingest.finishRun(run.getId(), json.read(bytes(payload), FinishRun.class), now);
    }

    static Map<String, Object> payloadWithCourse(String code, String name, String term, String start, String end,
            String room) {
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data").put("term_code", term);
        at(payload, "timetable", "data").put("courses", List.of(Map.of("course_code", code, "course_name", name,
                "meetings", List.of(Map.of("start_at", start, "end_at", end, "room", room)))));
        return payload;
    }

    static Map<String, Object> payloadWithCourse(String code, String name) {
        return payloadWithCourse(code, name, "20261", "2026-10-06T08:00:00+07:00", "2026-10-06T10:30:00+07:00", "A2.307");
    }

    SchoolSyncRun lastRun() {
        List<SchoolSyncRun> all = runs.findAll(BY_ID);
        return all.get(all.size() - 1);
    }

    // ---- EduSoft ----------------------------------------------------------------

    @Test
    void aSuccessfulSyncSavesEveryPartWithTimesInUtc() {
        assertThat(sync(userId, fullPayload())).isEqualTo("success");

        SchoolCourse course = courses.findAll(BY_ID).get(0);
        assertThat(List.of(course.getTermCode(), course.getCourseCode(), course.getGroupCode(), course.getLecturer()))
                .containsExactly("20261", "IT093IU", "01", "Nguyen Van A");
        assertThat(course.getCredits()).isEqualByComparingTo(new BigDecimal("4"));
        SchoolClassMeeting meeting = meetings.findAll(BY_ID).get(0);
        assertThat(meeting.getCourse().getId()).isEqualTo(course.getId());
        assertThat(meeting.getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 1, 0));
        assertThat(meeting.getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 3, 30));
        assertThat(meeting.getRoom()).isEqualTo("A2.307");
        SchoolExam exam = exams.findAll(BY_ID).get(0);
        assertThat(exam.getExamType()).isEqualTo("final");
        assertThat(exam.getStartAt()).isEqualTo(LocalDateTime.of(2026, 12, 12, 1, 0));
        assertThat(exam.getDurationMin()).isEqualTo(90);
        assertThat(exam.getRoom()).isEqualTo("A1.101");
        SchoolTuition bill = tuition.findAll(BY_ID).get(0);
        assertThat(List.of(bill.getAmountDue(), bill.getAmountPaid(), bill.getBalance()))
                .containsExactly(12_500_000L, 0L, 12_500_000L);
        assertThat(bill.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(bill.getStatusText()).isEqualTo("Chưa đóng");
    }

    @Test
    void aNewSyncReplacesThatTermsTimetable() {
        sync(userId, payloadWithCourse("IT001IU", "Old course"));

        sync(userId, payloadWithCourse("IT002IU", "New course"));

        assertThat(courses.findAll(BY_ID)).extracting(SchoolCourse::getCourseCode).containsExactly("IT002IU");
        assertThat(meetings.findAll(BY_ID)).singleElement()
                .satisfies(m -> assertThat(m.getCourse().getId()).isEqualTo(courses.findAll(BY_ID).get(0).getId()));
    }

    @Test
    void aFailedPartKeepsItsOldDataAndRecordsWhy() {
        sync(userId, fullPayload());
        Map<String, Object> payload = payloadWithCourse("IT002IU", "New course");
        payload.put("tuition", failed("edusoft_changed", "Tuition table not found"));

        assertThat(sync(userId, payload)).isEqualTo("partial");

        assertThat(tuition.findAll(BY_ID)).singleElement().extracting(SchoolTuition::getBalance).isEqualTo(12_500_000L);
        assertThat(courses.findAll(BY_ID)).extracting(SchoolCourse::getCourseCode).containsExactly("IT002IU");
        assertThat(lastRun().getSections().get("tuition")).isEqualTo(Map.of(
                "status", "failed", "error_code", "edusoft_changed", "error_message", "Tuition table not found"));
        assertThat(lastRun().getSections().get("timetable")).isEqualTo(Map.of("status", "ok"));
    }

    @Test
    void otherTermsAndOtherUsersAreUntouched() {
        sync(userId, payloadWithCourse("IT001IU", "Last term course", "20253", "2026-10-06T08:00:00+07:00",
                "2026-10-06T10:30:00+07:00", "A2.307"));
        sync(makeUser("binh@example.com"), payloadWithCourse("BA001IU", "Binh's course"));

        sync(userId, payloadWithCourse("IT002IU", "This term course"));

        assertThat(courses.findAll(BY_ID)).extracting(SchoolCourse::getCourseCode)
                .containsExactlyInAnyOrder("BA001IU", "IT001IU", "IT002IU");
    }

    @Test
    void aWholeRunErrorChangesNoData() {
        sync(userId, fullPayload());

        String status = sync(userId, Map.of("error_code", "bad_credentials", "error_message", "EduSoft rejected the password"));

        assertThat(status).isEqualTo("failed");
        assertThat(courses.count()).isEqualTo(1);
        assertThat(tuition.count()).isEqualTo(1);
        assertThat(lastRun().getErrorCode()).isEqualTo("bad_credentials");
        assertThat(lastRun().getSections()).isNull();
    }

    @Test
    void eachSyncRecordsWhatChanged() {
        // 2099 keeps these classes "upcoming" whenever the tests run.
        sync(userId, payloadWithCourse("IT001IU", "Web", "20261", "2099-10-06T08:00:00+07:00", "2099-10-06T10:30:00+07:00",
                "A2.307"));
        List<List<String>> firstRunChanges = changes.findAll(BY_ID).stream()
                .map(c -> List.of(c.getSection(), c.getKind())).toList();

        sync(userId, payloadWithCourse("IT001IU", "Web", "20261", "2099-10-06T08:00:00+07:00", "2099-10-06T10:30:00+07:00",
                "LA1.605"));

        assertThat(firstRunChanges).containsExactly(
                List.of("timetable", "added"), List.of("exams", "added"), List.of("tuition", "added"));
        SchoolChange change = changes.findBySyncRunIdOrderById(lastRun().getId()).get(0);
        assertThat(List.of(change.getSection(), change.getKind())).containsExactly("timetable", "changed");
        assertThat(change.getSummary()).contains("IT001IU Web: room A2.307 → LA1.605");
        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId())).hasSize(1);
    }

    @Test
    void aFailedPartRecordsNoChanges() {
        sync(userId, fullPayload());
        Map<String, Object> payload = fullPayload();
        payload.put("tuition", failed("edusoft_changed", "Table not found"));

        sync(userId, payload);

        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId())).isEmpty();
    }

    // ---- Blackboard -------------------------------------------------------------

    String syncBlackboard(Object part) {
        Map<String, Object> payload = fullPayload();
        payload.put("blackboard", part);
        return sync(userId, payload);
    }

    @Test
    void theBlackboardSectionIsSavedWithTimesInUtc() {
        assertThat(syncBlackboard(ok(blackboardPayload()))).isEqualTo("success");

        SchoolBbCourse course = bbCourses.findAll(BY_ID).get(0);
        assertThat(List.of(course.getBbId(), course.getCourseCode(), course.getName()))
                .containsExactly("_101_1", "IT093IU", "Web Application Development");
        SchoolBbAssignment assignment = bbAssignments.findAll(BY_ID).get(0);
        assertThat(assignment.getCourse().getId()).isEqualTo(course.getId());
        assertThat(assignment.getDueAt()).isEqualTo(LocalDateTime.of(2026, 10, 2, 16, 59));
        assertThat(assignment.getScore()).isEqualTo(8.5);
        assertThat(assignment.getStatus()).isEqualTo("graded");
        assertThat(bbAnnouncements.count()).isEqualTo(1);
        assertThat(bbMaterials.findAll(BY_ID).get(0).getPath()).isEqualTo("Week 5");
    }

    @Test
    void aNewBlackboardSyncReplacesTheOldRows() {
        syncBlackboard(ok(blackboardPayload()));
        Map<String, Object> data = blackboardPayload();
        at(data, "courses", 0).put("announcements", List.of());

        syncBlackboard(ok(data));

        assertThat(bbAnnouncements.count()).isZero();
        assertThat(bbCourses.count()).isEqualTo(1);
    }

    @Test
    void aFailedBlackboardPartKeepsTheOldRows() {
        syncBlackboard(ok(blackboardPayload()));

        String status = syncBlackboard(failed("source_changed", "Unexpected"));

        assertThat(status).isEqualTo("partial");
        assertThat(bbAnnouncements.count()).isEqualTo(1);
    }

    @Test
    void blackboardChangesReachTheFeed() {
        syncBlackboard(ok(blackboardPayload()));
        Map<String, Object> data = blackboardPayload();
        list(data, "courses", 0, "announcements").add(Map.of("bb_id", "_502_1", "title", "Room change",
                "text", "Moved to A2.508.", "posted_at", "2026-09-29T02:00:00+00:00", "url", BB + "/x"));

        syncBlackboard(ok(data));

        List<String> summaries = changes.findAll(BY_ID).stream()
                .filter(c -> c.getSection().equals("blackboard")).map(SchoolChange::getSummary).toList();
        assertThat(summaries.get(0)).startsWith("Blackboard loaded: 1 course");
        assertThat(summaries.get(summaries.size() - 1)).isEqualTo("New announcement · Web Application Development: Room change");
    }

    @Test
    void anUnchangedBlackboardSyncAddsNoFeedLines() {
        syncBlackboard(ok(blackboardPayload()));

        syncBlackboard(ok(blackboardPayload()));

        assertThat(changes.findBySyncRunIdOrderById(lastRun().getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=IngestTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `Ingest`.

- [ ] **Step 3: Write the saving**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncement;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncementRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignmentRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterialRepository;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeetingRepository;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionRepository;
import vn.edu.hcmiu.sla.school.sync.Changes.BbItem;
import vn.edu.hcmiu.sla.school.sync.Changes.BbState;
import vn.edu.hcmiu.sla.school.sync.Changes.Change;
import vn.edu.hcmiu.sla.school.sync.Changes.ExamInfo;
import vn.edu.hcmiu.sla.school.sync.Changes.Meeting;
import vn.edu.hcmiu.sla.school.sync.Changes.TuitionInfo;
import vn.edu.hcmiu.sla.school.sync.SyncContract.BbCourse;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Blackboard;
import vn.edu.hcmiu.sla.school.sync.SyncContract.ClassMeeting;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Course;
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

    private final SchoolSyncRunRepository runs;
    private final SchoolChangeRepository changes;
    private final SchoolCourseRepository courses;
    private final SchoolClassMeetingRepository meetings;
    private final SchoolExamRepository exams;
    private final SchoolTuitionRepository tuition;
    private final SchoolBbCourseRepository bbCourses;
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
        this.meetings = meetings;
        this.exams = exams;
        this.tuition = tuition;
        this.bbCourses = bbCourses;
        this.bbAnnouncements = bbAnnouncements;
        this.bbAssignments = bbAssignments;
        this.bbMaterials = bbMaterials;
    }

    /** Aware time as sent -> UTC without an offset, as stored. */
    static LocalDateTime toUtc(OffsetDateTime moment) {
        return moment == null ? null : moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Ends the run with the agent's result and saves its data. Returns the run's status. */
    @Transactional
    public String finishRun(Integer runId, FinishRun payload, LocalDateTime now) {
        SchoolSyncRun run = runs.findById(runId).orElseThrow();
        run.finish(payload.overallStatus(), now, payload.errorCode(), payload.errorMessage());
        if (payload.errorCode() != null) {
            return run.getStatus();
        }

        Integer userId = run.getUserId();
        Map<String, Map<String, String>> summary = new LinkedHashMap<>();
        part(run, summary, "timetable", payload.timetable(), data -> saveTimetable(userId, data, now), now);
        part(run, summary, "exams", payload.exams(), data -> saveExams(userId, data, now), now);
        part(run, summary, "tuition", payload.tuition(), data -> saveTuition(userId, data), now);
        part(run, summary, "blackboard", payload.blackboard(), data -> saveBlackboard(userId, data), now);
        run.setSections(summary);
        return run.getStatus();
    }

    private <T extends Record> void part(SchoolSyncRun run, Map<String, Map<String, String>> summary, String name,
            Section<T> result, Function<T, List<Change>> save, LocalDateTime now) {
        if (result == null) {
            return;
        }
        if (!result.ok()) {
            Map<String, String> failed = new LinkedHashMap<>();
            failed.put("status", "failed");
            failed.put("error_code", result.errorCode());
            failed.put("error_message", result.errorMessage());
            summary.put(name, failed);
            return;
        }
        for (Change change : save.apply(result.data())) {
            changes.save(new SchoolChange(run.getUserId(), run.getId(), name, change.kind(), change.summary(), now));
        }
        summary.put(name, Map.of("status", "ok"));
    }

    private List<Change> saveTimetable(Integer userId, Timetable timetable, LocalDateTime now) {
        String term = timetable.termCode();
        List<Meeting> old = null;
        if (courses.existsByUserIdAndTermCode(userId, term)) {
            old = meetings.findTerm(userId, term).stream()
                    .map(m -> new Meeting(m.getCourse().getCourseCode(), m.getCourse().getCourseName(), m.getStartAt(),
                            m.getEndAt(), m.getRoom()))
                    .toList();
        }
        List<Meeting> fresh = new ArrayList<>();
        for (Course course : timetable.courses()) {
            for (ClassMeeting m : course.meetings()) {
                fresh.add(new Meeting(course.courseCode(), course.courseName(), toUtc(m.startAt()), toUtc(m.endAt()),
                        m.room()));
            }
        }

        meetings.deleteTerm(userId, term);
        courses.deleteTerm(userId, term);
        for (Course course : timetable.courses()) {
            SchoolCourse row = new SchoolCourse(userId, term, course.courseCode(), course.courseName(), course.group(),
                    course.credits() == null ? null : BigDecimal.valueOf(course.credits()), course.lecturer());
            for (ClassMeeting m : course.meetings()) {
                row.addMeeting(toUtc(m.startAt()), toUtc(m.endAt()), m.room());
            }
            courses.save(row);
        }
        return Changes.timetable(old, fresh, now);
    }

    private List<Change> saveExams(Integer userId, Exams data, LocalDateTime now) {
        String term = data.termCode();
        List<SchoolExam> oldRows = exams.findByUserIdAndTermCode(userId, term);
        List<ExamInfo> old = oldRows.isEmpty() ? null : oldRows.stream()
                .map(e -> new ExamInfo(e.getCourseCode(), e.getCourseName(), e.getExamType(), e.getStartAt(), e.getRoom()))
                .toList();
        List<ExamInfo> fresh = data.exams().stream()
                .map(e -> new ExamInfo(e.courseCode(), e.courseName(), e.examType(), toUtc(e.startAt()), e.room()))
                .toList();

        exams.deleteTerm(userId, term);
        for (Exam e : data.exams()) {
            exams.save(new SchoolExam(userId, term, e.courseCode(), e.courseName(), e.examType(), toUtc(e.startAt()),
                    e.durationMin(), e.room(), e.notes()));
        }
        return Changes.exams(old, fresh, now);
    }

    private List<Change> saveTuition(Integer userId, Tuition data) {
        String term = data.termCode();
        TuitionInfo old = tuition.findByUserIdAndTermCode(userId, term)
                .map(t -> new TuitionInfo(t.getTermCode(), t.getBalance(), t.getDueDate(), t.getStatusText()))
                .orElse(null);
        TuitionInfo fresh = new TuitionInfo(term, data.balance(), data.dueDate(), data.statusText());

        tuition.deleteTerm(userId, term);
        tuition.save(new SchoolTuition(userId, term, data.amountDue(), data.amountPaid(), data.balance(),
                data.dueDate(), data.statusText(),
                data.items().stream().map(item -> new SchoolTuition.Item(item.description(), item.amount())).toList()));
        return Changes.tuition(old, fresh);
    }

    private List<Change> saveBlackboard(Integer userId, Blackboard data) {
        List<SchoolBbCourse> oldCourses = bbCourses.findByUserIdOrderById(userId);
        BbState old = null;
        if (!oldCourses.isEmpty()) {
            List<BbItem> items = new ArrayList<>();
            for (SchoolBbAnnouncement a : bbAnnouncements.findByUserIdOrderById(userId)) {
                items.add(BbItem.announcement(a.getCourse().getName(), a.getBbId(), a.getTitle()));
            }
            for (SchoolBbAssignment a : bbAssignments.findByUserIdOrderById(userId)) {
                items.add(BbItem.assignment(a.getCourse().getName(), a.getBbId(), a.getName(), a.getDueAt(),
                        a.getStatus(), a.getScore(), a.getPointsPossible(), a.getGradeText()));
            }
            for (SchoolBbMaterial m : bbMaterials.findByUserIdOrderById(userId)) {
                items.add(BbItem.material(m.getCourse().getName(), m.getBbId(), m.getTitle(), m.getKind()));
            }
            old = new BbState(oldCourses.stream().map(SchoolBbCourse::getName).toList(), items);
        }
        List<BbItem> freshItems = new ArrayList<>();
        for (BbCourse course : data.courses()) {
            course.announcements().forEach(a -> freshItems.add(BbItem.announcement(course.name(), a.bbId(), a.title())));
            course.assignments().forEach(a -> freshItems.add(BbItem.assignment(course.name(), a.bbId(), a.name(),
                    toUtc(a.dueAt()), a.status(), a.score(), a.pointsPossible(), a.gradeText())));
            course.materials().forEach(m -> freshItems.add(BbItem.material(course.name(), m.bbId(), m.title(), m.kind())));
        }
        BbState fresh = new BbState(data.courses().stream().map(BbCourse::name).toList(), freshItems);

        bbAnnouncements.deleteAllOfUser(userId);
        bbAssignments.deleteAllOfUser(userId);
        bbMaterials.deleteAllOfUser(userId);
        bbCourses.deleteAllOfUser(userId);
        for (BbCourse course : data.courses()) {
            SchoolBbCourse row = new SchoolBbCourse(userId, course.bbId(), course.courseCode(), course.name(), course.url());
            course.announcements().forEach(a -> row.getAnnouncements().add(new SchoolBbAnnouncement(row, a.bbId(),
                    a.title(), a.text(), toUtc(a.postedAt()), a.url())));
            course.assignments().forEach(a -> row.getAssignments().add(new SchoolBbAssignment(row, a.bbId(), a.name(),
                    toUtc(a.dueAt()), a.pointsPossible(), a.score(), a.gradeText(), a.status(), a.feedback(), a.url())));
            course.materials().forEach(m -> row.getMaterials().add(new SchoolBbMaterial(row, m.bbId(), m.title(),
                    m.kind(), m.path(), toUtc(m.createdAt()), m.url())));
            bbCourses.save(row);
        }
        return Changes.blackboard(old, fresh);
    }
}
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B -q test -Dtest=IngestTest)`
Expected: exit code 0; the report says `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 148, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/IngestTest.java
git commit -m "feat(web): save a finished sync and record what changed, in Java"
```

---

### Task 7: The sync API

The three addresses the agent calls. `DeviceKeys` ports `devices.py` (keys `sla_` + 43 URL-safe characters; only the SHA-256 is stored, so keys made on either site work on both). `SyncRuns` ports `sync_runs.py`. `DeviceKeyInterceptor` checks the key before every request and records the check-in, like the Python `before_request`. `SyncApiConfig` gives `/api/school/sync/**` its own security chain (no login, no session, no CSRF) and registers the interceptor. `SyncApiTest` is the Java twin of `tests/test_school_sync_api.py`, plus a check that the rest of the site still needs login.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeys.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncRuns.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeyInterceptor.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java`, `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncApiTest.java`

**Interfaces:**
- Consumes: the Task 1 classes and repositories; `SyncJson`, `SyncContract.StartRun`, `SyncContract.FinishRun`, `Payloads` (Task 3); `Scheduling.decide`, `Scheduling.RUN_TIMEOUT` (Task 5); `Ingest.finishRun` (Task 6); the stage 1 `SecurityConfig` chain (unchanged; it still covers every other address).
- Produces (stage 2b's Devices and status pages use these):
  - `DeviceKeys` (a Spring bean): `KEY_PREFIX = "sla_"`; `static String hashKey(String rawKey)`; `NewDevice create(Integer userId, String name, LocalDateTime now)` with `record NewDevice(SchoolSyncDevice device, String rawKey)`; `Optional<SchoolSyncDevice> authenticate(String rawKey)`; `Optional<SchoolSyncDevice> checkIn(String rawKey, LocalDateTime now)`.
  - `SyncRuns` (a Spring bean): `SchoolSyncSettings settings(Integer userId)` (made with 12 hours the first time); `Optional<SchoolSyncRun> latestRun(Integer userId, String... statuses)`; `Check check(Integer userId, LocalDateTime now)` with `record Check(SchoolSyncSettings settings, Scheduling.Decision decision)`; `SchoolSyncRun start(SchoolSyncDevice device, String trigger, LocalDateTime now)` throws `SyncRuns.RunInProgress`.
  - `DeviceKeyInterceptor.DEVICE`: the request attribute holding the checked device.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncApiTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.edu.hcmiu.sla.school.sync.Payloads.BB;
import static vn.edu.hcmiu.sla.school.sync.Payloads.at;
import static vn.edu.hcmiu.sla.school.sync.Payloads.blackboardPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;

/** Java twin of tests/test_school_sync_api.py. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SyncApiTest {

    static final Sort BY_ID = Sort.by("id");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    DeviceKeys deviceKeys;

    @Autowired
    SchoolSyncDeviceRepository devices;

    @Autowired
    SchoolSyncRunRepository runs;

    String key;

    Integer makeUser(String email) {
        return users.save(new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    /** A sync device made the way the Devices page makes it; returns the raw key. */
    String makeDevice(Integer userId) {
        return deviceKeys.create(userId, "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
    }

    @BeforeEach
    void device() {
        key = makeDevice(makeUser("an@example.com"));
    }

    static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key) {
        return request.header("Authorization", "Bearer " + key);
    }

    ResultActions check(String key) throws Exception {
        return mvc.perform(withKey(get("/api/school/sync/check"), key));
    }

    ResultActions start(String key, String trigger) throws Exception {
        return mvc.perform(withKey(post("/api/school/sync/runs"), key)
                .contentType(MediaType.APPLICATION_JSON).content(bytes(Map.of("trigger", trigger))));
    }

    ResultActions finish(String key, int runId, Object payload) throws Exception {
        return mvc.perform(withKey(post("/api/school/sync/runs/" + runId + "/finish"), key)
                .contentType(MediaType.APPLICATION_JSON).content(bytes(payload)));
    }

    int startedRun(String key) throws Exception {
        start(key, "scheduled").andExpect(status().isCreated());
        return lastRun().getId();
    }

    SchoolSyncRun lastRun() {
        List<SchoolSyncRun> all = runs.findAll(BY_ID);
        return all.get(all.size() - 1);
    }

    SchoolSyncDevice theDevice() {
        return devices.findAll(BY_ID).get(0);
    }

    // ---- Device keys ----------------------------------------------------------

    @Test
    void aDeviceKeyIsStoredOnlyAsItsSha256Hash() {
        SchoolSyncDevice device = theDevice();

        assertThat(device.getTokenHash()).isEqualTo(DeviceKeys.hashKey(key)).hasSize(64).doesNotContain(key);
        assertThat(key).startsWith("sla_").hasSize(4 + 43);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Bearer not-a-real-key", "Basic dXNlcjpwYXNz", "Bearer "})
    void requestsWithoutAValidDeviceKeyGet401(String authorization) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/school/sync/check");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\": \"invalid_device_key\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aCancelledDeviceKeyGets401() throws Exception {
        theDevice().setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));

        check(key).andExpect(status().isUnauthorized());
    }

    @Test
    void theApiNeedsNoLoginAndNoCsrfTokenButTheRestOfTheSiteStillDoes() throws Exception {
        start(key, "manual").andExpect(status().isCreated());

        mvc.perform(get("/api/school/other")).andExpect(redirectedUrl("/auth/login"));
    }

    // ---- Check ----------------------------------------------------------------

    @Test
    void theFirstCheckSaysDueAndRecordsTheCheckIn() throws Exception {
        check(key)
                .andExpect(status().isOk())
                .andExpect(content().json("{\"due\": true, \"reason\": \"never\", \"interval_hours\": 12}",
                        JsonCompareMode.STRICT));

        assertThat(theDevice().getLastSeenAt()).isNotNull();
    }

    // ---- Start ----------------------------------------------------------------

    @Test
    void startCreatesARunningRunAndCheckThenSaysRunning() throws Exception {
        start(key, "scheduled")
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"run_id\": " + lastRun().getId() + "}", JsonCompareMode.STRICT));

        SchoolSyncRun run = lastRun();
        assertThat(List.of(run.getStatus(), run.getTrigger())).containsExactly("running", "scheduled");
        assertThat(run.getDeviceId()).isEqualTo(theDevice().getId());
        check(key).andExpect(jsonPath("$.reason").value("running"));
    }

    @Test
    void aSecondStartWhileOneIsRunningGets409() throws Exception {
        start(key, "scheduled");

        start(key, "manual")
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"run_in_progress\"}", JsonCompareMode.STRICT));
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    void startClosesAStuckRunAsTimedOut() throws Exception {
        int stuckId = startedRun(key);
        runs.findById(stuckId).orElseThrow().setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));

        start(key, "manual").andExpect(status().isCreated());

        SchoolSyncRun old = runs.findById(stuckId).orElseThrow();
        assertThat(List.of(old.getStatus(), old.getErrorCode())).containsExactly("failed", "timeout");
        assertThat(old.getFinishedAt()).isNotNull();
        assertThat(lastRun().getStatus()).isEqualTo("running");
    }

    @Test
    void startRejectsAnUnknownTrigger() throws Exception {
        start(key, "whenever").andExpect(status().isUnprocessableContent());

        assertThat(runs.count()).isZero();
    }

    // ---- Finish ---------------------------------------------------------------

    @Test
    void finishMarksTheRunWithItsOverallStatus() throws Exception {
        int runId = startedRun(key);

        finish(key, runId, fullPayload())
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\": \"success\"}", JsonCompareMode.STRICT));

        SchoolSyncRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo("success");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void anInvalidUploadGets422WithoutRepeatingItAndLeavesTheRunRunning() throws Exception {
        int runId = startedRun(key);
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data").put("student_id", "ITITIU20001");

        finish(key, runId, payload)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.details[0].loc[2]").value("student_id"))
                .andExpect(content().string(not(containsString("ITITIU20001"))));
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void aDeviceCannotFinishAnotherUsersRun() throws Exception {
        int runId = startedRun(key);
        String otherKey = makeDevice(makeUser("binh@example.com"));

        finish(otherKey, runId, fullPayload()).andExpect(status().isNotFound());

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void aFinishedRunCannotBeFinishedAgain() throws Exception {
        int runId = startedRun(key);
        finish(key, runId, fullPayload());

        finish(key, runId, fullPayload())
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"run_not_running\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anOversizedUploadGets413() throws Exception {
        int runId = startedRun(key);
        Map<String, Object> payload = fullPayload();
        at(payload, "tuition", "data").put("status_text", "x".repeat(5_100_000));

        finish(key, runId, payload)
                .andExpect(status().isContentTooLarge())
                .andExpect(content().json("{\"error\": \"payload_too_large\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aHeavySemesterOfBlackboardDataInVietnameseIsAccepted() throws Exception {
        // 11 courses x 40 announcements of about 1,500 Vietnamese characters: well over 1 MB once JSON
        // escapes every letter with a diacritic as \\uXXXX, as the agent's Python JSON does.
        int runId = startedRun(key);
        Map<String, Object> course = at(blackboardPayload(), "courses", 0);
        String text = "Thông báo: lớp học bù vào thứ Năm, phòng A2.401. ".repeat(30);
        List<Object> courses = new ArrayList<>();
        for (int c = 1; c <= 11; c++) {
            List<Object> announcements = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                announcements.add(Map.of("bb_id", "_" + c + i + "_1", "title", "Thông báo " + i, "text", text,
                        "posted_at", "2026-09-28T02:00:00+00:00", "url", BB + "/x"));
            }
            Map<String, Object> copy = new HashMap<>(course);
            copy.put("bb_id", "_" + c + "_1");
            copy.put("announcements", announcements);
            courses.add(copy);
        }
        Map<String, Object> payload = fullPayload();
        payload.put("blackboard", ok(Map.of("courses", courses)));

        finish(key, runId, payload).andExpect(status().isOk());

        assertThat(bytes(payload).length).isGreaterThan(1_200_000);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncApiTest)`
Expected: `BUILD FAILURE` with `COMPILATION ERROR` and `cannot find symbol` for `DeviceKeys`.

- [ ] **Step 3: Write device keys and sync runs**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeys.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
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

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncRuns.java`:

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

- [ ] **Step 4: Write the key check, the API's security chain and the controller**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/DeviceKeyInterceptor.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;

/**
 * Runs before every sync API request: the laptop must send "Authorization: Bearer &lt;device key&gt;" for
 * an active device, or it gets 401 {"error": "invalid_device_key"}. The device is then available to the
 * controller as the request attribute {@link #DEVICE}.
 */
@Component
public class DeviceKeyInterceptor implements HandlerInterceptor {

    public static final String DEVICE = "syncDevice";

    private final DeviceKeys deviceKeys;

    public DeviceKeyInterceptor(DeviceKeys deviceKeys) {
        this.deviceKeys = deviceKeys;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String header = request.getHeader("Authorization");
        Optional<SchoolSyncDevice> device = header != null && header.startsWith("Bearer ")
                ? deviceKeys.checkIn(header.substring("Bearer ".length()).strip(), LocalDateTime.now(ZoneOffset.UTC))
                : Optional.empty();
        if (device.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"invalid_device_key\"}");
            return false;
        }
        request.setAttribute(DEVICE, device.get());
        return true;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The sync API at /api/school/sync/** is for the laptop agent, not a browser: no login page, no session
 * and no CSRF token. Instead {@link DeviceKeyInterceptor} checks the device key on every request.
 */
@Configuration
public class SyncApiConfig implements WebMvcConfigurer {

    static final String PATHS = "/api/school/sync/**";

    private final DeviceKeyInterceptor deviceKeyInterceptor;

    public SyncApiConfig(DeviceKeyInterceptor deviceKeyInterceptor) {
        this.deviceKeyInterceptor = deviceKeyInterceptor;
    }

    @Bean
    @Order(1) // before the pages' filter chain, which covers every other address
    SecurityFilterChain syncApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PATHS)
                .authorizeHttpRequests(api -> api.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceKeyInterceptor).addPathPatterns(PATHS);
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.StartRun;

/**
 * The three addresses the laptop agent calls (JSON; the device key instead of a login):
 * "is a sync due?", "I'm starting a sync", and "here is the data, or what went wrong".
 */
@RestController
@RequestMapping("/api/school/sync")
public class SyncApiController {

    private final SyncJson json;
    private final SyncRuns syncRuns;
    private final SchoolSyncRunRepository runs;
    private final Ingest ingest;

    public SyncApiController(SyncJson json, SyncRuns syncRuns, SchoolSyncRunRepository runs, Ingest ingest) {
        this.json = json;
        this.syncRuns = syncRuns;
        this.runs = runs;
        this.ingest = ingest;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    @GetMapping("/check")
    Map<String, Object> check(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device) {
        SyncRuns.Check check = syncRuns.check(device.getUserId(), now());
        return Map.of(
                "due", check.decision().due(),
                "reason", check.decision().reason(),
                "interval_hours", check.settings().getIntervalHours());
    }

    @PostMapping("/runs")
    ResponseEntity<Map<String, Object>> start(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device,
            HttpServletRequest request) throws IOException {
        StartRun body = json.read(json.body(request), StartRun.class);
        SchoolSyncRun run = syncRuns.start(device, body.trigger(), now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("run_id", run.getId()));
    }

    @PostMapping("/runs/{runId}/finish")
    ResponseEntity<Map<String, Object>> finish(@PathVariable int runId,
            @RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device, HttpServletRequest request)
            throws IOException {
        SchoolSyncRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.getUserId().equals(device.getUserId())) {
            return error(HttpStatus.NOT_FOUND, "not_found");
        }
        if (!run.getStatus().equals(SchoolSyncRun.RUNNING)) {
            return error(HttpStatus.CONFLICT, "run_not_running");
        }
        FinishRun body = json.read(json.body(request), FinishRun.class);
        String status = ingest.finishRun(run.getId(), body, now());
        return ResponseEntity.ok(Map.of("status", status));
    }

    @ExceptionHandler(SyncRuns.RunInProgress.class)
    ResponseEntity<Map<String, Object>> runInProgress() {
        return error(HttpStatus.CONFLICT, "run_in_progress");
    }

    @ExceptionHandler(SyncJson.TooLarge.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large");
    }

    @ExceptionHandler(SyncJson.Invalid.class)
    ResponseEntity<Map<String, Object>> invalid(SyncJson.Invalid invalid) {
        return ResponseEntity.unprocessableContent()
                .body(Map.of("error", "invalid_payload", "details", invalid.getDetails()));
    }
}
```

- [ ] **Step 5: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncApiTest)`
Expected: exit code 0; the report says `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 166, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/java/vn/edu/hcmiu/sla/school/sync web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncApiTest.java
git commit -m "feat(web): the sync API for the laptop agent, in Java"
```

---

### Task 8: README, spec, and checks on MySQL and with the real agent

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-26-java-website-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new for later tasks.

- [ ] **Step 1: README**

In `README.md`, section "The Java website (`web/`)", add a sentence at the end of the first paragraph (after "…on the same database and `.env`.").

Sentence to add:

```text
So far it has login, the shared layout, and the School module's sync API for the laptop agent; the School pages come next.
```

Then, right after the "### Tests" subsection (before "### Adding your module in Java"), add:

```markdown
### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.
```

- [ ] **Step 2: Spec status**

In `docs/superpowers/specs/2026-09-26-java-website-design.md`, replace the status line:

Old:

```text
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stages 2–3 to come
```

New:

```text
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stage 2a, the sync API and saving, built (docs/superpowers/plans/2026-09-27-java-stage2a-sync-api.md); stage 2b (School pages) and stage 3 to come
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-09-26-java-website-design.md
git commit -m "docs: the Java sync API, the shared samples, and stage 2a's status"
```

- [ ] **Step 4: Every test on a real MySQL server, without touching the student's**

MySQL 8.4 is installed on the laptop, and the student's database account may not create databases. So start a private, throwaway MySQL server from the same program: its own data folder in the scratchpad, port 3310, `--no-defaults` so it reads no settings file. It never touches the MySQL service on port 3306. `SCRATCH` is the session's scratchpad folder.

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 8.4/bin"
"$MYSQL/mysqld.exe" --no-defaults --initialize-insecure --basedir="C:/Program Files/MySQL/MySQL Server 8.4" --datadir="$(cygpath -w "$SCRATCH/mysqldata")"
```

Then start it in the background (a background shell task, not `&`):

```bash
"$MYSQL/mysqld.exe" --no-defaults --basedir="C:/Program Files/MySQL/MySQL Server 8.4" --datadir="$(cygpath -w "$SCRATCH/mysqldata")" --port=3310 --bind-address=127.0.0.1 --mysqlx=OFF --console
```

When its output says `ready for connections`, create the database and run every test on it:

```bash
"$MYSQL/mysql.exe" --no-defaults -uroot -h127.0.0.1 -P3310 -e "CREATE DATABASE sla_web_test CHARACTER SET utf8mb4"
(cd web && SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3310/sla_web_test" SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= ./mvnw -B test)
```

Expected: `Tests run: 166, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, and the log shows `Database: jdbc:mysql://127.0.0.1:3310/sla_web_test` and `Database version: 8.4.8`.

Stop it and delete its data:

```bash
"$MYSQL/mysqladmin.exe" --no-defaults -uroot -h127.0.0.1 -P3310 shutdown
```

When the background task has ended, `rm -rf "$SCRATCH/mysqldata"`. Expected: port 3310 no longer listening; the MySQL service on 3306 still running.

- [ ] **Step 5: The classes against the student's real tables (read-only)**

Run the site as a teammate would. It reads the repository's `.env`, so it uses the student's real database; with no new migration, Flyway writes nothing and Hibernate only reads the table definitions.

```bash
(cd web && ./mvnw spring-boot:run)
```

(in the background). Expected in the output: `No migration necessary` and `Started SlaWebApplication`, and no `Schema-validation` error. Leave it running for Step 6.

- [ ] **Step 6: One real sync through the Java site**

**Main session only** (it uses the laptop agent's saved EduSoft and Blackboard logins; never a subagent). The agent keeps syncing into the Python site; this sends one manual sync to the Java site on port 8080 with the agent's own code, so it checks the real agent, real data, and the real device key together. The logins only go to EduSoft and Blackboard, as always, and both sites share the tables, so the Python pages show the result.

Save as `$SCRATCH/java_sync.py`:

```python
"""One manual sync into the Java website (port 8080) with the laptop agent's saved settings."""

from sla_agent import cli

loaded = cli._load()
if loaded is None:
    raise SystemExit("The agent isn't set up on this laptop.")
state, password, key = loaded
raise SystemExit(cli._sync("manual", state, password, cli.make_server("http://localhost:8080", key)))
```

Run: `.venv/Scripts/python.exe "$SCRATCH/java_sync.py"`
Expected: `Sync finished.` (or `Sync finished, but couldn't read: …` if EduSoft or Blackboard itself had a problem) and exit code 0. `A sync is already running.` means the agent's scheduled sync is running at that moment: wait 15 minutes and run it again.

Then check what the Java site saved, without printing any `.env` value. Save as `$SCRATCH/java_sync_check.py`:

```python
"""Show the newest sync run and its What-changed lines (reads DATABASE_URL from .env, prints no secrets)."""

import json
from urllib.parse import unquote, urlsplit

import pymysql

url = next(line.split("=", 1)[1].strip().strip('"').strip("'")
           for line in open(".env", encoding="utf-8") if line.strip().startswith("DATABASE_URL="))
parts = urlsplit(url)
db = pymysql.connect(host=parts.hostname, port=parts.port or 3306, user=unquote(parts.username),
                     password=unquote(parts.password or ""), database=parts.path.lstrip("/"), charset="utf8mb4")
with db.cursor() as cursor:
    cursor.execute("SELECT id, `trigger`, status, sections FROM school_sync_runs ORDER BY id DESC LIMIT 1")
    run_id, trigger, status, sections = cursor.fetchone()
    print("run", run_id, trigger, status, json.loads(sections) if sections else None)
    cursor.execute("SELECT section, kind, summary FROM school_changes WHERE sync_run_id = %s ORDER BY id", (run_id,))
    for row in cursor.fetchall():
        print(" ", *row)
db.close()
```

Run: `.venv/Scripts/python.exe "$SCRATCH/java_sync_check.py"`
Expected: `run <id> manual success {…every part "ok"…}`, and What-changed lines only for real changes since the last sync (often none). Open the Python site's School Overview (http://127.0.0.1:5000/school/): the timetable, exams, tuition and Blackboard data look as before.

- [ ] **Step 7: Stop the site**

Stop the background `spring-boot:run` task, then make sure no Java process still listens on port 8080 (stopping Maven doesn't always stop the site's own Java process):

```bash
netstat -ano | grep ":8080 .*LISTENING"
```

Expected: nothing; otherwise `taskkill //F //PID <pid>` for the PID shown.
