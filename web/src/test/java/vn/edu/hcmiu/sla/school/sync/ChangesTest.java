package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

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
