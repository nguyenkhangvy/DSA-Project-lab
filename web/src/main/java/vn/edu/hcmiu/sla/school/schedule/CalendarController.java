package vn.edu.hcmiu.sla.school.schedule;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.events.MyEventConflicts;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * Events for the Timetable page's calendar (FullCalendar). Times are Vietnam wall-clock times without an
 * offset, so the calendar shows Vietnam time on any device.
 */
@RestController
public class CalendarController {

    static final int MAX_CALENDAR_DAYS = 62; // a month view asks for about 6 weeks
    static final Map<String, String> CHANGE_TITLES = Map.of(
            "online", "Online", "makeup", "Make-up", "cancelled", "Cancelled");
    static final Set<String> DONE = Set.of("needs_grading", "graded", "exempt");

    private final Schedule schedule;

    public CalendarController(Schedule schedule) {
        this.schedule = schedule;
    }

    /** start and end are Vietnam dates (end exclusive); FullCalendar sends "2026-09-28T00:00:00Z". */
    @GetMapping("/school/api/calendar")
    ResponseEntity<Object> feed(@AuthenticationPrincipal AppUser user,
            @RequestParam(required = false) String start, @RequestParam(required = false) String end) {
        LocalDate from = day(start);
        LocalDate to = day(end);
        if (from == null || to == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "start and end dates are required"));
        }
        if (!from.isBefore(to) || ChronoUnit.DAYS.between(from, to) > MAX_CALENDAR_DAYS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "the range must be 1 to " + MAX_CALENDAR_DAYS + " days"));
        }

        List<Map<String, Object>> events = new ArrayList<>();
        List<Item> items = schedule.itemsBetween(user.id(), VietnamTime.dayStart(from), VietnamTime.dayStart(to));
        Set<Item> clashing = Collections.newSetFromMap(new IdentityHashMap<>());
        MyEventConflicts.clashes(items).forEach(clash -> clashing.add(clash.occurrence()));
        for (Item item : items) {
            events.add(event(item, clashing.contains(item)));
        }
        for (SchoolBbAssignment deadline : schedule.deadlinesBetween(user.id(), VietnamTime.dayStart(from),
                VietnamTime.dayStart(to))) {
            events.add(deadline(deadline));
        }
        return ResponseEntity.ok(events);
    }

    /** The date at the start of "2026-09-28T00:00:00Z" or "2026-09-28", or null. */
    private static LocalDate day(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    static Map<String, Object> event(Item item) {
        return event(item, false);
    }

    /** clash: an own event's day that overlaps something else, shown with ⚠ and a red border. */
    static Map<String, Object> event(Item item, boolean clash) {
        String title = item.label() != null ? item.label() + ": " + item.title() : item.title();
        String css = "event-" + item.kind();
        if (item.change() != null) {
            title = CHANGE_TITLES.get(item.change()) + ": " + title;
            css = item.change().equals("cancelled") ? "event-cancelled" : "event-changed";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", item.kind());
        props.put("code", item.code());
        props.put("room", item.room());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", item.allDay() ? "Make-up class: " + item.title() + " (" + noTime(item) + ")" : title);
        event.put("start", VietnamTime.wallClock(item.startAt()));
        event.put("classNames", List.of(css));
        event.put("extendedProps", props);
        if (clash) {
            event.put("title", "⚠ " + event.get("title"));
            event.put("classNames", List.of(css, "event-conflict"));
        }
        if (item.allDay()) {
            event.put("start", VietnamTime.date(item.startAt()).toString());
            event.put("allDay", true);
        } else if (item.endAt() != null) {
            event.put("end", VietnamTime.wallClock(item.endAt()));
        }
        if (item.change() != null) {
            props.put("change", item.change());
        }
        if (item.source() != null) {
            event.put("url", item.source().link());
        }
        return event;
    }

    /** Why a make-up class is all day, and where to look: "time not given, see email" (or "see announcement"). */
    private static String noTime(Item item) {
        return item.source() == null ? "time not given"
                : "time not given, " + item.source().text().toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> deadline(SchoolBbAssignment deadline) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "due");
        props.put("code", deadline.getCourse().getCourseCode());
        props.put("room", null);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", (DONE.contains(deadline.getStatus()) ? "✓ " : "") + "Due "
                + VietnamTime.clock(deadline.getDueAt()) + ": " + deadline.getName() + " · "
                + deadline.getCourse().getName());
        event.put("start", VietnamTime.date(deadline.getDueAt()).toString());
        event.put("allDay", true);
        event.put("classNames", List.of("event-due"));
        event.put("extendedProps", props);
        return event;
    }
}
