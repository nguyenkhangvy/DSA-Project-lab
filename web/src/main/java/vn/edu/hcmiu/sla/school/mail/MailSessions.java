package vn.edu.hcmiu.sla.school.mail;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Conflicts.Busy;
import vn.edu.hcmiu.sla.school.mail.Conflicts.Mark;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.schedule.Schedule;
import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

/**
 * The sessions shown under an event's row and on its Join page: the ones the laptop found and the ones the student
 * joined, each marked Conflict / No conflict against the timetable
 * (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, sections 4.2, 4.5 and 4.6).
 */
@Service
public class MailSessions {

    static final Duration EXAM_WITHOUT_LENGTH = Duration.ofMinutes(90);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** One session of a card: found in its email and/or joined, with its mark. */
    public record Line(Session session, boolean found, boolean joined, Mark mark) {

        /** "Tue 29/09 13:00–14:00", or "Thu 01/10 from 14:00" without an end. */
        public String when() {
            String start = CLOCK.format(session.start());
            return VietnamTime.dayLabel(session.day()) + " "
                    + (session.end() != null ? start + "–" + CLOCK.format(session.end()) : "from " + start);
        }

        /** How the Join form names it: "2026-09-29T13:30". */
        public String id() {
            return session.day() + "T" + CLOCK.format(session.start());
        }
    }

    private final Schedule schedule;
    private final SchoolMailJoinedRepository joined;

    public MailSessions(Schedule schedule, SchoolMailJoinedRepository joined) {
        this.schedule = schedule;
        this.joined = joined;
    }

    static Session session(SchoolMailJoined row) {
        return new Session(row.getDay(), row.getStart(), row.getEnd());
    }

    /** The student's joined sessions of one card's emails, in time order. */
    public List<SchoolMailJoined> joinedOf(Integer userId, Collection<String> keys) {
        return joined.findByUserIdAndMailKeyInOrderByDayAscStartAsc(userId, keys);
    }

    /** The student's joined sessions of one card's emails that haven't ended at `now` (Vietnam time). */
    public List<SchoolMailJoined> upcomingJoined(Integer userId, Collection<String> keys, LocalDateTime now) {
        return joinedOf(userId, keys).stream().filter(row -> session(row).endAt().isAfter(now)).toList();
    }

    /**
     * Puts `rows` in place of the student's joined sessions of one card's emails that haven't ended at `now`, in one
     * transaction: a Save happens whole or not at all. The old rows go in one statement by id, so rows another Save
     * already removed are no error.
     */
    @Transactional
    public void replaceUpcoming(Integer userId, Collection<String> keys, LocalDateTime now,
            List<SchoolMailJoined> rows) {
        joined.deleteAllByIdInBatch(upcomingJoined(userId, keys, now).stream().map(SchoolMailJoined::getId).toList());
        joined.saveAll(rows);
    }

    /** Removes the student's joined sessions of one card's emails that haven't ended at `now`, in one transaction. */
    @Transactional
    public void leave(Integer userId, Collection<String> keys, LocalDateTime now) {
        joined.deleteAllByIdInBatch(upcomingJoined(userId, keys, now).stream().map(SchoolMailJoined::getId).toList());
    }

    /**
     * A joined event whose email is no longer in the Mailbox: its title, and its joined sessions that haven't
     * ended, so the student can still leave it.
     */
    public record Gone(String key, String title, boolean trainingPoints, List<Line> lines) {
    }

    /**
     * The student's joined events whose emails are none of `mailKeys` (the Mailbox's) and that have a session that
     * hasn't ended at `now` (Vietnam time), soonest first.
     */
    public List<Gone> gone(Integer userId, Set<String> mailKeys, LocalDateTime now) {
        Map<String, List<SchoolMailJoined>> byKey = new LinkedHashMap<>();
        // From yesterday: a session without an end that starts late in the evening ends after midnight.
        for (SchoolMailJoined row : joined.findByUserIdAndDayGreaterThanEqualOrderByDayAscStartAsc(userId,
                now.toLocalDate().minusDays(1))) {
            if (!mailKeys.contains(row.getMailKey()) && session(row).endAt().isAfter(now)) {
                byKey.computeIfAbsent(row.getMailKey(), key -> new ArrayList<>()).add(row);
            }
        }
        return byKey.entrySet().stream().map(e -> new Gone(e.getKey(), e.getValue().get(0).getTitle(),
                e.getValue().get(0).isTrainingPoints(),
                e.getValue().stream().map(row -> new Line(session(row), false, true, null)).toList())).toList();
    }

