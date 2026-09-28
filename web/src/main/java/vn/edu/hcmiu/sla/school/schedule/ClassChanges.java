package vn.edu.hcmiu.sla.school.schedule;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.edu.hcmiu.sla.school.VietnamTime;

/**
 * Class changes announced on Blackboard: online, cancelled and make-up classes. Pure functions; the Java
 * twin of app/school/services/class_changes.py, with the same rules and test cases.
 *
 * <p>A sentence that mentions a class, a change word and a date makes a change; each date takes the
 * nearest cancel or make-up word, or an online word when the sentence has neither. The rules are in
 * docs/superpowers/specs/2026-09-26-class-changes-and-to-submit-design.md, section 2.
 *
 * <p>Patterns use Unicode mode, like Python's: {@code \w}, {@code \b}, {@code \d} and {@code \s} know
 * Vietnamese letters, and text is normalised to NFC first, so "lớp" typed either way matches.
 */
public final class ClassChanges {

    private ClassChanges() {
    }

    private static final Logger log = LoggerFactory.getLogger(ClassChanges.class);

    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int IGNORE_CASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE;

    static final Pattern CHANGE_WORDS = Pattern.compile(
            "(?<makeup>\\bmake[\\s-]?up\\b|\\bbù\\b)"
                    + "|(?<cancelled>\\bcancel(?:s|ed|led|ing|ling|lations?)?\\b|\\bno class(?:es)?\\b|\\bnghỉ\\b"
                    + "|\\bhủy\\b|\\bhuỷ\\b)"
                    + "|(?<online>\\bonline\\b|\\btrực tuyến\\b)",
            IGNORE_CASE);
    static final Pattern CLASS_WORDS = Pattern.compile(
            "\\b(?:class(?:es)?|lectures?|sessions?|lessons?|lớp|buổi|tiết|(?:học|dạy) (?:online|trực tuyến|bù)"
                    + "|nghỉ học)\\b",
            IGNORE_CASE);

