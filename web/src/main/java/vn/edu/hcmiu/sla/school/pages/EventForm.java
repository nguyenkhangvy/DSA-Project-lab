package vn.edu.hcmiu.sla.school.pages;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vn.edu.hcmiu.sla.school.events.Details;
import vn.edu.hcmiu.sla.school.events.Occurrences;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;

/**
 * The event form (docs/superpowers/specs/2026-09-30-my-events-design.md, 4.1): the text as typed, checked by
 * {@link #check()}. weekdays are ISO day numbers "1" (Mon) to "7" (Sun); days are "2026-10-05" and times "17:00".
 */
public class EventForm {

    static final int MAX_TITLE = 100;
    static final int MAX_PLACE = 100;
    static final int MAX_NOTES = 500;
    static final int MAX_EVERY = 99;

    private String title = "";
    private String place = "";
    private String notes = "";
    private String start = "";
    private String end = "";
    private String repeat = Occurrences.ONCE;
    private String every = "1";
    private List<String> weekdays = new ArrayList<>();
    private String firstDay = "";
    private String lastDay = "";

    public EventForm() {
    }

    /** A new event: once, today, with today's weekday ticked in case it becomes weekly. */
    static EventForm fresh(LocalDate today) {
        EventForm form = new EventForm();
        form.firstDay = today.toString();
        form.lastDay = today.toString();
        form.weekdays = new ArrayList<>(List.of(String.valueOf(today.getDayOfWeek().getValue())));
        return form;
    }

    /** The form for an event that exists. */
    static EventForm of(SchoolMyEvent event) {
        EventForm form = new EventForm();
        form.title = event.getTitle();
        form.place = event.getPlace() == null ? "" : event.getPlace();
        form.notes = event.getNotes() == null ? "" : event.getNotes();
        form.start = event.getStartTime().toString();
        form.end = event.getEndTime().toString();
        form.repeat = event.getRepeatKind();
        form.every = String.valueOf(event.getEveryN());
        form.weekdays = new ArrayList<>(Occurrences.weekdaysOf(event.getWeekdays()).stream()
                .map(d -> String.valueOf(d.getValue())).toList());
        form.firstDay = event.getFirstDay().toString();
        form.lastDay = event.getLastDay().toString();
        return form;
    }

    /** The mistakes, by field; empty when the event can be saved. */
    public Map<String, String> check() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (title.isEmpty()) {
            errors.put("title", "Enter a title.");
        } else if (length(title) > MAX_TITLE) {
            errors.put("title", "At most 100 characters.");
        }
        if (length(place) > MAX_PLACE) {
            errors.put("place", "At most 100 characters.");
        }
        if (length(notes) > MAX_NOTES) {
            errors.put("notes", "At most 500 characters.");
        }
        LocalTime startTime = time(start);
        LocalTime endTime = time(end);
        if (startTime == null) {
            errors.put("start", "Enter a time.");
        }
        if (endTime == null) {
            errors.put("end", "Enter a time.");
        } else if (startTime != null && !endTime.isAfter(startTime)) {
            errors.put("end", "The end must be after the start.");
        }
        boolean once = Occurrences.ONCE.equals(repeat);
        if (!Occurrences.KINDS.contains(repeat)) {
            errors.put("repeat", "Choose how it repeats.");
        }
        Integer n = number(every);
        if (!once && (n == null || n < 1 || n > MAX_EVERY)) {
            errors.put("every", "Enter a number from 1 to 99.");
        }
        if (Occurrences.WEEKS.equals(repeat) && weekdaySet().isEmpty()) {
            errors.put("weekdays", "Tick at least one day.");
        }
        LocalDate first = date(firstDay);
        LocalDate last = once ? first : date(lastDay);
        if (first == null) {
            errors.put("firstDay", "Enter a day.");
        }
        if (!once && last == null) {
            errors.put("lastDay", "Enter a day.");
        } else if (first != null && last != null) {
            if (last.isBefore(first)) {
                errors.put("lastDay", "The last day can't be before the first day.");
            } else if (ChronoUnit.DAYS.between(first, last) > Occurrences.MAX_SPAN_DAYS) {
                errors.put("lastDay", "The last day can be at most a year after the first day.");
            }
        }
        if (errors.isEmpty() && Occurrences.all(rule()).isEmpty()) {
            errors.put("days", "These settings give no days.");
        }
        return errors;
    }

    /** What the form sets on an event; only after {@link #check()} found nothing. */
    public Details details() {
        return new Details(title, place.isEmpty() ? null : place, notes.isEmpty() ? null : notes, rule(), time(start),
                time(end));
    }

    private Occurrences.Rule rule() {
        LocalDate first = date(firstDay);
        return switch (repeat) {
            case Occurrences.ONCE -> new Occurrences.Rule(first, first, repeat, 1, Set.of(), Set.of());
            case Occurrences.WEEKS -> new Occurrences.Rule(first, date(lastDay), repeat, number(every), weekdaySet(),
                    Set.of());
            default -> new Occurrences.Rule(first, date(lastDay), repeat, number(every), Set.of(), Set.of());
        };
    }

    private Set<DayOfWeek> weekdaySet() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String value : weekdays) {
            Integer n = number(value);
            if (n != null && n >= 1 && n <= 7) {
                days.add(DayOfWeek.of(n));
            }
        }
        return days;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static LocalTime time(String text) {
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static Integer number(String text) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = clean(title);
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = clean(place);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = clean(notes);
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = clean(start);
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = clean(end);
    }

    public String getRepeat() {
        return repeat;
    }

    public void setRepeat(String repeat) {
        this.repeat = clean(repeat);
    }

    public String getEvery() {
        return every;
    }

    public void setEvery(String every) {
        this.every = clean(every);
    }

    public List<String> getWeekdays() {
        return weekdays;
    }

    public void setWeekdays(List<String> weekdays) {
        this.weekdays = weekdays == null ? new ArrayList<>() : new ArrayList<>(weekdays);
    }

    public String getFirstDay() {
        return firstDay;
    }

    public void setFirstDay(String firstDay) {
        this.firstDay = clean(firstDay);
    }

    public String getLastDay() {
        return lastDay;
    }

    public void setLastDay(String lastDay) {
        this.lastDay = clean(lastDay);
    }
}
