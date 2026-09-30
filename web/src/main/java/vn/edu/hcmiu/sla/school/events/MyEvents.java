package vn.edu.hcmiu.sla.school.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.events.MyEventConflicts.Clash;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;
import vn.edu.hcmiu.sla.school.model.SchoolMyEventRepository;
import vn.edu.hcmiu.sla.school.schedule.Schedule;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * The student's own events for the pages (docs/superpowers/specs/2026-09-30-my-events-design.md, 4 and 5): saving,
 * checking a rule against the timetable, and the flash after Save.
 */
@Service
public class MyEvents {

    static final int CHECKED = -1; // the eventId of the days being checked, which aren't saved yet
    static final int FLASH_DAYS = 5; // clashing days named in the flash after Save

    /** A rule's days (total, skips taken out) and the ones that clash. */
    public record Checked(int total, List<Clash> clashes) {

        public boolean conflict() {
            return !clashes.isEmpty();
        }

        /** "✓ No conflict in 33 sessions", "⚠ 3 of 33 sessions clash", or "No days left". */
        public String summary() {
            if (total == 0) {
                return "No days left";
            }
            String sessions = total == 1 ? "session" : "sessions";
            return clashes.isEmpty() ? "✓ No conflict in " + total + " " + sessions
                    : "⚠ " + clashes.size() + " of " + total + " " + sessions + " clash";
        }

        /** "Mon 05/10 17:00–19:00 ⚠ IT093IU Web Application Development (17:15–19:45)", one per clashing day. */
        public List<String> lines() {
            return clashes.stream().map(clash -> VietnamTime.dayLabel(VietnamTime.date(clash.occurrence().startAt()))
                    + " " + VietnamTime.clock(clash.occurrence().startAt()) + "–"
                    + VietnamTime.clock(clash.occurrence().endAt()) + " ⚠ "
                    + clash.with().stream().map(MyEventConflicts::name).collect(Collectors.joining(" · "))).toList();
        }

        /** "Mon 05/10", one per clashing day. */
        public List<String> days() {
            return clashes.stream().map(clash -> VietnamTime.dayLabel(VietnamTime.date(clash.occurrence().startAt())))
                    .toList();
        }
    }

    private final SchoolMyEventRepository events;
    private final Schedule schedule;

    public MyEvents(SchoolMyEventRepository events, Schedule schedule) {
        this.events = events;
        this.schedule = schedule;
    }

    /** The user's event with its skipped days, or empty (someone else's or unknown). */
    @Transactional(readOnly = true)
    public Optional<SchoolMyEvent> find(Integer userId, Integer id) {
        return events.findOfUser(id, userId);
    }

    /**
     * These details' days against the timetable. For an existing event (eventId not null), its saved days are left
     * out and its skipped days that are still days of the new rule are kept.
     */
    @Transactional(readOnly = true)
    public Checked check(Integer userId, Integer eventId, Details details) {
        Occurrences.Rule rule = details.rule();
        if (eventId != null) {
            Occurrences.Rule newRule = rule;
            rule = rule.withSkipped(events.findOfUser(eventId, userId).map(e -> e.rule().skipped()).orElse(java.util.Set.of())
                    .stream().filter(day -> Occurrences.falls(newRule, day)).collect(Collectors.toSet()));
        }
        List<LocalDate> days = Occurrences.all(rule);
        List<Item> items = new ArrayList<>(schedule.itemsBetween(userId, VietnamTime.dayStart(rule.first()),
                VietnamTime.dayStart(rule.last().plusDays(1))));
        items.removeIf(item -> eventId != null && eventId.equals(item.eventId()));
        for (LocalDate day : days) {
            items.add(new Item(Schedule.MINE, VietnamTime.utc(day, details.start()), VietnamTime.utc(day, details.end()),
                    null, details.title(), details.place(), Schedule.MY_EVENT, null, null, false, CHECKED));
        }
        List<Clash> clashes = MyEventConflicts.clashes(items).stream()
                .filter(clash -> Integer.valueOf(CHECKED).equals(clash.occurrence().eventId())).toList();
        return new Checked(days.size(), clashes);
    }

