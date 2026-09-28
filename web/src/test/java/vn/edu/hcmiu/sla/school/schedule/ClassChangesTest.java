package vn.edu.hcmiu.sla.school.schedule;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Announced;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.ClassChange;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Posted;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Slot;
import vn.edu.hcmiu.sla.school.schedule.ClassChanges.Source;

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
        return new Posted("MA026IU", Source.course(5), title, "", posted);
    }

    @Test
    void theNewestAnnouncementWinsForACourseAndDate() {
        Map<Slot, ClassChange> changes = ClassChanges.changesFrom(List.of(
                row("Class on 24/9 is cancelled", LocalDateTime.of(2026, 9, 21, 0, 0)),
                row("Online class on 24/9", LocalDateTime.of(2026, 9, 20, 0, 0))));

        assertThat(changes).containsExactly(Map.entry(new Slot("MA026IU", LocalDate.of(2026, 9, 24), "class"),
                new ClassChange("MA026IU", Source.course(5), "cancelled", LocalDate.of(2026, 9, 24), null, null, null)));
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
                new Posted(null, Source.course(5), "Online class on 24/9", "", POSTED)))).isEmpty();
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
