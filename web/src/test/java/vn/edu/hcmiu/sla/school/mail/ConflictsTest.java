package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.mail.Conflicts.Busy;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;

/** Conflict or No conflict for one session (spec 2026-09-28-mailbox-events-design.md, section 4.5). */
class ConflictsTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    static Session session(String start, String end) {
        return new Session(DAY, LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    static Busy busy(String name, String start, String end) {
        return new Busy(name, DAY.atTime(LocalTime.parse(start)), DAY.atTime(LocalTime.parse(end)));
    }

    static String mark(Session session, Busy... busy) {
        return Conflicts.of(session, List.of(busy)).text();
    }

    @Test
    void overlappingTimesConflict() {
        assertThat(mark(session("13:00", "14:00"), busy("Web Application", "13:30", "15:00")))
                .isEqualTo("⚠ Conflict: Web Application");
        assertThat(Conflicts.of(session("13:00", "14:00"), List.of(busy("Web Application", "12:00", "16:00")))
                .conflict()).isTrue();
    }

    @Test
    void backToBackIsNoConflict() {
        assertThat(mark(session("13:00", "14:00"), busy("Physics 4", "14:00", "15:30"), busy("Lab", "11:30", "13:00")))
                .isEqualTo("✓ No conflict");
    }

    @Test
    void aSessionWithoutAnEndLastsAnHour() {
        assertThat(mark(session("13:00", null), busy("Physics 4", "13:59", "15:00"))).startsWith("⚠ Conflict");
        assertThat(mark(session("13:00", null), busy("Physics 4", "14:00", "15:00"))).isEqualTo("✓ No conflict");
    }

    @Test
    void moreClashesAreCountedAndTheFirstIsNamed() {
        assertThat(mark(session("13:00", "15:00"), busy("Final exam: Physics 4", "13:40", "15:10"),
                busy("Web Application", "12:30", "14:00"), busy("Talkshow B", "14:30", "16:00")))
                .isEqualTo("⚠ Conflict: Web Application + 2");
    }

    @Test
    void anEmptyTimetableOrAnotherDayIsNoConflict() {
        assertThat(mark(session("13:00", "14:00"))).isEqualTo("✓ No conflict");
        assertThat(mark(session("13:00", "14:00"), new Busy("Web Application", DAY.plusDays(1).atTime(13, 0),
                DAY.plusDays(1).atTime(14, 0)))).isEqualTo("✓ No conflict");
    }
}