    /** Each joinable card's sessions (Card.joinable) that haven't ended at `now` (Vietnam time), by card key. */
    @Transactional(readOnly = true)
    public Map<String, List<Line>> lines(Integer userId, List<Card> cards, LocalDateTime now) {
        Map<String, Map<List<Object>, Line>> byCard = new LinkedHashMap<>();
        LocalDate first = null;
        LocalDate last = null;
        for (Card card : cards) {
            if (!card.joinable()) {
                continue;
            }
            Map<List<Object>, Line> lines = new LinkedHashMap<>();
            for (Session s : card.sessions()) {
                lines.put(List.of(s.day(), s.start()), new Line(s, true, false, null));
            }
            for (SchoolMailJoined row : joinedOf(userId, card.keys())) {
                Session s = session(row);
                if (s.endAt().isAfter(now)) {
                    Line found = lines.get(List.of(s.day(), s.start()));
                    lines.put(List.of(s.day(), s.start()),
                            new Line(found != null ? found.session() : s, found != null, true, null));
                }
            }
            byCard.put(card.key(), lines);
            for (List<Object> key : lines.keySet()) {
                LocalDate day = (LocalDate) key.get(0);
                first = first == null || day.isBefore(first) ? day : first;
                last = last == null || day.isAfter(last) ? day : last;
            }
        }
        Map<String, List<Line>> result = new LinkedHashMap<>();
        if (first == null) {
            return result;
        }
        List<Busy> timetable = timetable(userId, first, last);
        List<SchoolMailJoined> allJoined = joined.findByUserIdAndDayBetweenOrderByDayAscStartAsc(userId, first, last);
        for (Card card : cards) {
            Map<List<Object>, Line> lines = byCard.get(card.key());
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<Busy> busy = new ArrayList<>(timetable);
            for (SchoolMailJoined row : allJoined) {
                if (!card.keys().contains(row.getMailKey())) {
                    Session s = session(row);
                    busy.add(new Busy(row.getTitle(), s.startAt(), s.endAt()));
                }
            }
            result.put(card.key(), lines.values().stream()
                    .sorted((a, b) -> Session.ORDER.compare(a.session(), b.session()))
                    .map(l -> new Line(l.session(), l.found(), l.joined(), Conflicts.of(l.session(), busy)))
                    .toList());
        }
        return result;
    }

    /** One card's lines, as {@link #lines(Integer, List, LocalDateTime)} gives them. */
    public List<Line> lines(Integer userId, Card card, LocalDateTime now) {
        return lines(userId, List.of(card), now).getOrDefault(card.key(), List.of());
    }

    /**
     * Classes and exams on the Vietnam days [first, last], as the Timetable shows them: cancelled classes, classes
     * without a time and joined events (counted apart, without the card's own) are left out.
     */
    private List<Busy> timetable(Integer userId, LocalDate first, LocalDate last) {
        List<Busy> busy = new ArrayList<>();
        List<Item> items = schedule.itemsBetween(userId, VietnamTime.dayStart(first),
                VietnamTime.dayStart(last.plusDays(1)));
        for (Item item : items) {
            if (item.allDay() || "cancelled".equals(item.change()) || "event".equals(item.kind())) {
                continue;
            }
            LocalDateTime start = VietnamTime.of(item.startAt()).toLocalDateTime();
            LocalDateTime end = item.endAt() != null ? VietnamTime.of(item.endAt()).toLocalDateTime()
                    : start.plus(EXAM_WITHOUT_LENGTH);
            busy.add(new Busy(item.label() != null ? item.label() + ": " + item.title() : item.title(), start, end));
        }
        return busy;
    }
}
