package vn.edu.hcmiu.sla.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;

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
    void tuitionItemsAreKeptAsJson() {
        SchoolTuition tuition = new SchoolTuition(userId, "20261", 12_500_000, 0, 12_500_000,
                LocalDate.of(2026, 10, 15), "Chưa đóng", List.of(new SchoolTuition.Item("Học phí", 12_500_000)));
        db.persist(tuition);

        SchoolTuition again = reloaded(tuition, tuition.getId());

        assertThat(again.getItems()).containsExactly(new SchoolTuition.Item("Học phí", 12_500_000));
        assertThat(again.getStatusText()).isEqualTo("Chưa đóng");
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
