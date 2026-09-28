package vn.edu.hcmiu.sla.school.mail;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;

/**
 * Whether an event's session clashes with the timetable (docs/superpowers/specs/2026-09-28-mailbox-events-design.md,
 * section 4.5). Pure functions; times are Vietnam wall-clock times.
 */
public final class Conflicts {

    private Conflicts() {
    }

    /** Something on the timetable: a class, an exam or another joined event, by the name its mark shows. */
    public record Busy(String name, LocalDateTime start, LocalDateTime end) {
    }

    /** A session's mark: the names of what it clashes with, in time order. None is "No conflict". */
    public record Mark(List<String> with) {

        public boolean conflict() {
            return !with.isEmpty();
        }

        /** "✓ No conflict", "⚠ Conflict: Web Application" or "⚠ Conflict: Web Application + 1". */
        public String text() {
            if (with.isEmpty()) {
                return "✓ No conflict";
            }
            return "⚠ Conflict: " + with.get(0) + (with.size() > 1 ? " + " + (with.size() - 1) : "");
        }
    }

    /** A session clashes with what its time overlaps; back-to-back (one ends as the other starts) doesn't. */
    public static Mark of(Session session, List<Busy> busy) {
        return new Mark(busy.stream()
                .filter(b -> session.startAt().isBefore(b.end()) && b.start().isBefore(session.endAt()))
                .sorted(Comparator.comparing(Busy::start))
                .map(Busy::name)
                .toList());
    }
}
