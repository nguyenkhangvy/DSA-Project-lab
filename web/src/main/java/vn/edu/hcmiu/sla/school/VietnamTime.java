package vn.edu.hcmiu.sla.school;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The database keeps times in UTC without an offset (as the Python site does); pages show Vietnam time,
 * UTC+7 all year. A Vietnam day runs from 17:00 UTC the day before to 17:00 UTC that day.
 */
public final class VietnamTime {

    private VietnamTime() {
    }

    public static final ZoneOffset VIETNAM = ZoneOffset.ofHours(7);

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.ENGLISH);
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** UTC as stored -> the same moment in Vietnam. */
    public static OffsetDateTime of(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(VIETNAM);
    }

    /** The day it is in Vietnam at this UTC moment. */
    public static LocalDate date(LocalDateTime utc) {
        return of(utc).toLocalDate();
    }

    /** Midnight in Vietnam on this day, as UTC. */
    public static LocalDateTime dayStart(LocalDate day) {
        return utc(day, LocalTime.MIDNIGHT);
    }

    /** A Vietnam wall-clock time on this day, as UTC. */
    public static LocalDateTime utc(LocalDate day, LocalTime clock) {
        return day.atTime(clock).atOffset(VIETNAM).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** "Tue 29/09 08:00". */
    public static String when(LocalDateTime utc) {
        return WHEN.format(of(utc));
    }

    /** "08:00". */
    public static String clock(LocalDateTime utc) {
        return CLOCK.format(of(utc));
    }

    /** "29/09/2026". */
    public static String fullDate(LocalDate day) {
        return FULL_DATE.format(day);
    }

    /** "Tue 29/09". */
    public static String dayLabel(LocalDate day) {
        return DAY_LABEL.format(day);
    }

    /** Vietnam wall-clock time without an offset, "2026-09-29T08:00:00", as the calendar wants it. */
    public static String wallClock(LocalDateTime utc) {
        return WALL_CLOCK.format(of(utc));
    }
}
