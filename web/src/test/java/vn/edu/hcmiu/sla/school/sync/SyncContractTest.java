package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.edu.hcmiu.sla.school.sync.Payloads.BB;
import static vn.edu.hcmiu.sla.school.sync.Payloads.at;
import static vn.edu.hcmiu.sla.school.sync.Payloads.blackboardPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.failed;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.iupayPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.list;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;
import static vn.edu.hcmiu.sla.school.sync.Payloads.outlookPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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
import org.junit.jupiter.params.provider.ValueSource;

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
                List.of("exams", "data", "bank_account"));
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
                Arguments.of("failed-without-code", Map.of("status", "failed", "error_message", "Exam table not found")),
                Arguments.of("unknown-error-code", failed("made_up_code", "x")),
                Arguments.of("unknown-status", Map.of("status", "done", "data", Map.of())),
                Arguments.of("ok-with-an-error", Map.of("status", "ok", "data", at(fullPayload(), "exams", "data"),
                        "error_code", "unknown")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badParts")
    void eachPartIsEitherOkWithDataOrFailedWithAReason(String name, Map<String, Object> part) {
        Map<String, Object> payload = fullPayload();
        payload.put("exams", part);

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
        wholeRunError.put("error_code", "bad_credentials");
        wholeRunError.put("error_message", "EduSoft rejected the password");
        Map<String, Object> onlyTimetable = new HashMap<>();
        onlyTimetable.put("exams", null);
        return Stream.of(
                Arguments.of("all-ok", Map.of(), "success"),
                Arguments.of("one-failed", Map.of("exams", failedPart()), "partial"),
                Arguments.of("all-failed", Map.of("timetable", failedPart(), "exams", failedPart()), "failed"),
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

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "blackboard");
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

    @Test
    void anOutlookSectionIsAcceptedNextToTheOthers() {
        Map<String, Object> payload = fullPayload();
        payload.put("outlook", ok(outlookPayload()));

        FinishRun finish = read(payload);

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "outlook");
        var emails = finish.outlook().data().emails();
        assertThat(emails.get(0).classChanges().get(0).kind()).isEqualTo("online");
        assertThat(emails.get(1).categories()).containsExactly("event", "training_points");
        assertThat(emails.get(2).sorted()).isFalse();
        assertThat(emails.get(2).classChanges().get(0).start()).isEqualTo(LocalTime.of(13, 15));
        assertThat(List.of(emails.get(1).fromLecturer(), emails.get(1).sorted(), emails.get(1).classChanges()))
                .containsExactly(false, true, List.of());
        assertThat(emails.get(1).sessions()).extracting(s -> s.day() + " " + s.start() + "-" + s.end())
                .containsExactly("2026-09-25 13:30-16:30", "2026-10-02 08:00-null");
        assertThat(emails.get(0).sessions()).isEmpty();
        assertThat(emails.get(1).registerBy()).isEqualTo(java.time.LocalDate.of(2026, 9, 23));
        assertThat(emails.get(0).registerBy()).isNull();
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
                        "sessions", 1).remove("start")),
                Arguments.of("bad-register-by", (Consumer<Map<String, Object>>) p -> at(p, "emails", 1)
                        .put("register_by", "22/9/2026")));
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

    // ---- IUPay ------------------------------------------------------------------

    @Test
    void iupayBillsAreAcceptedNextToTheOthers() {
        Map<String, Object> payload = fullPayload();
        payload.put("iupay", ok(iupayPayload()));

        FinishRun finish = read(payload);

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "iupay");
        var first = finish.iupay().data().bills().get(0);
        assertThat(List.of(first.billNo(), first.status(), first.paidOn(), first.amount(), first.discount()))
                .containsExactly("E0000014104", "paid", LocalDate.of(2026, 9, 30), 65_250_000L, 0L);
        assertThat(first.dueDate()).isNull();
    }

    @Test
    void missingDiscountAndFeeMeanZero() {
        var bills = read(new HashMap<>(Map.of("iupay", ok(Payloads.iupayUnpaidPayload())))).iupay().data().bills();

        assertThat(bills).extracting(b -> b.status() + " " + b.discount() + " " + b.fee() + " " + b.dueDate())
                .containsExactly("unpaid 2000000 0 2027-02-15", "paying 0 0 2027-01-31", "partly_paid 0 0 2026-12-31");
    }

    @Test
    void noBillsIsAValidAnswer() {
        FinishRun finish = read(new HashMap<>(Map.of("iupay", ok(Map.of("bills", List.of())))));

        assertThat(finish.overallStatus()).isEqualTo("success");
    }

    static Stream<Arguments> badIupayData() {
        return Stream.<Arguments>of(
                Arguments.of("negative-amount", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0).put("amount", -1)),
                Arguments.of("unknown-status", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("status", "cancelled")),
                Arguments.of("same-bill-twice", (Consumer<Map<String, Object>>) p -> list(p, "bills")
                        .add(new HashMap<>(at(p, "bills", 0)))),
                Arguments.of("extra-field", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("student_name", "Nguyen Van An")),
                Arguments.of("blank-description", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("description", "   ")),
                Arguments.of("bill-number-too-long", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("bill_no", "E".repeat(41))),
                Arguments.of("bad-date", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("paid_on", "30/09/2026")),
                Arguments.of("fraction", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0).put("amount", 12.5)),
                Arguments.of("too-many-bills", (Consumer<Map<String, Object>>) p -> {
                    List<Object> bills = new ArrayList<>();
                    for (int i = 0; i < 72; i++) {
                        bills.addAll(list(p, "bills"));
                    }
                    p.put("bills", bills);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badIupayData")
    void badIupayDataIsRejected(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> data = iupayPayload();
        change.accept(data);

        assertRefused(new HashMap<>(Map.of("iupay", ok(data))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "extra_verification", "bad_credentials", "source_changed"})
    void aFailedIupayPartSaysWhy(String code) {
        FinishRun finish = read(Map.of(
                "timetable", fullPayload().get("timetable"),
                "iupay", failed(code, "IUPay problem")));

        assertThat(finish.overallStatus()).isEqualTo("partial");
    }

    @Test
    void anOldAgentsTuitionPartIsAcceptedButNotCounted() throws IOException {
        FinishRun finish = json.read(Files.readAllBytes(Payloads.SAMPLES.resolve("finish-old-agent-tuition.json")),
                FinishRun.class);

        assertThat(finish.sections().keySet()).containsExactly("timetable");
        assertThat(finish.tuition().ok()).isFalse();
        assertThat(finish.overallStatus()).isEqualTo("success");
    }

    @Test
    void anOldTuitionPartAloneIsNotAnUpload() {
        assertRefused(new HashMap<>(Map.of("tuition", failed("edusoft_changed", "Headers changed"))));
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
    void textLimitsCountCharactersLikePydanticNotJavaUnits() {
        // An emoji is one character for the agent's Python, which cuts announcements to 5000 characters,
        // but two UTF-16 units for Java's String.length().
        Map<String, Object> data = blackboardPayload();
        at(data, "courses", 0, "announcements", 0).put("text", "📢" + "x".repeat(4999));
        at(data, "courses", 0, "announcements", 0).put("title", "📌" + "t".repeat(254));

        var announcement = read(new HashMap<>(Map.of("blackboard", ok(data)))).blackboard().data().courses().get(0)
                .announcements().get(0);

        assertThat(announcement.text().codePointCount(0, announcement.text().length())).isEqualTo(5000);
        at(data, "courses", 0, "announcements", 0).put("text", "📢" + "x".repeat(5000));
        assertRefused(new HashMap<>(Map.of("blackboard", ok(data))));
    }

    @Test
    void numbersAreNotTextAndFractionsAreNotWholeNumbers() {
        Map<String, Object> numberAsText = fullPayload();
        at(numberAsText, "timetable", "data", "courses", 0).put("course_code", 93);
        Map<String, Object> fraction = fullPayload();
        at(fraction, "exams", "data", "exams", 0).put("duration_min", 90.5);

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
