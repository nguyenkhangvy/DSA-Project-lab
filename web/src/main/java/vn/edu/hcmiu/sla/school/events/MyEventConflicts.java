package vn.edu.hcmiu.sla.school.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.schedule.Schedule;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * Which days of the student's own events clash with the rest of the timetable
 * (docs/superpowers/specs/2026-09-30-my-events-design.md, 5). Pure functions over {@link Schedule} items.
 */
public final class MyEventConflicts {

    private MyEventConflicts() {
    }

    static final Duration EXAM_WITHOUT_LENGTH = Duration.ofMinutes(90); // as Mailbox's conflict marks

    /** One day of an own event and what it overlaps, in time order. */
    public record Clash(Item occurrence, List<Item> with) {
    }

    /**
     * Every own-event item in items that overlaps something else in items: a class (not cancelled, with a time), an
     * exam, a joined event, or another own event's day. One ending as the other starts is not a clash, and an event
     * never clashes with its own days.
     */
    public static List<Clash> clashes(List<Item> items) {
        List<Item> busy = items.stream().filter(item -> !item.allDay() && !"cancelled".equals(item.change())).toList();
        List<Clash> clashes = new ArrayList<>();
        for (Item item : items) {
            if (!Schedule.MINE.equals(item.kind())) {
                continue;
            }
            List<Item> with = busy.stream()
                    .filter(other -> other != item && !Objects.equals(other.eventId(), item.eventId()))
                    .filter(other -> item.startAt().isBefore(end(other)) && other.startAt().isBefore(end(item)))
                    .sorted(Comparator.comparing(Item::startAt))
                    .toList();
            if (!with.isEmpty()) {
                clashes.add(new Clash(item, with));
            }
        }
        return clashes;
    }

    static LocalDateTime end(Item item) {
        return item.endAt() != null ? item.endAt() : item.startAt().plus(EXAM_WITHOUT_LENGTH);
    }

    /** "IT093IU Web Application Development (17:15–19:45)", "Final exam: … (16:00–17:30)", "My event: … (…)". */
    public static String name(Item item) {
        String what = "class".equals(item.kind()) ? item.code() + " " + item.title()
                : item.label() != null ? item.label() + ": " + item.title() : item.title();
        return what + " (" + VietnamTime.clock(item.startAt()) + "–" + VietnamTime.clock(end(item)) + ")";
    }
}
