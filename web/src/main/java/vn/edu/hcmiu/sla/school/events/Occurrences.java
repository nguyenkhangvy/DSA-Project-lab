package vn.edu.hcmiu.sla.school.events;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which days a student's own event happens on (docs/superpowers/specs/2026-09-30-my-events-design.md, 3.2).
 * Pure functions; days are Vietnam dates.
 */
public final class Occurrences {

    private Occurrences() {
    }

    public static final String ONCE = "once";
    public static final String DAYS = "days";
    public static final String WEEKS = "weeks";
    public static final String MONTHS = "months";
    public static final List<String> KINDS = List.of(ONCE, DAYS, WEEKS, MONTHS);
    public static final int MAX_SPAN_DAYS = 366; // the last day is at most this many days after the first

    /** An event's repeat rule: weekdays for WEEKS only; skipped days are taken out of its days. */
    public record Rule(LocalDate first, LocalDate last, String repeat, int every, Set<DayOfWeek> weekdays,
            Set<LocalDate> skipped) {

        public Rule {
            weekdays = weekdays == null || weekdays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekdays);
            skipped = skipped == null ? Set.of() : Set.copyOf(skipped);
        }

        public Rule withSkipped(Set<LocalDate> days) {
            return new Rule(first, last, repeat, every, weekdays, days);
        }
    }

    /** The event's days in [from, to], in order, without the skipped days. */
    public static List<LocalDate> days(Rule rule, LocalDate from, LocalDate to) {
        LocalDate start = from.isAfter(rule.first()) ? from : rule.first();
        LocalDate end = to.isBefore(rule.last()) ? to : rule.last();
        List<LocalDate> days = new ArrayList<>();
        if (start.isAfter(end)) {
            return days;
        }
        switch (rule.repeat()) {
            case ONCE -> days.add(rule.first());
            case DAYS -> {
                for (LocalDate d = rule.first(); !d.isAfter(end); d = d.plusDays(rule.every())) {
                    if (!d.isBefore(start)) {
                        days.add(d);
                    }
                }
            }
            case WEEKS -> {
                LocalDate week0 = rule.first().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    long week = ChronoUnit.DAYS.between(week0, d) / 7;
                    if (week % rule.every() == 0 && rule.weekdays().contains(d.getDayOfWeek())) {
                        days.add(d);
                    }
                }
            }
            case MONTHS -> {
                int dayOfMonth = rule.first().getDayOfMonth();
                for (YearMonth month = YearMonth.from(rule.first()); !month.atDay(1).isAfter(end);
                        month = month.plusMonths(rule.every())) {
                    if (month.isValidDay(dayOfMonth)) {
                        LocalDate d = month.atDay(dayOfMonth);
                        if (!d.isBefore(start) && !d.isAfter(end)) {
                            days.add(d);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown repeat: " + rule.repeat());
        }
        days.removeAll(rule.skipped());
        return days;
    }

    /** All the event's days, without the skipped ones. */
    public static List<LocalDate> all(Rule rule) {
        return days(rule, rule.first(), rule.last());
    }

    /** Whether the rule has this day, skipped or not. */
    public static boolean falls(Rule rule, LocalDate day) {
        return !days(rule.withSkipped(Set.of()), day, day).isEmpty();
    }

    /** "Once", "Every day", "Every 3 days", "Every week on Mon, Tue, Wed", "Every month on the 15th". */
    public static String describe(Rule rule) {
        return switch (rule.repeat()) {
            case ONCE -> "Once";
            case DAYS -> rule.every() == 1 ? "Every day" : "Every " + rule.every() + " days";
            case WEEKS -> (rule.every() == 1 ? "Every week" : "Every " + rule.every() + " weeks") + " on "
                    + rule.weekdays().stream().map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                            .collect(Collectors.joining(", "));
            case MONTHS -> (rule.every() == 1 ? "Every month" : "Every " + rule.every() + " months") + " on the "
                    + ordinal(rule.first().getDayOfMonth());
            default -> throw new IllegalArgumentException("Unknown repeat: " + rule.repeat());
        };
    }

    /** 1st, 2nd, 3rd, 4th, 11th, 21st, 22nd, 23rd, 31st. */
    static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** "1,2,3" for Mon, Tue, Wed (ISO day numbers), as the table keeps them. */
    public static String weekdayNumbers(Set<DayOfWeek> days) {
        return days.stream().sorted().map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(","));
    }

    /** The weekdays of "1,2,3"; none for null or "". */
    public static Set<DayOfWeek> weekdaysOf(String numbers) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (numbers == null || numbers.isBlank()) {
            return days;
        }
        Arrays.stream(numbers.split(",")).map(String::strip).filter(n -> !n.isEmpty())
                .forEach(n -> days.add(DayOfWeek.of(Integer.parseInt(n))));
        return days;
    }
}
