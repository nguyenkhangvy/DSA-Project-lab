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
import static vn.edu.hcmiu.sla.school.sync.Payloads.outlookPayload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

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
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
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
    EntityManager db;

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

    @Autowired
    SchoolMailRepository mails;

    @Autowired
    SchoolMailChangeRepository mailChanges;

    @Autowired
    SchoolMailSessionRepository mailSessions;

    @Autowired
    SchoolMailJoinedRepository mailJoined;

    @Autowired
    SchoolMailChoiceRepository mailChoices;

    @Autowired
    SchoolMailStatusRepository mailStatus;

    Integer userId;

    Integer makeUser(String email) {
        return users.save(new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    @BeforeEach
    void user() {
        userId = makeUser("an@example.com");
    }

    /**
     * Opens a run for this user and finishes it with this upload, as the agent would; returns its status.
     * Like a real request, it starts from what the database holds and writes everything out at the end,
     * so a second sync compares with rows read back from the database, not with objects still in memory.
     */
    String sync(Integer userId, Object payload) {
        db.flush();
        db.clear();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SchoolSyncRun run = runs.save(new SchoolSyncRun(userId, null, "manual", now));
        String status = ingest.finishRun(run.getId(), json.read(bytes(payload), FinishRun.class), now);
        db.flush();
        db.clear();
        return status;
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
        assertThat(List.of(workshop.isFromLecturer(), workshop.isSorted())).containsExactly(false, true);
        assertThat(workshop.getRegisterBy()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(saved.get(0).getRegisterBy()).isNull();
        assertThat(mailSessions.findOfUser(userId))
                .extracting(s -> s.getMail().getMailKey() + " " + s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly(workshop.getMailKey() + " 2026-09-25 13:30-16:30",
                        workshop.getMailKey() + " 2026-10-02 08:00-null");
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
        assertThat(mailSessions.findOfUser(userId)).isEmpty();
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
        choose(other, KEY_1);

        syncOutlook(userId, ok(outlookPayload()));

        assertThat(mails.findByUserIdOrderByReceivedAtDescIdDesc(other)).hasSize(3);
        assertThat(mailSessions.findOfUser(other)).hasSize(2);
        assertThat(mailChoices.findByUserId(other)).hasSize(1);
    }
}
