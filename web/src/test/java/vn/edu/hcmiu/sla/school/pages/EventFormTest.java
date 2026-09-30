package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.edu.hcmiu.sla.school.events.Details;
import vn.edu.hcmiu.sla.school.events.Occurrences;

class EventFormTest {

    /** The student's example, as typed. */
    static EventForm selfStudy() {
        EventForm form = new EventForm();
        form.setTitle("  Tự học buổi tối ");
        form.setStart("17:00");
        form.setEnd("19:00");
        form.setRepeat("weeks");
        form.setEvery("1");
        form.setWeekdays(List.of("1", "2", "3"));
        form.setFirstDay("2026-10-05");
        form.setLastDay("2026-12-20");
        return form;
    }

    @Test
    void theStudentsExampleIsFineAndBecomesItsDetails() {
        EventForm form = selfStudy();

        assertThat(form.check()).isEmpty();
        Details details = form.details();
        assertThat(List.of(details.title(), details.start(), details.end()))
                .containsExactly("Tự học buổi tối", LocalTime.of(17, 0), LocalTime.of(19, 0));
        assertThat(details.place()).isNull();
        assertThat(details.rule().weekdays()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        assertThat(Occurrences.all(details.rule())).hasSize(33);
    }

    @Test
    void onceIgnoresTheLastDayTheNumberAndTheWeekdays() {
        EventForm form = selfStudy();
        form.setRepeat("once");
        form.setEvery("0");
        form.setWeekdays(List.of());
        form.setLastDay("");

        assertThat(form.check()).isEmpty();
        assertThat(form.details().rule()).isEqualTo(new Occurrences.Rule(LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 5), "once", 1, java.util.Set.of(), java.util.Set.of()));
    }

    @Test
    void aNewFormStartsTodayOnceWithTodaysWeekdayTicked() {
        EventForm form = EventForm.fresh(LocalDate.of(2026, 10, 1)); // a Thursday

        assertThat(List.of(form.getFirstDay(), form.getLastDay(), form.getRepeat(), form.getEvery()))
                .containsExactly("2026-10-01", "2026-10-01", "once", "1");
        assertThat(form.getWeekdays()).containsExactly("4");
    }

    static Stream<Arguments> mistakes() {
        return Stream.of(
                Arguments.of("title", "Enter a title.", (Consumer<EventForm>) f -> f.setTitle("  ")),
                Arguments.of("title", "At most 100 characters.", (Consumer<EventForm>) f -> f.setTitle("x".repeat(101))),
                Arguments.of("place", "At most 100 characters.", (Consumer<EventForm>) f -> f.setPlace("x".repeat(101))),
                Arguments.of("notes", "At most 500 characters.", (Consumer<EventForm>) f -> f.setNotes("x".repeat(501))),
                Arguments.of("start", "Enter a time.", (Consumer<EventForm>) f -> f.setStart("")),
                Arguments.of("end", "Enter a time.", (Consumer<EventForm>) f -> f.setEnd("7pm")),
                Arguments.of("end", "The end must be after the start.", (Consumer<EventForm>) f -> f.setEnd("17:00")),
                Arguments.of("start", "The Timetable shows 07:00–23:00: start at 07:00 or later.",
                        (Consumer<EventForm>) f -> f.setStart("06:30")),
                Arguments.of("end", "The Timetable shows 07:00–23:00: end by 23:00.",
                        (Consumer<EventForm>) f -> f.setEnd("23:30")),
                Arguments.of("repeat", "Choose how it repeats.", (Consumer<EventForm>) f -> f.setRepeat("yearly")),
                Arguments.of("every", "Enter a number from 1 to 99.", (Consumer<EventForm>) f -> f.setEvery("0")),
                Arguments.of("every", "Enter a number from 1 to 99.", (Consumer<EventForm>) f -> f.setEvery("100")),
                Arguments.of("every", "Enter a number from 1 to 99.", (Consumer<EventForm>) f -> f.setEvery("two")),
                Arguments.of("weekdays", "Tick at least one day.", (Consumer<EventForm>) f -> f.setWeekdays(List.of())),
                Arguments.of("firstDay", "Enter a day.", (Consumer<EventForm>) f -> f.setFirstDay("")),
                Arguments.of("lastDay", "Enter a day.", (Consumer<EventForm>) f -> f.setLastDay("20/12/2026")),
                Arguments.of("lastDay", "The last day can't be before the first day.",
                        (Consumer<EventForm>) f -> f.setLastDay("2026-10-04")),
                Arguments.of("lastDay", "The last day can be at most a year after the first day.",
                        (Consumer<EventForm>) f -> f.setLastDay("2027-10-07")),
                Arguments.of("days", "These settings give no days.", (Consumer<EventForm>) f -> {
                    f.setWeekdays(List.of("1"));
                    f.setFirstDay("2026-10-06");
                    f.setLastDay("2026-10-10");
                }));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mistakes")
    void eachMistakeIsNamedUnderItsField(String field, String message, Consumer<EventForm> change) {
        EventForm form = selfStudy();
        change.accept(form);

        Map<String, String> errors = form.check();

        assertThat(errors).containsEntry(field, message);
    }

    @Test
    void aYearIsAllowed() {
        EventForm form = selfStudy();
        form.setRepeat("days");
        form.setLastDay("2027-10-06"); // 366 days after 05/10/2026

        assertThat(form.check()).isEmpty();
    }
}
