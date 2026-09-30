package vn.edu.hcmiu.sla.school.schedule;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.events.Occurrences;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncementRepository;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignmentRepository;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeeting;
import vn.edu.hcmiu.sla.school.model.SchoolClassMeetingRepository;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolCourseRepository;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolExamRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailChangeRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;
import vn.edu.hcmiu.sla.school.model.SchoolMyEventRepository;
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

    public static final Map<String, String> EXAM_LABELS = Map.of(
            "final", "Final exam", "midterm", "Midterm exam", "other", "Exam");
    static final Duration DEFAULT_CLASS_LENGTH = Duration.ofMinutes(90); // a make-up of a course with no known classes
    static final Duration JOINED_WITHOUT_END = Duration.ofHours(1); // an event session whose email gave no end
    public static final String MINE = "mine"; // one day of the student's own event
    public static final String MY_EVENT = "My event";

    /**
     * A class, an exam, a joined event or one day of the student's own event on the timetable. Times are UTC;
     * endAt may be empty. label: "Final exam" for exams, "Event" or "★ Training points" for joined events, "My event"
     * for own events (whose code is empty and room the place typed). change: online / cancelled / makeup, from a
     * Blackboard announcement or a lecturer's email, with source the app's page where it was announced (for an event,
     * its email in Mailbox; for an own event, its edit page for that day). allDay: a make-up class announced without
     * a time. eventId: the own event's id, for kind "mine" only.
     */
    public record Item(String kind, LocalDateTime startAt, LocalDateTime endAt, String code, String title, String room,
            String label, String change, Source source, boolean allDay, Integer eventId) {

        Item changed(String change, Source source, String room) {
            return new Item(kind, startAt, endAt, code, title, room, label, change, source, allDay, eventId);
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
    private final SchoolMailChangeRepository mailChanges;
    private final SchoolMailJoinedRepository joined;
    private final SchoolMyEventRepository myEvents;

    public Schedule(SchoolClassMeetingRepository meetings, SchoolExamRepository exams, SchoolCourseRepository courses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments,
            SchoolMailChangeRepository mailChanges, SchoolMailJoinedRepository joined,
            SchoolMyEventRepository myEvents) {
        this.meetings = meetings;
        this.exams = exams;
        this.courses = courses;
        this.announcements = announcements;
        this.assignments = assignments;
        this.mailChanges = mailChanges;
        this.joined = joined;
        this.myEvents = myEvents;
    }

    /** Classes, exams, joined events and own events starting in [startUtc, endUtc), with announced changes, in time order. */
    @Transactional(readOnly = true)
    public List<Item> itemsBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> items = new ArrayList<>();
        for (SchoolClassMeeting m : meetings.findStarting(userId, startUtc, endUtc)) {
            items.add(new Item("class", m.getStartAt(), m.getEndAt(), m.getCourse().getCourseCode(),
                    m.getCourse().getCourseName(), m.getRoom(), null, null, null, false, null));
        }
        for (SchoolExam e : exams.findStarting(userId, startUtc, endUtc)) {
            LocalDateTime end = e.getDurationMin() == null ? null : e.getStartAt().plusMinutes(e.getDurationMin());
            items.add(new Item("exam", e.getStartAt(), end, e.getCourseCode(), e.getCourseName(), e.getRoom(),
                    EXAM_LABELS.getOrDefault(e.getExamType(), "Exam"), null, null, false, null));
        }
        List<Item> result = new ArrayList<>(withChanges(items, announcedChanges(userId), timetableCourses(userId),
                startUtc, endUtc));
        result.addAll(joinedEvents(userId, startUtc, endUtc));
        result.addAll(ownEvents(userId, startUtc, endUtc));
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
        return result;
    }

    /**
     * The event sessions the student joined from Mailbox, starting in [startUtc, endUtc)
     * (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, section 4.7). Class changes never apply to them.
     */
    private List<Item> joinedEvents(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        List<Item> events = new ArrayList<>();
        for (SchoolMailJoined row : joined.findByUserIdAndDayBetweenOrderByDayAscStartAsc(userId,
                VietnamTime.date(startUtc), VietnamTime.date(endUtc))) {
            LocalDateTime startAt = VietnamTime.utc(row.getDay(), row.getStart());
            LocalDateTime endAt = row.getEnd() != null ? VietnamTime.utc(row.getDay(), row.getEnd())
                    : startAt.plus(JOINED_WITHOUT_END);
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                events.add(new Item("event", startAt, endAt, null, row.getTitle(), row.getPlace(),
                        row.isTrainingPoints() ? "★ Training points" : "Event", null, Source.email(row.getMailKey()),
                        false, null));
            }
        }
        return events;
    }

    /** The days of the student's own events starting in [startUtc, endUtc) (docs/superpowers/specs/2026-09-30-my-events-design.md, 4.4). */
    private List<Item> ownEvents(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
        LocalDate from = VietnamTime.date(startUtc);
        LocalDate to = VietnamTime.date(endUtc);
        List<Item> items = new ArrayList<>();
        for (SchoolMyEvent event : myEvents.findOverlapping(userId, from, to)) {
            for (LocalDate day : Occurrences.days(event.rule(), from, to)) {
                LocalDateTime startAt = VietnamTime.utc(day, event.getStartTime());
                if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                    items.add(new Item(MINE, startAt, VietnamTime.utc(day, event.getEndTime()), null, event.getTitle(),
                            event.getPlace(), MY_EVENT, null, Source.myEvent(event.getId(), day), false, event.getId()));
                }
            }
        }
        return items;
    }

    /** The items of one Vietnam day. */
    public List<Item> itemsOn(Integer userId, LocalDate day) {
        return itemsBetween(userId, VietnamTime.dayStart(day), VietnamTime.dayStart(day.plusDays(1)));
    }

    /** Blackboard deadlines in [startUtc, endUtc), soonest first, with their course. */
    public List<SchoolBbAssignment> deadlinesBetween(Integer userId, LocalDateTime startUtc, LocalDateTime endUtc) {
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
                    item = item.changed(change.kind(), change.source(),
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
                            "makeup", change.source(), true, null));
                }
                continue;
            }
            LocalDateTime startAt = VietnamTime.utc(change.day(), change.start());
            LocalDateTime endAt = change.end() != null && change.end().isAfter(change.start())
                    ? VietnamTime.utc(change.day(), change.end())
                    : startAt.plus(course.usual());
            if (!startAt.isBefore(startUtc) && startAt.isBefore(endUtc)) {
                result.add(new Item("class", startAt, endAt, change.code(), course.name(), change.room(), null,
                        "makeup", change.source(), false, null));
            }
        }
        result.sort(Comparator.comparing(Item::startAt).thenComparing(Item::kind));
        return result;
    }
}
