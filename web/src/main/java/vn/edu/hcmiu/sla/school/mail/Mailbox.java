package vn.edu.hcmiu.sla.school.mail;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;

/**
 * What the Mailbox tab shows: emails grouped into cards, cards in boxes, in the order of
 * docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.3. Pure functions.
 */
public final class Mailbox {

    private Mailbox() {
    }

    /** The categories in the order they are shown, with their names. */
    public static final Map<String, String> CATEGORIES = orderedNames(
            "class", "Class", "school_task", "School task", "money", "Money", "event", "Event",
            "training_points", "Training points", "requests_account", "Your requests & account",
            "system_notice", "System notice", "promotion", "Promotion");
    static final Duration SAME_EMAIL_WINDOW = Duration.ofDays(30);
    public static final int EVERYTHING_ELSE_SHOWN = 10;

    private static Map<String, String> orderedNames(String... pairs) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            names.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(names);
    }

    /**
     * One card: a thread, or the same email sent more than once. key, entryId, sender, subject and time come
     * from its newest email; keys are all its emails' keys. nextDate: its earliest date from today on.
     */
    public record Card(String key, List<String> keys, String entryId, String senderName, String subject,
            LocalDateTime receivedAt, List<String> categories, boolean fromLecturer, boolean moved,
            List<LocalDate> dates, LocalDate nextDate, boolean losesPoints, boolean sorted, boolean done,
            int messages, int copies) {

        public boolean trainingPoints() {
            return categories.contains("training_points");
        }

        /** Every date is over (a card without dates is never past). */
        public boolean past() {
            return !dates.isEmpty() && nextDate == null;
        }

        LocalDate lastDate() {
            return dates.isEmpty() ? null : dates.get(dates.size() - 1);
        }
    }

    /**
     * A box. cards: shown; more: in "Show all" (Everything else only); past: in its closed "Past" list
     * (Events and School tasks only).
     */
    public record Box(String id, String title, List<Card> cards, List<Card> more, List<Card> past,
            String pastTitle) {

        public boolean empty() {
            return cards.isEmpty() && more.isEmpty() && past.isEmpty();
        }
    }

    /** The boxes in order, and the closed "Done" list. */
    public record View(List<Box> boxes, List<Card> done) {

        public Card card(String key) {
            return boxes.stream().flatMap(b -> Stream.of(b.cards(), b.more(), b.past()))
                    .flatMap(List::stream).filter(c -> c.key().equals(key))
                    .findFirst()
                    .orElseGet(() -> done.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null));
        }
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
        for (List<SchoolMail> thread : threads.values()) {
            int same = sameEmail(merged, thread.get(0));
            if (same >= 0) {
                merged.get(same).addAll(thread);
                copies.set(same, copies.get(same) + 1);
            } else {
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

    /** The group whose newest email is this email sent again, or -1. */
    private static int sameEmail(List<List<SchoolMail>> groups, SchoolMail mail) {
        for (int i = 0; i < groups.size(); i++) {
            SchoolMail other = groups.get(i).get(0);
            if (other.getSenderAddress().equalsIgnoreCase(mail.getSenderAddress())
                    && sameSubject(other.getSubject()).equals(sameSubject(mail.getSubject()))
                    && Duration.between(mail.getReceivedAt(), other.getReceivedAt()).abs()
                            .compareTo(SAME_EMAIL_WINDOW) <= 0) {
                return i;
            }
        }
        return -1;
    }

    private static Card card(Group group, Map<String, SchoolMailChoice> choices, LocalDate today) {
        List<SchoolMail> mails = group.mails();
        SchoolMail newest = mails.get(0);
        SchoolMailChoice moved = mails.stream().map(m -> choices.get(m.getMailKey()))
                .filter(c -> c != null && c.isMoved()).findFirst().orElse(null);
        SchoolMailChoice newestChoice = choices.get(newest.getMailKey());
        TreeSet<LocalDate> dates = new TreeSet<>();
        mails.forEach(m -> dates.addAll(m.getDates()));
        LocalDate next = dates.ceiling(today);
        List<String> categories = moved != null && moved.getCategories() != null ? moved.getCategories()
                : newest.getCategories();
        boolean fromLecturer = moved != null && moved.getFromLecturer() != null ? moved.getFromLecturer()
                : newest.isFromLecturer();
        return new Card(newest.getMailKey(), mails.stream().map(SchoolMail::getMailKey).toList(), newest.getEntryId(),
                newest.getSenderName(), newest.getSubject(), newest.getReceivedAt(), categories, fromLecturer,
                moved != null, List.copyOf(dates), next, mails.stream().anyMatch(SchoolMail::isLosesPoints),
                newest.isSorted(), newestChoice != null && newestChoice.isDone(), mails.size(), group.copies());
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

    /** The whole tab. mails: newest first; choices by mail key; today in Vietnam. */
    public static View build(List<SchoolMail> mails, Map<String, SchoolMailChoice> choices, LocalDate today) {
        Map<String, List<Card>> byBox = new LinkedHashMap<>();
        for (String box : List.of("lecturers", "tasks", "money", "events", "other")) {
            byBox.put(box, new ArrayList<>());
        }
        List<Card> done = new ArrayList<>();
        for (Group group : groups(mails)) {
            Card card = card(group, choices, today);
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
                new Box("lecturers", "From lecturers", byBox.get("lecturers"), List.of(), List.of(), null),
                new Box("tasks", "School tasks", current(tasks, SOONEST_FIRST), List.of(), past(tasks), "Past"),
                new Box("money", "Money", byBox.get("money"), List.of(), List.of(), null),
                new Box("events", "Events", current(events, Comparator.comparing((Card c) -> !c.trainingPoints())
                        .thenComparing(SOONEST_FIRST)), List.of(), past(events), "Past events"),
                new Box("other", "Everything else", other.subList(0, Math.min(EVERYTHING_ELSE_SHOWN, other.size())),
                        other.subList(Math.min(EVERYTHING_ELSE_SHOWN, other.size()), other.size()), List.of(), null));
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
