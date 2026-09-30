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
import vn.edu.hcmiu.sla.school.events.MyEvents;
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
import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionBillRepository;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionStatus;
import vn.edu.hcmiu.sla.school.model.SchoolTuitionStatusRepository;
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
    private final SchoolTuitionBillRepository tuitionBills;
    private final SchoolTuitionStatusRepository tuitionStatus;
    private final SchoolBbCourseRepository bbCourses;
    private final SchoolBbAnnouncementRepository announcements;
    private final SchoolBbAssignmentRepository assignments;
    private final SchoolBbMaterialRepository materials;
    private final MyEvents myEvents;

    public SchoolController(Clock clock, SyncRuns syncRuns, Schedule schedule, SchoolSyncDeviceRepository devices,
            SchoolSyncRunRepository runs, SchoolExamRepository exams, SchoolChangeRepository changes,
            SchoolTuitionBillRepository tuitionBills, SchoolTuitionStatusRepository tuitionStatus,
            SchoolBbCourseRepository bbCourses,
            SchoolBbAnnouncementRepository announcements, SchoolBbAssignmentRepository assignments,
            SchoolBbMaterialRepository materials, MyEvents myEvents) {
        this.clock = clock;
        this.syncRuns = syncRuns;
        this.schedule = schedule;
        this.devices = devices;
        this.runs = runs;
        this.exams = exams;
        this.changes = changes;
        this.tuitionBills = tuitionBills;
        this.tuitionStatus = tuitionStatus;
        this.bbCourses = bbCourses;
        this.announcements = announcements;
        this.assignments = assignments;
        this.materials = materials;
        this.myEvents = myEvents;
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
        List<SchoolTuitionBill> bills = tuitionBills.findByUserIdOrderById(user.id());
        model.addAttribute("tuitionNotice", TuitionBills.notice(bills, today));
        model.addAttribute("recentBills", TuitionBills.recent(bills, today));
        model.addAttribute("changes", changes.findTop10ByUserIdOrderByIdDesc(user.id()));
        model.addAttribute("now", now);
        return "school/index";
    }

    @GetMapping("/timetable")
    String timetable(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("myEvents", myEvents.lines(user.id()));
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
        List<SchoolTuitionBill> bills = tuitionBills.findByUserIdOrderById(user.id());
        model.addAttribute("checkedAt",
                tuitionStatus.findById(user.id()).map(SchoolTuitionStatus::getCheckedAt).orElse(null));
        model.addAttribute("toPay", TuitionBills.toPay(bills));
        model.addAttribute("paid", TuitionBills.paid(bills));
        model.addAttribute("today", VietnamTime.date(now()));
        return "school/tuition";
    }

    @PostMapping("/sync-now")
    String syncNow(@AuthenticationPrincipal AppUser user, RedirectAttributes redirect) {
        syncRuns.requestSync(user.id(), now());
        Flash.success(redirect, "Sync requested. Your laptop will pick it up at its next check-in.");
        return "redirect:/school";
    }
}