    static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("june", 6), Map.entry("july", 7), Map.entry("august", 8),
            Map.entry("september", 9), Map.entry("october", 10), Map.entry("november", 11),
            Map.entry("december", 12), Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
            Map.entry("apr", 4), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9),
            Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));
    // Longest names first, so "september" is tried before "sep".
    static final String MONTH = MONTH_NAMES.keySet().stream()
            .sorted(Comparator.comparing(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .collect(Collectors.joining("|"));
    static final String ORDINAL = "(?:st|nd|rd|th)?";
    static final String YEAR = "(?:,?\\s+(?<year>\\d{4}))?";
    static final List<Pattern> DATE_FORMATS = List.of(
            Pattern.compile("\\b(?<month>" + MONTH + ")\\b\\.?\\s+(?<day>\\d{1,2})" + ORDINAL + "(?!\\d)" + YEAR,
                    IGNORE_CASE),
            Pattern.compile("(?<!\\d)(?<day>\\d{1,2})" + ORDINAL + "\\s+(?:of\\s+)?(?<month>" + MONTH + ")\\b\\.?"
                    + YEAR, IGNORE_CASE),
            Pattern.compile("(?<![\\w/.:])(?<day>\\d{1,2})/(?<month>\\d{1,2})(?:/(?<year>\\d{4}))?(?![\\d/])",
                    UNICODE),
            Pattern.compile("(?<![\\w/.:-])(?<day>\\d{1,2})-(?<month>\\d{1,2})-(?<year>\\d{4})(?![\\d-])", UNICODE),
            Pattern.compile("ngày\\s+(?<day>\\d{1,2})\\s+tháng\\s+(?<month>\\d{1,2})(?:\\s+năm\\s+(?<year>\\d{4}))?",
                    IGNORE_CASE));
    static final Pattern TIME = Pattern.compile(
            "(?<![\\w/.:])(?<hour>\\d{1,2})(?:[:hg](?<minute>\\d{2})?|(?=\\s*[ap]\\.?m\\b))"
                    + "(?:\\s*(?<ampm>[ap])\\.?m\\.?)?(?!\\w)",
            IGNORE_CASE);
    static final Pattern RANGE_JOIN = Pattern.compile("\\s*(?:-|–|to|đến)\\s*", IGNORE_CASE);
    static final Pattern ROOM = Pattern.compile("(?<![\\w.])(?:[A-Z]{1,2}\\d\\.\\d{3}|R\\d{3})(?!\\w|\\.\\w)", UNICODE);
    static final Pattern URL = Pattern.compile("https?://\\S+", UNICODE);
    static final List<String> ABBREVIATIONS = List.of("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec", "dr", "mr", "ms", "mrs");
    static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.!?])" + ABBREVIATIONS.stream().map(a -> "(?<!\\b" + a + "\\.)").collect(Collectors.joining())
                    + "(?<![ap]\\.m\\.)\\s+|\\n+",
            IGNORE_CASE);

    /** A change one announcement makes. kind: online / cancelled / makeup; start, end, room: make-ups only. */
    public record Announced(String kind, LocalDate day, LocalTime start, LocalTime end, String room) {

        Announced(String kind, LocalDate day) {
            this(kind, day, null, null, null);
        }
    }

    /**
     * Where a change links to: the course page for a Blackboard announcement ("See announcement"), or the email
     * in Mailbox for a lecturer's email ("See email").
     */
    public record Source(String link, String text) {

        public static Source course(int bbCourseId) {
            return new Source("/school/courses/" + bbCourseId, "See announcement");
        }

        public static Source email(String mailKey) {
            return new Source("/school/mailbox#mail-" + mailKey, "See email");
        }
    }

    /** A change for one course, code "MA026IU", and where it was announced. */
    public record ClassChange(String code, Source source, String kind, LocalDate day, LocalTime start, LocalTime end,
            String room) {
    }

    /** Where a change applies: slot "class" (online, cancelled) or "makeup" (a make-up class). */
    public record Slot(String code, LocalDate day, String slot) {
    }

    /** An announcement with its course: what {@link #changesFrom} reads. postedAt is UTC. */
    public record Posted(String code, Source source, String title, String text, LocalDateTime postedAt) {
    }

    /** A change the laptop already read from a lecturer's email. postedAt (when it arrived) is UTC. */
    public record Emailed(String code, Source source, LocalDateTime postedAt, Announced change) {
    }

    /** Reads one announcement; tests swap in a reader that fails. */
    interface Reader {
        List<Announced> read(String title, String text, LocalDateTime postedAt);
    }

    private record Found(int start, int end, LocalDate day) {
    }

    private record Word(int start, String kind) {
    }

    private static LocalDate date(Matcher match, LocalDate postedDay) {
        String monthText = match.group("month");
        int month = monthText.chars().allMatch(Character::isLetter)
                ? MONTH_NAMES.get(monthText.toLowerCase(Locale.ROOT))
                : Integer.parseInt(monthText);
        int day = Integer.parseInt(match.group("day"));
        String year = match.group("year");
        try {
            if (year != null) {
                return LocalDate.of(Integer.parseInt(year), month, day);
            }
            List<LocalDate> candidates = new ArrayList<>();
            for (int k = -1; k <= 1; k++) {
                candidates.add(LocalDate.of(postedDay.getYear() + k, month, day));
            }
            LocalDate nearest = candidates.get(0);
            for (LocalDate candidate : candidates) {
                if (distance(candidate, postedDay) < distance(nearest, postedDay)) {
                    nearest = candidate;
                }
            }
            return nearest;
        } catch (DateTimeException error) {
            return null; // 31/2, month 13, ...
        }
    }

    private static long distance(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    /** The dates in a sentence from the posting day on, in the order they appear, with their positions. */
    private static List<Found> dates(String sentence, LocalDate postedDay) {
        List<Found> found = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        for (Pattern pattern : DATE_FORMATS) {
            Matcher match = pattern.matcher(sentence);
            while (match.find()) {
                int start = match.start();
                int end = match.end();
                if (taken.stream().anyMatch(span -> start < span[1] && span[0] < end)) {
                    continue;
                }
                taken.add(new int[] {start, end});
                LocalDate day = date(match, postedDay);
                if (day != null && !day.isBefore(postedDay)) {
                    found.add(new Found(start, end, day));
                }
            }
        }
        found.sort(Comparator.comparingInt(Found::start).thenComparingInt(Found::end).thenComparing(Found::day));
        return found;
    }

    /** The time a match names, read with ampm ("a" / "p") when given, else with its own AM/PM. */
    private static LocalTime time(Matcher match, String ampm) {
        int hour = Integer.parseInt(match.group("hour"));
        int minute = match.group("minute") == null ? 0 : Integer.parseInt(match.group("minute"));
        String half = (ampm != null ? ampm : match.group("ampm") != null ? match.group("ampm") : "")
                .toLowerCase(Locale.ROOT);
        if (half.equals("p") && hour < 12) {
            hour += 12;
        }
        if (half.equals("a") && hour == 12) {
            hour = 0;
        }
        return hour < 24 && minute < 60 ? LocalTime.of(hour, minute) : null;
    }

    /**
     * The first time in a text and, for a range like 8:00-9:40, its end. In a range, a start without AM/PM
     * takes the end's when that keeps it before the end: "1:15 to 3:45 PM" is 13:15-15:45.
     */
    private static LocalTime[] times(String text) {
        Matcher first = TIME.matcher(text);
        if (!first.find()) {
            return new LocalTime[] {null, null};
        }
        LocalTime start = time(first, null);
        LocalTime end = null;
        Matcher second = TIME.matcher(text);
        if (second.find(first.end()) && RANGE_JOIN.matcher(text.substring(first.end(), second.start())).matches()) {
            end = time(second, null);
            if (start != null && end != null && first.group("ampm") == null && second.group("ampm") != null) {
                LocalTime shifted = time(first, second.group("ampm"));
                if (shifted != null && shifted.isBefore(end)) {
                    start = shifted;
                }
            }
        }
        return new LocalTime[] {start, end};
    }

    private static String kindOf(Matcher match) {
        if (match.group("makeup") != null) {
            return "makeup";
        }
        return match.group("cancelled") != null ? "cancelled" : "online";
    }

    /** The class changes one announcement makes. postedAt is UTC. */
    public static List<Announced> readAnnouncement(String title, String text, LocalDateTime postedAt) {
        LocalDate postedDay = VietnamTime.date(postedAt);
        List<String> sentences = new ArrayList<>();
        sentences.add(title == null ? "" : title);
        sentences.addAll(List.of(SENTENCE_END.split(text == null ? "" : text, -1)));

        List<Announced> found = new ArrayList<>();
        for (String raw : sentences) {
            String sentence = URL.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ");
            List<Word> words = new ArrayList<>();
            Matcher change = CHANGE_WORDS.matcher(sentence);
            while (change.find()) {
                words.add(new Word(change.start(), kindOf(change)));
            }
            if (words.isEmpty() || !CLASS_WORDS.matcher(sentence).find()) {
                continue;
            }
            // Online words decide only when there is no cancel or make-up word: "make-up class online on
            // 3/10" is an online make-up class.
            List<Word> deciding = words.stream().filter(word -> !word.kind().equals("online")).toList();
            if (deciding.isEmpty()) {
                deciding = words;
            }
            List<Found> dates = dates(sentence, postedDay);
            for (int i = 0; i < dates.size(); i++) {
                Found date = dates.get(i);
                Word nearest = deciding.get(0);
                for (Word word : deciding) {
                    if (Math.abs(word.start() - date.start()) < Math.abs(nearest.start() - date.start())) {
                        nearest = word;
                    }
                }
                if (!nearest.kind().equals("makeup")) {
                    found.add(new Announced(nearest.kind(), date.day()));
                    continue;
                }
                // A make-up's time and room come from the text after its date (up to the next date), or else
                // from the text before it (back to the previous date).
                String after = sentence.substring(date.end(), i + 1 < dates.size() ? dates.get(i + 1).start()
                        : sentence.length());
                String before = sentence.substring(i > 0 ? dates.get(i - 1).end() : 0, date.start());
                LocalTime[] clock = times(TIME.matcher(after).find() ? after : before);
                Matcher room = ROOM.matcher(after);
                if (!room.find()) {
                    room = ROOM.matcher(before);
                    if (!room.find()) {
                        room = null;
                    }
                }
                boolean online = words.stream().anyMatch(word -> word.kind().equals("online"));
                found.add(new Announced("makeup", date.day(), clock[0], clock[1],
                        online ? "Online" : room == null ? null : room.group()));
            }
        }
        return found;
    }

    /**
     * Changes by course, day and slot. The newest announcement wins within each slot, so a later make-up
     * notice naming a cancelled day doesn't erase the cancellation. Announcements without a course code or a
     * posting time are skipped, and so is one that can't be read.
     */
    public static Map<Slot, ClassChange> changesFrom(List<Posted> announcements) {
        return changesFrom(announcements, List.of());
    }

    /** Announcements and emailed changes together: the newest wins in each slot, whichever it came from. */
    public static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, List<Emailed> emailed) {
        return changesFrom(announcements, emailed, ClassChanges::readAnnouncement);
    }

    static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, Reader reader) {
        return changesFrom(announcements, List.of(), reader);
    }

    static Map<Slot, ClassChange> changesFrom(List<Posted> announcements, List<Emailed> emailed, Reader reader) {
        List<Emailed> all = new ArrayList<>();
        for (Posted a : announcements) {
            if (a.code() == null || a.code().isEmpty() || a.postedAt() == null) {
                continue;
            }
            try {
                for (Announced one : reader.read(a.title(), a.text(), a.postedAt())) {
                    all.add(new Emailed(a.code(), a.source(), a.postedAt(), one));
                }
            } catch (RuntimeException error) { // one unreadable announcement must never break a page
                log.warn("Couldn't read an announcement for class changes", error);
            }
        }
        emailed.stream().filter(e -> e.code() != null && !e.code().isEmpty() && e.postedAt() != null).forEach(all::add);
        all.sort(Comparator.comparing(Emailed::postedAt)); // stable: at the same time, emails come after announcements

        Map<Slot, ClassChange> changes = new LinkedHashMap<>();
        for (Emailed e : all) {
            Announced one = e.change();
            String slot = one.kind().equals("makeup") ? "makeup" : "class";
            changes.put(new Slot(e.code(), one.day(), slot), new ClassChange(e.code(), e.source(), one.kind(), one.day(),
                    one.start(), one.end(), one.room()));
        }
        return changes;
    }
}
