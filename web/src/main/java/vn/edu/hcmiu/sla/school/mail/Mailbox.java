package vn.edu.hcmiu.sla.school.mail;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;

/**
 * What the Mailbox tab shows: emails grouped into cards, cards in boxes, in the order of
 * docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.3, with the sessions, Past and opening
 * rules of 2026-09-28-mailbox-events-design.md, section 4. Pure functions.
 */
public final class Mailbox {

    private Mailbox() {
    }

    /** The categories in the order they are shown, with their names. */
    public static final Map<String, String> CATEGORIES = orderedNames(
            "class", "Class", "school_task", "School task", "money", "Money", "event", "Event",
            "training_points", "Training points", "requests_account", "Your requests & account",
            "system_notice", "System notice", "promotion", "Promotion");
    /** The short names on a card's labels. */
    public static final Map<String, String> LABELS = orderedNames(
            "class", "Class", "school_task", "Task", "money", "Money", "event", "Event",
            "training_points", "★ Points", "requests_account", "Requests", "system_notice", "System",
            "promotion", "Promo");
    static final Duration SAME_EMAIL_WINDOW = Duration.ofDays(30);
    /** How long a session without an end lasts. */
    public static final Duration SESSION_WITHOUT_END = Duration.ofHours(1);

    private static Map<String, String> orderedNames(String... pairs) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            names.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(names);
    }

    /** One time an event takes place, as the laptop found it. Vietnam time; end may be empty. */
    public record Session(LocalDate day, LocalTime start, LocalTime end) {

        static final Comparator<Session> ORDER = Comparator.comparing(Session::day).thenComparing(Session::start);

        public LocalDateTime startAt() {
            return day.atTime(start);
        }

        /** The end, or an hour after the start when the email gave none. */
        public LocalDateTime endAt() {
            return end != null ? day.atTime(end) : startAt().plus(SESSION_WITHOUT_END);
        }
    }

    /**
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. sessions: an event or school task's sessions that
     * haven't ended (none for other cards). nextDate: the day of the first of them, or else the earliest date
     * from today on. over: every session has ended, or (without sessions) every date is over. past: it is over, or
     * (an event) its registration closed and the student joined none of its sessions. suggested: the student
     * moved it to Event or School task and the rules gave it neither, so its sessions are only suggestions.
     * registerBy: the latest registration deadline of its emails, or null; closed: that day is before today.
     * joined: the student joined a session of one of its emails.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, List<Session> sessions, LocalDate nextDate, boolean over, boolean past,
            boolean sorted,
            boolean opened, boolean done, int messages, int copies, boolean suggested, LocalDate registerBy,
            boolean closed, boolean joined) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
        }

        /** An event or school task: it has sessions and Join…, and stays in its box when opened while ahead. */
        public boolean eventLike() {
            return Mailbox.eventLike(categories);
        }

        /** Join… and Leave are open to it: an event or school task, or a card moved away after joining. */
        public boolean joinable() {
            return eventLike() || joined;
        }

        /**
         * With auto-Done on, opening it marks it Done: anything but an event or school task still ahead, or one in
         * Past only because its registration closed (Past and Done stay apart).
         */
        public boolean doneWhenOpened() {
            return !(eventLike() && (nextDate != null || (past && !over)));
        }

        LocalDate lastDate() {
            return dates.isEmpty() ? null : dates.get(dates.size() - 1);
        }
    }

    /** A box. cards: shown, all of them; past: in its closed "Past" list (Events and School tasks only). */
    public record Box(String id, String title, List<Card> cards, List<Card> past, String pastTitle) {

        public boolean empty() {
            return cards.isEmpty() && past.isEmpty();
        }
    }

    /** The boxes in order, and the closed "Done" list. */
    public record View(List<Box> boxes, List<Card> done) {

        public Card card(String key) {
            return boxes.stream().flatMap(b -> Stream.of(b.cards(), b.past()))
                    .flatMap(List::stream).filter(c -> c.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> done.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null));
        }

        /** Every email's key, of every card. */
        public Set<String> keys() {
            return Stream.concat(boxes.stream().flatMap(b -> Stream.of(b.cards(), b.past())), Stream.of(done))
                    .flatMap(List::stream).flatMap(c -> c.keys().stream()).collect(Collectors.toSet());
        }
    }

    static boolean eventLike(List<String> categories) {
        return categories.contains("event") || categories.contains("school_task");
    }

    /** Lower case, letters and digits only, one space between words: how "the same subject" is compared. */
    static String sameSubject(String subject) {
        String text = Normalizer.normalize(subject, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return text.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** A card's emails, newest first, and how many separate sends it merged. */
    private record Group(List<SchoolMail> mails, int copies) {
    }

    /** Emails of one thread form a group; groups with the same sender and subject within 30 days merge. */
    private static List<Group> groups(List<SchoolMail> newestFirst) {
        Map<String, List<SchoolMail>> threads = new LinkedHashMap<>();
        for (SchoolMail mail : newestFirst) {
            String thread = mail.getThreadId() != null ? "t:" + mail.getThreadId() : "k:" + mail.getMailKey();
            threads.computeIfAbsent(thread, t -> new ArrayList<>()).add(mail);
        }
        List<List<SchoolMail>> merged = new ArrayList<>();
        List<Integer> copies = new ArrayList<>();
        // The groups so far by sender and subject, so a thread is only compared with those it could be a copy of.
        Map<String, List<Integer>> alike = new HashMap<>();
        for (List<SchoolMail> thread : threads.values()) {
            SchoolMail mail = thread.get(0);
            List<Integer> candidates = alike.computeIfAbsent(
                    mail.getSenderAddress().toLowerCase(Locale.ROOT) + "\n" + sameSubject(mail.getSubject()),
                    k -> new ArrayList<>());
            Integer same = candidates.stream().filter(i -> Duration.between(mail.getReceivedAt(),
                    merged.get(i).get(0).getReceivedAt()).abs().compareTo(SAME_EMAIL_WINDOW) <= 0)
                    .findFirst().orElse(null);
            if (same != null) {
                merged.get(same).addAll(thread);
                copies.set(same, copies.get(same) + 1);
            } else {
                candidates.add(merged.size());
                merged.add(new ArrayList<>(thread));
                copies.add(1);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            List<SchoolMail> mails = merged.get(i);
            mails.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
            groups.add(new Group(mails, copies.get(i)));
        }
        return groups;
    }

    /** A card's emails' sessions, each day and start once (one with an end wins), in time order. */
    private static List<Session> sessionsOf(List<SchoolMail> mails, Map<String, List<Session>> sessions) {
        Map<List<Object>, Session> kept = new LinkedHashMap<>();
        for (SchoolMail mail : mails) {
            for (Session s : sessions.getOrDefault(mail.getMailKey(), List.of())) {
                kept.merge(List.of(s.day(), s.start()), s, (old, now) -> old.end() != null ? old : now);
            }
        }
        return kept.values().stream().sorted(Session.ORDER).toList();
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            Set<String> joinedKeys, LocalDateTime now) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
                .filter(c -> c != null && c.isMoved()).findFirst().orElse(null);
        SchoolMailChoice newestChoice = choices.get(newest.getMailKey());
        List<String> categories = moved != null && moved.getCategories() != null ? moved.getCategories()
                : newest.getCategories();
        boolean fromLecturer = moved != null && moved.getFromLecturer() != null ? moved.getFromLecturer()
                : newest.isFromLecturer();
        TreeSet<LocalDate> dates = new TreeSet<>();
        mails.forEach(m -> dates.addAll(m.getDates()));
        List<Session> all = eventLike(categories) ? sessionsOf(mails, sessions) : List.of();
        List<Session> ahead = all.stream().filter(s -> s.endAt().isAfter(now)).toList();
        LocalDate next = all.isEmpty() ? dates.ceiling(now.toLocalDate()) : ahead.isEmpty() ? null : ahead.get(0).day();
        LocalDate registerBy = mails.stream().map(SchoolMail::getRegisterBy).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean closed = registerBy != null && registerBy.isBefore(now.toLocalDate());
        boolean joined = mails.stream().anyMatch(m -> joinedKeys.contains(m.getMailKey()));
        boolean over = all.isEmpty() ? !dates.isEmpty() && next == null : ahead.isEmpty();
        boolean past = over || (categories.contains("event") && closed && !joined);
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), ahead, next, over, past, newest.isSorted(),
                newestChoice != null && newestChoice.isOpened(), newestChoice != null && newestChoice.isDone(),
                mails.size(), group.copies(), eventLike(categories) && !eventLike(newest.getCategories()), registerBy,
                closed, joined);
    }

    private static final Comparator<Card> NEWEST_FIRST = Comparator.comparing(Card::receivedAt).reversed();
    private static final Comparator<Card> SOONEST_FIRST = Comparator.comparing(Card::nextDate,
            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(NEWEST_FIRST);
    private static final Comparator<Card> LATEST_DATE_FIRST = Comparator.comparing(Card::lastDate,
            Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())).thenComparing(NEWEST_FIRST);

    private static String boxOf(Card card) {
        if (card.fromLecturer()) {
            return "lecturers";
        }
        for (String[] box : new String[][] {{"school_task", "tasks"}, {"money", "money"}, {"event", "events"}}) {
            if (card.categories().contains(box[0])) {
                return box[1];
            }
        }
        return "other";
    }

    /** The whole tab, for a student who joined no events. */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices,
            Map<String, List<Session>> sessions, LocalDateTime now) {
        return build(mails, choices, sessions, Set.of(), now);
    }

    /**
     * The whole tab. mails: newest first; choices and sessions by mail key; joinedKeys: the emails with a joined
     * session; now in Vietnam (wall clock).
     */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices,
            Map<String, List<Session>> sessions, Set<String> joinedKeys, LocalDateTime now) {
        Map<String, List<Card>> byBox = new LinkedHashMap<>();
        for (String box : List.of("lecturers", "tasks", "money", "events", "other")) {
            byBox.put(box, new ArrayList<>());
        }
        List<Card> done = new ArrayList<>();
        for (Group group : groups(mails)) {
            Card card = card(group, choices, sessions, joinedKeys, now);
            (card.done() ? done : byBox.get(boxOf(card))).add(card);
        }
        done.sort(NEWEST_FIRST);

        List<Card> events = byBox.get("events");
        List<Card> tasks = byBox.get("tasks");
        List<Card> other = byBox.get("other");
        byBox.get("lecturers").sort(NEWEST_FIRST);
        byBox.get("money").sort(NEWEST_FIRST);
        other.sort(NEWEST_FIRST);
        List<Box> boxes = List.of(
                new Box("lecturers", "From lecturers", byBox.get("lecturers"), List.of(), null),
                new Box("tasks", "School tasks", current(tasks, SOONEST_FIRST), past(tasks), "Past"),
                new Box("money", "Money", byBox.get("money"), List.of(), null),
                new Box("events", "Events", current(events, Comparator.comparing((Card c) -> !c.trainingPoints())
                        .thenComparing(SOONEST_FIRST)), past(events), "Past events"),
                new Box("other", "Everything else", other, List.of(), null));
        return new View(boxes, done);
    }

    private static List<Card> current(List<Card> cards, Comparator<Card> order) {
        return cards.stream().filter(c -> !c.past()).sorted(order).toList();
    }

    private static List<Card> past(List<Card> cards) {
        return cards.stream().filter(Card::past).sorted(LATEST_DATE_FIRST).toList();
    }

    /** Whether these are allowed Move to… categories: one or two known ones, different. */
    public static boolean validCategories(List<String> categories) {
        return !categories.isEmpty() && categories.size() <= 2
                && categories.stream().allMatch(c -> c != null && CATEGORIES.containsKey(c))
                && categories.stream().distinct().count() == categories.size();
    }
}
