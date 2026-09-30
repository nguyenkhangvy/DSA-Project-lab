package vn.edu.hcmiu.sla.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.events.Details;
import vn.edu.hcmiu.sla.school.events.Occurrences;

/** The School classes fit the tables the Python site made, including the JSON and TEXT columns. */
@SpringBootTest
@Transactional
class SchoolTablesTest {

    static final LocalDateTime SEPT_28 = LocalDateTime.of(2026, 9, 28, 1, 0);

    @Autowired
    EntityManager db;

    @Autowired
    UserRepository users;

    @Autowired
    SchoolMailSettingsRepository settings;

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
    void aTuitionBillIsKept() {
        SchoolTuitionBill bill = new SchoolTuitionBill(userId, "E0000020001", "20262",
                "Academic year 2026-2027 - Semester 2", "Thu Học Phí HK 2\nIT093IU", "Thu Học Phí", 40_000_000,
                2_000_000, 0, "unpaid", LocalDate.of(2027, 2, 15), null, null);
        db.persist(bill);

        SchoolTuitionBill again = reloaded(bill, bill.getId());

        assertThat(List.of(again.getBillNo(), again.getDescription(), again.getStatus()))
                .containsExactly("E0000020001", "Thu Học Phí HK 2\nIT093IU", "unpaid");
        assertThat(List.of(again.getPayable(), again.getFee())).containsExactly(38_000_000L, 0L);
        assertThat(again.getDueDate()).isEqualTo(LocalDate.of(2027, 2, 15));
        assertThat(again.isPaid()).isFalse();
    }

    @Test
    void anOwnEventKeepsItsRuleAndSkippedDays() {
        SchoolMyEvent event = new SchoolMyEvent(userId, SEPT_28);
        event.set(new Details("Tự học buổi tối", "Library", "Chapter 3", new Occurrences.Rule(LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 12, 20), Occurrences.WEEKS, 1,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), Set.of()),
                LocalTime.of(17, 0), LocalTime.of(19, 0)), SEPT_28);
        event.skip(LocalDate.of(2026, 10, 6));
        db.persist(event);

        SchoolMyEvent again = reloaded(event, event.getId());

        assertThat(List.of(again.getTitle(), again.getPlace(), again.getRepeatKind(), again.getWeekdays()))
                .containsExactly("Tự học buổi tối", "Library", "weeks", "1,2,3");
        assertThat(again.rule().skipped()).containsExactly(LocalDate.of(2026, 10, 6));
        assertThat(Occurrences.all(again.rule())).hasSize(32);
        assertThat(again.details().start()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void changingTheRuleDropsSkippedDaysThatAreNoLongerDaysOfIt() {
        SchoolMyEvent event = new SchoolMyEvent(userId, SEPT_28);
        Occurrences.Rule monTueWed = new Occurrences.Rule(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 20),
                Occurrences.WEEKS, 1, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), Set.of());
        event.set(new Details("Tự học", null, null, monTueWed, LocalTime.of(17, 0), LocalTime.of(19, 0)), SEPT_28);
        event.skip(LocalDate.of(2026, 10, 6)); // a Tuesday
        event.skip(LocalDate.of(2026, 10, 7)); // a Wednesday
        db.persist(event);

        event.set(new Details("Tự học", null, null, new Occurrences.Rule(monTueWed.first(), monTueWed.last(),
                Occurrences.WEEKS, 1, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), Set.of()),
                LocalTime.of(17, 0), LocalTime.of(19, 0)), SEPT_28);

        assertThat(reloaded(event, event.getId()).rule().skipped()).containsExactly(LocalDate.of(2026, 10, 6));
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

    @Test
    void mailCategoriesAndDatesAreKeptAsCommaSeparatedText() {
        SchoolMail mail = new SchoolMail(userId, "a".repeat(64), "00AB", null, SEPT_28, "P.CTSV [OSS]",
                "oss@hcmiu.edu.vn", "Workshop", List.of("event", "training_points"), false,
                List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)), true, null);
        mail.getChanges().add(new SchoolMailChange(mail, "IT093IU", "makeup", LocalDate.of(2026, 10, 3),
                java.time.LocalTime.of(13, 15), null, "A2.401"));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 10, 2), java.time.LocalTime.of(8, 0), null));
        mail.getSessions().add(new SchoolMailSession(mail, LocalDate.of(2026, 9, 29), java.time.LocalTime.of(13, 30),
                java.time.LocalTime.of(16, 30)));
        mail.setRegisterBy(LocalDate.of(2026, 9, 25));
        db.persist(mail);
        SchoolMail empty = new SchoolMail(userId, "b".repeat(64), "00AC", "T1", SEPT_28, "", "", "", List.of(), false,
                List.of(), false, null);
        db.persist(empty);

        SchoolMail again = reloaded(mail, mail.getId());

        assertThat(again.getCategories()).containsExactly("event", "training_points");
        assertThat(again.getRegisterBy()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(again.getDates()).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2));
        assertThat(again.getChanges()).extracting(SchoolMailChange::getStart).containsExactly(java.time.LocalTime.of(13, 15));
        assertThat(again.getSessions()).extracting(s -> s.getDay() + " " + s.getStart() + "-" + s.getEnd())
                .containsExactly("2026-09-29 13:30-16:30", "2026-10-02 08:00-null");
        SchoolMail emptyAgain = db.find(SchoolMail.class, empty.getId());
        assertThat(List.of(emptyAgain.getCategories(), emptyAgain.getDates())).containsExactly(List.of(), List.of());
    }

    @Test
    void aChoiceWithoutMoveToKeepsNoCategories() {
        SchoolMailChoice choice = new SchoolMailChoice(userId, "a".repeat(64), SEPT_28);
        choice.setDone(true, SEPT_28);
        db.persist(choice);

        SchoolMailChoice again = reloaded(choice, choice.getId());

        assertThat(List.of(again.isDone(), again.isMoved())).containsExactly(true, false);
        assertThat(again.getCategories()).isNull();
    }

    @Test
    void aChoiceRemembersOpeningAndTheSettingIsOnWithoutARow() {
        SchoolMailChoice choice = new SchoolMailChoice(userId, "a".repeat(64), SEPT_28);
        choice.open(SEPT_28);
        db.persist(choice);

        assertThat(List.of(reloaded(choice, choice.getId()).isOpened(), settings.autoDone(userId)))
                .containsExactly(true, true);
        settings.save(new SchoolMailSettings(userId, false));
        assertThat(settings.autoDone(userId)).isFalse();
    }
}
