package vn.edu.hcmiu.sla.school.sync;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
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

    static final String ERROR_CODES = "bad_credentials|session_expired|network|edusoft_changed|source_changed"
            + "|extra_verification|outlook_not_set_up|outlook_blocked|unknown";
    public static final String MAIL_CATEGORIES =
            "class|event|training_points|school_task|money|requests_account|system_notice|promotion";
    static final String BLACKBOARD_URL = "(?s)https://blackboard\\.hcmiu\\.edu\\.vn/.*";

    public record StartRun(@NotNull @Pattern(regexp = "scheduled|manual|import|mail") String trigger) {
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

    // ---- IUPay ------------------------------------------------------------------

    static final String BILL_STATUSES = "unpaid|paid|paying|partly_paid";

    /** One IUPay bill. Amounts are VND; dueDate and paidOn are Vietnam dates. */
    public record TuitionBill(
            @NotNull @Chars(min = 1, max = 40) String billNo,
            @NotNull @Chars(min = 1, max = 20) String termCode,
            @Chars(min = 1, max = 255) String termName,
            @NotNull @Chars(min = 1, max = 1000) String description,
            @Chars(min = 1, max = 255) String feeType,
            @NotNull @PositiveOrZero Long amount,
            @PositiveOrZero Long discount,
            @PositiveOrZero Long fee,
            @NotNull @Pattern(regexp = BILL_STATUSES) String status,
            LocalDate dueDate,
            LocalDate paidOn,
            @Chars(max = 100) String channel) {

        public TuitionBill {
            discount = discount == null ? 0L : discount;
            fee = fee == null ? 0L : fee;
        }
    }

    /** Every bill IUPay lists for the student, paid or not. */
    public record Iupay(@Size(max = 500) List<@Valid TuitionBill> bills) {

        public Iupay {
            bills = bills == null ? List.of() : bills;
        }

        @AssertTrue(message = "bill_no must differ")
        boolean isEachBillOnce() {
            return bills.stream().map(TuitionBill::billNo).distinct().count() == bills.size();
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
            @NotNull @Pattern(regexp = "[0-9A-F]{2,512}") String entryId,
            @Chars(max = 64) String threadId,
            @NotNull OffsetDateTime receivedAt,
            @Chars(max = 255) String senderName,
            @Chars(max = 255) String senderAddress,
            @Chars(max = 500) String subject,
            @Size(max = 2) List<@NotNull @Pattern(regexp = MAIL_CATEGORIES) String> categories,
            Boolean fromLecturer,
            @Size(max = 30) List<@NotNull LocalDate> dates,
            @Size(max = 10) List<@Valid MailSession> sessions,
            LocalDate registerBy,
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
            sessions = sessions == null ? List.of() : sessions;
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
            @Valid Section<Tuition> tuition, // agents from before IUPay: accepted, never counted or saved
            @Valid Section<Iupay> iupay,
            @Valid Section<Blackboard> blackboard,
            @Valid Section<Outlook> outlook) {

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
            return errorCode == null || (sections().isEmpty() && tuition == null);
        }

        @AssertTrue(message = "send either error_code or at least one section")
        boolean isErrorOrSections() {
            return errorCode != null || !sections().isEmpty();
        }

        /** The parts that were sent, by name, in the order timetable, exams, iupay, blackboard, outlook. */
        public Map<String, Section<?>> sections() {
            Map<String, Section<?>> sent = new LinkedHashMap<>();
            if (timetable != null) {
                sent.put("timetable", timetable);
            }
            if (exams != null) {
                sent.put("exams", exams);
            }
            if (iupay != null) {
                sent.put("iupay", iupay);
            }
            if (blackboard != null) {
                sent.put("blackboard", blackboard);
            }
            if (outlook != null) {
                sent.put("outlook", outlook);
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