    @Transactional
    public SchoolMyEvent create(Integer userId, Details details, LocalDateTime now) {
        SchoolMyEvent event = new SchoolMyEvent(userId, now);
        event.set(details, now);
        return events.save(event);
    }

    @Transactional
    public Optional<SchoolMyEvent> update(Integer userId, Integer id, Details details, LocalDateTime now) {
        Optional<SchoolMyEvent> event = events.findOfUser(id, userId);
        event.ifPresent(e -> e.set(details, now));
        return event;
    }

    @Transactional
    public boolean delete(Integer userId, Integer id) {
        Optional<SchoolMyEvent> event = events.findOfUser(id, userId);
        event.ifPresent(events::delete);
        return event.isPresent();
    }

    /** Skips a day of the user's event; false when the event is someone else's or unknown. */
    @Transactional
    public boolean skip(Integer userId, Integer id, LocalDate day) {
        Optional<SchoolMyEvent> event = events.findOfUser(id, userId);
        event.ifPresent(e -> e.skip(day));
        return event.isPresent();
    }

    /** Brings a skipped day back; false when the event is someone else's or unknown. */
    @Transactional
    public boolean unskip(Integer userId, Integer id, LocalDate day) {
        Optional<SchoolMyEvent> event = events.findOfUser(id, userId);
        event.ifPresent(e -> e.unskip(day));
        return event.isPresent();
    }

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    /** A line of the Timetable's "My events" list. */
    public record Line(SchoolMyEvent event, String repeat, String days, String time, Checked checked) {

        /** "⚠ 3 clashes", "⚠ 1 clash", "✓ No conflict" or "No days left". */
        public String mark() {
            if (checked.total() == 0) {
                return "No days left";
            }
            int n = checked.clashes().size();
            return n == 0 ? "✓ No conflict" : "⚠ " + n + (n == 1 ? " clash" : " clashes");
        }
    }

    /** The user's events, soonest first day first, each with its clashing days over the whole series. */
    @Transactional(readOnly = true)
    public List<Line> lines(Integer userId) {
        List<SchoolMyEvent> all = events.findAllOfUser(userId);
        if (all.isEmpty()) {
            return List.of();
        }
        LocalDate from = all.stream().map(SchoolMyEvent::getFirstDay).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = all.stream().map(SchoolMyEvent::getLastDay).max(LocalDate::compareTo).orElseThrow();
        Map<Integer, List<Clash>> clashes = MyEventConflicts.clashes(schedule.itemsBetween(userId,
                VietnamTime.dayStart(from), VietnamTime.dayStart(to.plusDays(1)))).stream()
                .collect(Collectors.groupingBy(clash -> clash.occurrence().eventId()));
        return all.stream().map(event -> new Line(event, Occurrences.describe(event.rule()),
                event.getFirstDay().equals(event.getLastDay()) ? DAY_MONTH.format(event.getFirstDay())
                        : DAY_MONTH.format(event.getFirstDay()) + "–" + DAY_MONTH.format(event.getLastDay()),
                event.getStartTime() + "–" + event.getEndTime(),
                new Checked(Occurrences.all(event.rule()).size(), clashes.getOrDefault(event.getId(), List.of()))))
                .toList();
    }

    /** 'Saved "Tự học". 3 of 33 sessions clash: Mon 05/10, Tue 06/10, Wed 07/10.' or 'Saved "Tự học". No conflict.' */
    public static String savedFlash(String title, Checked checked) {
        String saved = "Saved \"" + title + "\". ";
        if (!checked.conflict()) {
            return saved + "No conflict.";
        }
        List<String> days = checked.days();
        String listed = String.join(", ", days.subList(0, Math.min(FLASH_DAYS, days.size())))
                + (days.size() > FLASH_DAYS ? ", …" : "");
        return saved + days.size() + " of " + checked.total() + " sessions clash: " + listed + ".";
    }
}
