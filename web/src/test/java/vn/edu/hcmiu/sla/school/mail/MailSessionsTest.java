package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.mail.MailSessions.Line;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;

/**
 * An event's sessions against the timetable as the Timetable page shows it: classes with their changes, exams and
 * other joined events (spec 2026-09-28-mailbox-events-design.md, sections 4.5 and 4.6). Times in comments are
 * Vietnam time; the rows are saved in UTC.
 */
@SpringBootTest
@Transactional
class MailSessionsTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // UTC: Mon 28/09 08:00 in Vietnam
    static final LocalDateTime NOW_IN_VIETNAM = LocalDateTime.of(2026, 9, 28, 8, 0);
    static final LocalDate TUE = LocalDate.of(2026, 9, 29);
    static final String TALK = "a".repeat(64);
    static final String OTHER = "f".repeat(64);

    @Autowired
    EntityManager db;

    @Autowired
    MailSessions mailSessions;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void aTimetable() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        // Tue 29/09 13:00-15:30 and Wed 30/09 08:00-10:00
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 9, 29, 6, 0), LocalDateTime.of(2026, 9, 29, 8, 30), "A2.401"));
        data.course(an, "PH012IU", "Physics 4",
                new Meeting(LocalDateTime.of(2026, 9, 30, 1, 0), LocalDateTime.of(2026, 9, 30, 3, 0), "A1.309"));
    }

    static Session at(LocalDate day, String start, String end) {
        return new Session(day, LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    /** An event email with these sessions, as its Mailbox card. */
    Card event(AppUser who, String key, List<String> categories, Session... sessions) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1", null, NOW.minusDays(1), "P.CTSV [OSS]", "oss@hcmiu.edu.vn",
                "Workshop", categories, false, List.of(), true, null);
        db.persist(mail);
        db.flush();
        return Mailbox.build(List.of(mail), Map.of(), Map.of(key, Arrays.asList(sessions)), NOW_IN_VIETNAM).card(key);
    }

    Card event(Session... sessions) {
        return event(an, TALK, List.of("event"), sessions);
    }

    List<String> marks(Card card) {
        return mailSessions.lines(an.id(), card, NOW_IN_VIETNAM).stream().map(line -> line.mark().text()).toList();
    }

    void join(String key, LocalDate day, String start, String end, String title) {
        db.persist(new SchoolMailJoined(an.id(), key, day, LocalTime.parse(start), end == null ? null : LocalTime.parse(end),
                title, null, false, false, NOW));
        db.flush();
    }

    @Test
    void aSessionDuringAClassConflictsWithIt() {
        assertThat(marks(event(at(TUE, "14:00", "16:00"), at(TUE, "15:30", "17:00"))))
                .containsExactly("⚠ Conflict: Web Application Development", "✓ No conflict");
    }

    @Test
    void anOnlineClassStillCountsAndACancelledOneDoesNot() {
        data.save(data.emailChange(data.emailChange(data.lecturerEmail(an, "e".repeat(64), NOW.minusDays(3), null),
                "IT093IU", "online", TUE, null, null, null), "PH012IU", "cancelled", TUE.plusDays(1), null, null, null));

        assertThat(marks(event(at(TUE, "14:00", null), at(TUE.plusDays(1), "08:30", "09:30"))))
                .containsExactly("⚠ Conflict: Web Application Development", "✓ No conflict");
    }

    @Test
    void aMakeUpClassCounts() {
        LocalDate saturday = TUE.plusDays(4);
        data.save(data.emailChange(data.lecturerEmail(an, "e".repeat(64), NOW.minusDays(3), null), "IT093IU", "makeup",
                saturday, LocalTime.of(8, 0), LocalTime.of(10, 0), "A2.401"));

        assertThat(marks(event(at(saturday, "09:00", null)))).containsExactly("⚠ Conflict: Web Application Development");
    }

    @Test
    void examsCountWithTheirLengthOrNinetyMinutes() {
        // Thu 01/10 09:00 without a length, Fri 02/10 13:00 for 120 minutes
        db.persist(new SchoolExam(an.id(), "20261", "MA026IU", "Probability", "final", LocalDateTime.of(2026, 10, 1, 2, 0),
                null, "A2.101", null));
        db.persist(new SchoolExam(an.id(), "20261", "PH012IU", "Physics 4", "midterm", LocalDateTime.of(2026, 10, 2, 6, 0),
                120, "A1.309", null));
        db.flush();

        assertThat(marks(event(at(TUE.plusDays(2), "10:20", null), at(TUE.plusDays(2), "10:30", null),
                at(TUE.plusDays(3), "14:30", null))))
                .containsExactly("⚠ Conflict: Final exam: Probability", "✓ No conflict", "⚠ Conflict: Midterm exam: Physics 4");
    }

    @Test
    void anotherJoinedEventCountsButTheEmailsOwnDoesNot() {
        join(OTHER, TUE, "17:00", "18:00", "Talkshow B");
        join(TALK, TUE, "17:30", null, "Workshop");

        List<Line> lines = mailSessions.lines(an.id(), event(at(TUE, "17:30", null)), NOW_IN_VIETNAM);

        assertThat(lines).extracting(line -> line.mark().text()).containsExactly("⚠ Conflict: Talkshow B");
        assertThat(lines).extracting(Line::joined).containsExactly(true);
    }

    @Test
    void withoutATimetableEverySessionIsFree() {
        AppUser binh = data.user("binh@example.com");

        Card card = event(binh, TALK, List.of("event"), at(TUE, "14:00", "16:00"));

        assertThat(mailSessions.lines(binh.id(), card, NOW_IN_VIETNAM)).extracting(line -> line.mark().text())
                .containsExactly("✓ No conflict");
    }

    @Test
    void sessionsAddedByHandShowWithTheFoundOnesButNotThoseOver() {
        join(TALK, TUE.plusDays(5), "18:00", "20:00", "Workshop");
        join(TALK, TUE.minusDays(2), "18:00", "20:00", "Workshop");

        List<Line> lines = mailSessions.lines(an.id(), event(at(TUE, "15:30", null)), NOW_IN_VIETNAM);

        assertThat(lines).extracting(Line::when, Line::found, Line::joined).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Tue 29/09 from 15:30", true, false),
                org.assertj.core.groups.Tuple.tuple("Sun 04/10 18:00–20:00", false, true));
        assertThat(lines.get(0).id()).isEqualTo("2026-09-29T15:30");
    }

    @Test
    void onlyEventsAndSchoolTasksHaveSessions() {
        Card invoice = event(an, OTHER, List.of("money"), at(TUE, "14:00", null));

        assertThat(mailSessions.lines(an.id(), invoice, NOW_IN_VIETNAM)).isEmpty();
    }
}
