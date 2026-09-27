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
 * Text limits use {@link Chars} (characters, as pydantic counts them); list limits use {@code @Size}.
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
            @Chars(max = 50) String room) {

        @AssertTrue(message = "end_at must be after start_at")
        boolean isEndAfterStart() {
            return startAt == null || endAt == null || endAt.isAfter(startAt);
        }
    }

    public record Course(
            @NotNull @Chars(min = 1, max = 20) String courseCode,
            @NotNull @Chars(min = 1, max = 255) String courseName,
            @Chars(max = 20) String group,
            @PositiveOrZero @DecimalMax("50") Double credits,
            @Chars(max = 255) String lecturer,
            @Size(max = 200) List<@Valid ClassMeeting> meetings) {

        public Course {
            meetings = meetings == null ? List.of() : meetings;
        }
    }

    public record Timetable(
            @NotNull @Chars(min = 1, max = 20) String termCode,
            @Chars(max = 100) String termName,
            @NotNull @Size(max = 40) List<@Valid Course> courses) {
    }

    public record Exam(
            @NotNull @Chars(min = 1, max = 20) String courseCode,
            @NotNull @Chars(min = 1, max = 255) String courseName,
            @NotNull @Pattern(regexp = "midterm|final|other") String examType,
            @NotNull OffsetDateTime startAt,
            @Min(1) @Max(600) Integer durationMin,
            @Chars(max = 50) String room,
            @Chars(max = 500) String notes) {
    }

    public record Exams(
            @NotNull @Chars(min = 1, max = 20) String termCode,
            @NotNull @Size(max = 60) List<@Valid Exam> exams) {
    }

    /** Amounts are VND. */
    public record TuitionItem(@NotNull @Chars(min = 1, max = 255) String description, @NotNull Long amount) {
    }

    /** Amounts are VND; a negative balance means overpaid. */
    public record Tuition(
            @NotNull @Chars(min = 1, max = 20) String termCode,
            @NotNull @PositiveOrZero Long amountDue,
            @NotNull @PositiveOrZero Long amountPaid,
            @NotNull Long balance,
            LocalDate dueDate,
            @Chars(max = 255) String statusText,
            @Size(max = 60) List<@Valid TuitionItem> items) {

        public Tuition {
            items = items == null ? List.of() : items;
        }
    }

    // ---- Blackboard -------------------------------------------------------------

    public record BbAnnouncement(
            @NotNull @Chars(min = 1, max = 64) String bbId,
            @NotNull @Chars(min = 1, max = 255) String title,
            @Chars(max = 5000) String text,
            OffsetDateTime postedAt,
            @NotNull @Chars(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {

        public BbAnnouncement {
            text = text == null ? "" : text;
        }
    }

    public record BbAssignment(
            @NotNull @Chars(min = 1, max = 64) String bbId,
            @NotNull @Chars(min = 1, max = 255) String name,
            OffsetDateTime dueAt,
            @PositiveOrZero Double pointsPossible,
            Double score,
            @Chars(max = 50) String gradeText,
            @NotNull @Pattern(regexp = "not_graded|needs_grading|graded|exempt") String status,
            @Chars(max = 1000) String feedback,
            @NotNull @Chars(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {
    }

    public record BbMaterial(
            @NotNull @Chars(min = 1, max = 64) String bbId,
            @NotNull @Chars(min = 1, max = 255) String title,
            @NotNull @Pattern(regexp = "file|folder|link|document|other") String kind,
            @Chars(max = 500) String path,
            OffsetDateTime createdAt,
            @NotNull @Chars(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url) {

        public BbMaterial {
            path = path == null ? "" : path;
        }
    }

    public record BbCourse(
            @NotNull @Chars(min = 1, max = 64) String bbId,
            @Chars(min = 1, max = 20) String courseCode,
            @NotNull @Chars(min = 1, max = 255) String name,
            @NotNull @Chars(max = 500) @Pattern(regexp = BLACKBOARD_URL) String url,
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
            @Chars(min = 1, max = 500) String errorMessage) {

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
            @Chars(min = 1, max = 500) String errorMessage,
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
