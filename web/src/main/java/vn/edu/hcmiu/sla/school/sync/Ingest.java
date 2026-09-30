package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSession;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionBillRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionStatus;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionStatusRepository;
import vn.edu.hcmiu.sla.school.sync.Changes.BbItem;
import vn.edu.hcmiu.sla.school.sync.Changes.BbState;
import vn.edu.hcmiu.sla.school.sync.Changes.Change;
import vn.edu.hcmiu.sla.school.sync.Changes.ExamInfo;
import vn.edu.hcmiu.sla.school.sync.Changes.Meeting;
import vn.edu.hcmiu.sla.school.sync.SyncContract.BbCourse;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Blackboard;
import vn.edu.hcmiu.sla.school.sync.SyncContract.ClassMeeting;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Course;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exam;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Exams;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Iupay;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailClassChange;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailItem;
import vn.edu.hcmiu.sla.school.sync.SyncContract.MailSession;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Outlook;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Section;
import vn.edu.hcmiu.sla.school.sync.SyncContract.Timetable;
import vn.edu.hcmiu.sla.school.sync.SyncContract.TuitionBill;

/**
 * Saves the result of a sync run, in one transaction. Each part that arrived correctly replaces that
 * user's rows for that term (Blackboard and Outlook: all of them), after comparing old and new rows for the
 * "What changed" feed (mail never adds to it). A part that failed keeps its old rows. An old agent's tuition
 * part is ignored. The Java twin of ingest.py.
 */
@Service
public class Ingest {

    private final SchoolSyncRunRepository runs;
    private final SchoolChangeRepository changes;
    private final SchoolCourseRepository courses;
    private final SchoolClassMeetingRepository meetings;
    private final SchoolExamRepository exams;
    private final SchoolBbCourseRepository bbCourses;
    private final SchoolBbAnnouncementRepository bbAnnouncements;
    private final SchoolBbAssignmentRepository bbAssignments;
    private final SchoolBbMaterialRepository bbMaterials;
    private final SchoolMailRepository mails;
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailSessionRepository mailSessions;
    private final SchoolMailChoiceRepository mailChoices;
    private final SchoolMailStatusRepository mailStatus;
    private final SchoolTuitionBillRepository tuitionBills;
    private final SchoolTuitionStatusRepository tuitionStatus;

    public Ingest(SchoolSyncRunRepository runs, SchoolChangeRepository changes, SchoolCourseRepository courses,
            SchoolClassMeetingRepository meetings, SchoolExamRepository exams,
            SchoolBbCourseRepository bbCourses, SchoolBbAnnouncementRepository bbAnnouncements,
            SchoolBbAssignmentRepository bbAssignments, SchoolBbMaterialRepository bbMaterials,
            SchoolMailRepository mails, SchoolMailChangeRepository mailChanges,
            SchoolMailSessionRepository mailSessions, SchoolMailChoiceRepository mailChoices,
            SchoolMailStatusRepository mailStatus, SchoolTuitionBillRepository tuitionBills,
            SchoolTuitionStatusRepository tuitionStatus) {
        this.runs = runs;
        this.changes = changes;
        this.courses = courses;
        this.meetings = meetings;
        this.exams = exams;
        this.bbCourses = bbCourses;
        this.bbAnnouncements = bbAnnouncements;
        this.bbAssignments = bbAssignments;
        this.bbMaterials = bbMaterials;
        this.mails = mails;
        this.mailChanges = mailChanges;
        this.mailSessions = mailSessions;
        this.mailChoices = mailChoices;
        this.mailStatus = mailStatus;
        this.tuitionBills = tuitionBills;
        this.tuitionStatus = tuitionStatus;
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
        part(run, summary, "iupay", payload.iupay(), data -> saveIupay(userId, data, now), now);
        part(run, summary, "blackboard", payload.blackboard(), data -> saveBlackboard(userId, data), now);
        part(run, summary, "outlook", payload.outlook(), data -> saveOutlook(userId, data, now), now);
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

    /** Replaces the student's bills with this read and remembers when IUPay was read. Bills never enter "What changed". */
    private List<Change> saveIupay(Integer userId, Iupay data, LocalDateTime now) {
        tuitionBills.deleteAllOfUser(userId);
        for (TuitionBill b : data.bills()) {
            tuitionBills.save(new SchoolTuitionBill(userId, b.billNo(), b.termCode(), b.termName(), b.description(),
                    b.feeType(), b.amount(), b.discount(), b.fee(), b.status(), b.dueDate(), b.paidOn(), b.channel()));
        }
        tuitionStatus.save(new SchoolTuitionStatus(userId, now));
        return List.of();
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

    /**
     * Replaces the user's mail with this upload, keeping the student's Done and Move to… choices for the emails
     * still there. An email sent twice with the same key is kept once (the first).
     */
    private List<Change> saveOutlook(Integer userId, Outlook data, LocalDateTime now) {
        mailChanges.deleteAllOfUser(userId);
        mailSessions.deleteAllOfUser(userId);
        mails.deleteAllOfUser(userId);
        Set<String> keys = new LinkedHashSet<>();
        for (MailItem item : data.emails()) {
            if (!keys.add(item.key())) {
                continue;
            }
            SchoolMail mail = new SchoolMail(userId, item.key(), item.entryId(), item.threadId(),
                    toUtc(item.receivedAt()), item.senderName(), item.senderAddress(), item.subject(), item.categories(),
                    item.fromLecturer(), item.dates(), item.sorted(), item.blackboardTitle());
            mail.setRegisterBy(item.registerBy());
            for (MailClassChange c : item.classChanges()) {
                mail.getChanges().add(new SchoolMailChange(mail, c.courseCode(), c.kind(), c.day(), c.start(), c.end(),
                        c.room()));
            }
            for (MailSession s : item.sessions()) {
                mail.getSessions().add(new SchoolMailSession(mail, s.day(), s.start(), s.end()));
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
