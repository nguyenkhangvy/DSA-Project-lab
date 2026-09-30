package vn.edu.hcmiu.sla.school.events;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static vn.edu.hcmiu.sla.school.events.Occurrences.DAYS;
import static vn.edu.hcmiu.sla.school.events.Occurrences.MONTHS;
import static vn.edu.hcmiu.sla.school.events.Occurrences.ONCE;
import static vn.edu.hcmiu.sla.school.events.Occurrences.WEEKS;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.edu.hcmiu.sla.school.events.Occurrences.Rule;

class OccurrencesTest {

    static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2026, month, dayOfMonth);
    }

    static Rule rule(LocalDate first, LocalDate last, String repeat, int every, DayOfWeek... weekdays) {
        return new Rule(first, last, repeat, every, weekdays.length == 0 ? Set.of() : EnumSet.of(weekdays[0], weekdays),
                Set.of());
    }

    // The student's example: "Tự học buổi tối", every week on Mon, Tue, Wed, Mon 05/10 to Sun 20/12/2026.
    static final Rule SELF_STUDY = rule(day(10, 5), day(12, 20), WEEKS, 1, MONDAY, TUESDAY, WEDNESDAY);

    @Test
    void onceIsTheFirstDayWhenItIsInTheRange() {
        Rule once = rule(day(10, 5), day(10, 5), ONCE, 1);

        assertThat(Occurrences.days(once, day(10, 1), day(10, 31))).containsExactly(day(10, 5));
        assertThat(Occurrences.days(once, day(10, 6), day(10, 31))).isEmpty();
    }

    @Test
    void everyDayAndEveryThreeDays() {
        assertThat(Occurrences.all(rule(day(10, 1), day(10, 5), DAYS, 1))).hasSize(5);
        assertThat(Occurrences.all(rule(day(10, 1), day(10, 12), DAYS, 3)))
                .containsExactly(day(10, 1), day(10, 4), day(10, 7), day(10, 10));
    }

    @Test
    void theStudentsWeeklyExampleHas33Days() {
        List<LocalDate> days = Occurrences.all(SELF_STUDY);

        assertThat(days).hasSize(33);
        assertThat(days.subList(0, 3)).containsExactly(day(10, 5), day(10, 6), day(10, 7));
        assertThat(days.get(32)).isEqualTo(day(12, 16));
    }

    @Test
    void everyTwoWeeksCountsFromTheWeekOfTheFirstDay() {
        // Thu 01/10 is in the week of Mon 28/09 (week 0): its Saturday 03/10 counts, then 17/10 and 31/10.
        assertThat(Occurrences.all(rule(day(10, 1), day(10, 31), WEEKS, 2, SATURDAY)))
                .containsExactly(day(10, 3), day(10, 17), day(10, 31));
    }

    @Test
    void everyTwoWeeksOnMonAndWedFromAThursdayCountsWeeksFromItsMonday() {
        // Thu 01/10 is in the week of Mon 28/09 (week 0), whose Mon and Wed are before it; week 2 is 12/10-18/10.
        // Seven-day blocks counted from Thu 01/10 would give 05/10, 07/10, 19/10 and 21/10 instead.
        assertThat(Occurrences.all(rule(day(10, 1), day(10, 31), WEEKS, 2, MONDAY, WEDNESDAY)))
                .containsExactly(day(10, 12), day(10, 14), day(10, 26), day(10, 28));
    }

    @Test
    void aWeeklyEventStartingMidWeekSkipsTheTickedDaysBeforeItsFirstDay() {
        // First day Wed 07/10, weekly on Mon and Wed: Mon 05/10 is before it.
        assertThat(Occurrences.all(rule(day(10, 7), day(10, 14), WEEKS, 1, MONDAY, WEDNESDAY)))
                .containsExactly(day(10, 7), day(10, 12), day(10, 14));
    }

    @Test
    void aWeeklyRuleCanGiveNoDays() {
        assertThat(Occurrences.all(rule(day(10, 6), day(10, 10), WEEKS, 1, MONDAY))).isEmpty();
    }

    @Test
    void monthlyOnThe31stSkipsShortMonths() {
        assertThat(Occurrences.all(rule(day(1, 31), day(12, 31), MONTHS, 1))).containsExactly(
                day(1, 31), day(3, 31), day(5, 31), day(7, 31), day(8, 31), day(10, 31), day(12, 31));
    }

    @Test
    void everyTwoMonths() {
        assertThat(Occurrences.all(rule(day(10, 15), LocalDate.of(2027, 4, 15), MONTHS, 2))).containsExactly(
                day(10, 15), day(12, 15), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 4, 15));
    }

    @Test
    void theRangeAskedForCutsTheSeriesAtBothEnds() {
        assertThat(Occurrences.days(rule(day(10, 1), day(10, 31), DAYS, 1), day(10, 10), day(10, 12)))
                .containsExactly(day(10, 10), day(10, 11), day(10, 12));
        assertThat(Occurrences.days(SELF_STUDY, day(12, 15), LocalDate.of(2027, 1, 31)))
                .containsExactly(day(12, 15), day(12, 16));
    }

    @Test
    void skippedDaysAreTakenOut() {
        Rule skipping = SELF_STUDY.withSkipped(Set.of(day(10, 6)));

        assertThat(Occurrences.all(skipping)).hasSize(32).doesNotContain(day(10, 6));
        assertThat(Occurrences.falls(skipping, day(10, 6))).isTrue(); // still one of its days, only skipped
        assertThat(Occurrences.falls(skipping, day(10, 8))).isFalse(); // a Thursday
    }

    @Test
    void everyDayForAYearIsTheLongestSeries() {
        assertThat(Occurrences.all(rule(day(1, 1), LocalDate.of(2027, 1, 2), DAYS, 1))).hasSize(367);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "once   | 1 | 2026-10-05 | ''    | Once",
        "days   | 1 | 2026-10-05 | ''    | Every day",
        "days   | 3 | 2026-10-05 | ''    | Every 3 days",
        "weeks  | 1 | 2026-10-05 | 1,2,3 | Every week on Mon, Tue, Wed",
        "weeks  | 2 | 2026-10-05 | 6     | Every 2 weeks on Sat",
        "months | 1 | 2026-10-15 | ''    | Every month on the 15th",
        "months | 2 | 2026-10-01 | ''    | Every 2 months on the 1st",
        "months | 1 | 2026-10-22 | ''    | Every month on the 22nd",
        "months | 1 | 2026-10-23 | ''    | Every month on the 23rd",
        "months | 1 | 2026-10-11 | ''    | Every month on the 11th",
        "months | 1 | 2026-10-31 | ''    | Every month on the 31st",
    })
    void eachRuleInWords(String repeat, int every, LocalDate first, String weekdays, String words) {
        Rule rule = new Rule(first, first.plusMonths(3), repeat, every, Occurrences.weekdaysOf(weekdays), Set.of());

        assertThat(Occurrences.describe(rule)).isEqualTo(words);
    }

    @Test
    void weekdaysAreKeptAsIsoNumbers() {
        assertThat(Occurrences.weekdayNumbers(EnumSet.of(WEDNESDAY, MONDAY, TUESDAY))).isEqualTo("1,2,3");
        assertThat(Occurrences.weekdaysOf("1,2,3")).containsExactly(MONDAY, TUESDAY, WEDNESDAY);
        assertThat(Occurrences.weekdaysOf(null)).isEmpty();
        assertThat(Occurrences.weekdaysOf("")).isEmpty();
    }
}
