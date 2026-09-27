package vn.edu.hcmiu.sla.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Stored times are UTC; a Vietnam day runs from 17:00 UTC the day before to 17:00 UTC that day. */
class VietnamTimeTest {

    @Test
    void aVietnamDayStartsAt1700UtcTheDayBefore() {
        assertThat(VietnamTime.dayStart(LocalDate.of(2026, 10, 5))).isEqualTo(LocalDateTime.of(2026, 10, 4, 17, 0));
        assertThat(VietnamTime.date(LocalDateTime.of(2026, 10, 4, 16, 59))).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(VietnamTime.date(LocalDateTime.of(2026, 10, 4, 17, 0))).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void aVietnamClockTimeBecomesUtc() {
        assertThat(VietnamTime.utc(LocalDate.of(2026, 9, 29), LocalTime.of(8, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 29, 1, 0));
    }

    @Test
    void timesAreWrittenInVietnamTime() {
        LocalDateTime utc = LocalDateTime.of(2026, 10, 2, 16, 59); // Fri 02/10 23:59 in Vietnam

        assertThat(VietnamTime.when(utc)).isEqualTo("Fri 02/10 23:59");
        assertThat(VietnamTime.clock(utc)).isEqualTo("23:59");
        assertThat(VietnamTime.wallClock(utc)).isEqualTo("2026-10-02T23:59:00");
        assertThat(VietnamTime.fullDate(LocalDate.of(2026, 10, 15))).isEqualTo("15/10/2026");
        assertThat(VietnamTime.dayLabel(LocalDate.of(2026, 9, 29))).isEqualTo("Tue 29/09");
    }
}
