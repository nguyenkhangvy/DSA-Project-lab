package vn.edu.hcmiu.sla.school.sync;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import vn.edu.hcmiu.sla.school.VietnamTime;

/**
 * The "What changed" feed: compare old and new data, describe the differences. The Java twin of
 * app/school/services/changes.py, with the same wording.
 *
 * <p>Times are UTC as stored and shown in Vietnam time. Only upcoming classes and exams count, so weeks
 * that are simply over aren't reported.
 */
public final class Changes {

    private Changes() {
    }

    static final int MAX_DATES = 3;
    static final int MATERIALS_NAMED = 3; // titles named in a "new materials" line

    /** One feed line. kind is added, removed or changed. */
    public record Change(String kind, String summary) {
    }

    public record Meeting(String courseCode, String courseName, LocalDateTime startAt, LocalDateTime endAt,
            String room) {
    }

    public record ExamInfo(String courseCode, String courseName, String examType, LocalDateTime startAt,
            String room) {
    }

    /** An announcement, assignment or material, reduced to what the feed compares. */
    public record BbItem(String kind, String course, String bbId, String title, LocalDateTime dueAt, String status,
            Double score, Double pointsPossible, String gradeText, String materialKind) {

        public static BbItem announcement(String course, String bbId, String title) {
            return new BbItem("announcement", course, bbId, title, null, null, null, null, null, null);
        }

        public static BbItem assignment(String course, String bbId, String title, LocalDateTime dueAt, String status,
                Double score, Double pointsPossible, String gradeText) {
            return new BbItem("assignment", course, bbId, title, dueAt, status, score, pointsPossible, gradeText, null);
        }

        public static BbItem material(String course, String bbId, String title, String materialKind) {
            return new BbItem("material", course, bbId, title, null, null, null, null, null, materialKind);
        }
    }

    /** What Blackboard held: the course names and everything in them. */
    public record BbState(List<String> courses, List<BbItem> items) {
    }

    /** "Tue 29/09 08:00" in Vietnam time. */
    private static String when(LocalDateTime utc) {
        return VietnamTime.when(utc);
    }

    private static String dates(Collection<LocalDateTime> moments) {
        List<LocalDateTime> sorted = moments.stream().sorted().toList();
        List<String> shown = sorted.stream().limit(MAX_DATES).map(Changes::when).toList();
        String text = String.join(", ", shown);
        if (sorted.size() > MAX_DATES) {
            text += " and " + (sorted.size() - MAX_DATES) + " more";
        }
        return text;
    }

    private static String count(long n, String singular) {
        return count(n, singular, singular + "s");
    }

    private static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static boolean upcoming(LocalDateTime start, LocalDateTime now) {
        return !start.isBefore(now);
    }

    // ---- Timetable --------------------------------------------------------------

    private record MeetingKey(String courseCode, LocalDateTime startAt) {
    }

    private record Kept(Meeting before, Meeting after) {
    }

    private record RoomMove(String from, String to) {
    }

    private static Map<MeetingKey, Meeting> upcomingMeetings(List<Meeting> meetings, LocalDateTime now) {
        Map<MeetingKey, Meeting> byKey = new LinkedHashMap<>();
        for (Meeting m : meetings) {
            if (upcoming(m.startAt(), now)) {
                byKey.put(new MeetingKey(m.courseCode(), m.startAt()), m);
            }
        }
        return byKey;
    }

    /** old is null on the first sync of a term (or while it had no classes). */
    public static List<Change> timetable(List<Meeting> old, List<Meeting> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing to announce yet
            }
            long courses = fresh.stream().map(Meeting::courseCode).distinct().count();
            long upcoming = fresh.stream().filter(m -> upcoming(m.startAt(), now)).count();
            return List.of(new Change("added", "Timetable loaded: " + count(courses, "course") + ", "
                    + count(upcoming, "upcoming class", "upcoming classes")));
        }

        Map<MeetingKey, Meeting> before = upcomingMeetings(old, now);
        Map<MeetingKey, Meeting> after = upcomingMeetings(fresh, now);
        Map<String, String> labels = new TreeMap<>();
        for (Meeting m : before.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }
        for (Meeting m : after.values()) {
            labels.put(m.courseCode(), m.courseCode() + " " + m.courseName());
        }

        List<Change> changes = new ArrayList<>();
        labels.forEach((code, label) -> {
            List<LocalDateTime> removed = new ArrayList<>();
            List<Meeting> added = new ArrayList<>();
            List<Kept> kept = new ArrayList<>();
            before.forEach((key, meeting) -> {
                if (key.courseCode().equals(code)) {
                    if (after.containsKey(key)) {
                        kept.add(new Kept(meeting, after.get(key)));
                    } else {
                        removed.add(key.startAt());
                    }
                }
            });
            after.forEach((key, meeting) -> {
                if (key.courseCode().equals(code) && !before.containsKey(key)) {
                    added.add(meeting);
                }
            });

            if (!removed.isEmpty()) {
                String what = removed.size() == 1 ? "class cancelled" : removed.size() + " classes cancelled";
                changes.add(new Change("removed", label + ": " + what + " on " + dates(removed)));
            }

            if (added.size() == 1) {
                Meeting m = added.get(0);
                String room = m.room() == null || m.room().isEmpty() ? "" : " (" + m.room() + ")";
                changes.add(new Change("added", label + ": new class on " + when(m.startAt()) + room));
            } else if (!added.isEmpty()) {
                changes.add(new Change("added", label + ": " + added.size() + " new classes on "
                        + dates(added.stream().map(Meeting::startAt).toList())));
            }

            Map<RoomMove, List<LocalDateTime>> roomMoves = new LinkedHashMap<>();
            for (Kept pair : kept) {
                if (!Objects.equals(pair.before().room(), pair.after().room())) {
                    roomMoves.computeIfAbsent(new RoomMove(pair.before().room(), pair.after().room()),
                            move -> new ArrayList<>()).add(pair.after().startAt());
                }
            }
            roomMoves.entrySet().stream()
                    .sorted(Comparator.comparing(move -> Collections.min(move.getValue())))
                    .forEach(move -> changes.add(new Change("changed", label + ": room "
                            + orElse(move.getKey().from(), "?") + " → " + orElse(move.getKey().to(), "?")
                            + " on " + dates(move.getValue()))));

            kept.stream()
                    .sorted(Comparator.comparing(pair -> pair.after().startAt()))
                    .filter(pair -> !pair.before().endAt().equals(pair.after().endAt()))
                    .forEach(pair -> changes.add(new Change("changed", label + ": class on "
                            + when(pair.after().startAt()) + " now ends at "
                            + VietnamTime.clock(pair.after().endAt()))));
        });
        return changes;
    }

    // ---- Exams ------------------------------------------------------------------

    private record ExamKey(String courseCode, String examType) {
    }

    private static Map<ExamKey, ExamInfo> upcomingExams(List<ExamInfo> exams, LocalDateTime now) {
        Map<ExamKey, ExamInfo> byKey = new LinkedHashMap<>();
        for (ExamInfo e : exams) {
            if (upcoming(e.startAt(), now)) {
                byKey.put(new ExamKey(e.courseCode(), e.examType()), e);
            }
        }
        return byKey;
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word
                : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** old is null on the first sync of a term (or while no exams were published). */
    public static List<Change> exams(List<ExamInfo> old, List<ExamInfo> fresh, LocalDateTime now) {
        if (old == null) {
            if (fresh.isEmpty()) {
                return List.of(); // nothing published yet; announced once exams appear
            }
            return List.of(new Change("added", "Exam schedule loaded: " + count(fresh.size(), "exam")));
        }

        Map<ExamKey, ExamInfo> before = upcomingExams(old, now);
        Map<ExamKey, ExamInfo> after = upcomingExams(fresh, now);
        Set<ExamKey> keys = new TreeSet<>(Comparator.comparing(ExamKey::courseCode).thenComparing(ExamKey::examType));
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        List<Change> changes = new ArrayList<>();
        for (ExamKey key : keys) {
            ExamInfo oldExam = before.get(key);
            ExamInfo newExam = after.get(key);
            ExamInfo exam = newExam != null ? newExam : oldExam;
            String label = exam.courseCode() + " " + exam.courseName();
            String type = exam.examType();
            if (oldExam == null) {
                String room = newExam.room() == null || newExam.room().isEmpty() ? "" : " (" + newExam.room() + ")";
                changes.add(new Change("added", "New " + type + " exam: " + label + ", " + when(newExam.startAt()) + room));
            } else if (newExam == null) {
                changes.add(new Change("removed", capitalize(type) + " exam removed: " + label));
            } else {
                if (!oldExam.startAt().equals(newExam.startAt())) {
                    changes.add(new Change("changed", label + " " + type + " exam moved: "
                            + when(oldExam.startAt()) + " → " + when(newExam.startAt())));
                }
                if (!Objects.equals(oldExam.room(), newExam.room())) {
                    changes.add(new Change("changed", label + " " + type + " exam room: "
                            + orElse(oldExam.room(), "?") + " → " + orElse(newExam.room(), "?")));
                }
            }
        }
        return changes;
    }

    // ---- Blackboard -------------------------------------------------------------

    private record ItemKey(String kind, String bbId) {
    }

    /** Like Python's f"{value:g}": 6 significant digits, no trailing zeros (8.5, 10, 6.66667, 1e+06). */
    public static String number(double value) {
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(6, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (rounded.signum() != 0 && (exponent < -4 || exponent >= 6)) {
            String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
            return mantissa + String.format(Locale.ROOT, "e%+03d", exponent);
        }
        return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
    }

    /** "8.5/10", "8.5", the grade's text, or "graded". */
    public static String grade(Double score, Double pointsPossible, String gradeText) {
        if (score != null && pointsPossible != null && pointsPossible != 0) {
            return number(score) + "/" + number(pointsPossible);
        }
        if (score != null) {
            return number(score);
        }
        return orElse(gradeText, "graded");
    }

    private static String grade(BbItem item) {
        return grade(item.score(), item.pointsPossible(), item.gradeText());
    }

    private static String bbCounts(List<BbItem> items) {
        long announcements = items.stream().filter(i -> i.kind().equals("announcement")).count();
        long assignments = items.stream().filter(i -> i.kind().equals("assignment")).count();
        long materials = items.stream().filter(i -> i.kind().equals("material")).count();
        return count(announcements, "announcement") + ", " + count(assignments, "assignment") + ", "
                + count(materials, "material");
    }

    private static Change materialsLine(String course, List<String> titles) {
        if (titles.size() == 1) {
            return new Change("added", "New material · " + course + ": " + titles.get(0));
        }
        String more = titles.size() > MATERIALS_NAMED ? " and " + (titles.size() - MATERIALS_NAMED) + " more" : "";
        return new Change("added", titles.size() + " new materials · " + course + ": "
                + String.join(", ", titles.subList(0, Math.min(MATERIALS_NAMED, titles.size()))) + more);
    }

    /**
     * old is null on the first Blackboard sync. A newly seen course and a batch of new materials each give
     * one line, so a folder of uploads doesn't push the other news off the short list on the Overview.
     */
    public static List<Change> blackboard(BbState old, BbState fresh) {
        if (old == null) {
            if (fresh.courses().isEmpty()) {
                return List.of();
            }
            return List.of(new Change("added", "Blackboard loaded: " + count(fresh.courses().size(), "course") + ", "
                    + bbCounts(fresh.items())));
        }

        Set<String> known = new HashSet<>(old.courses());
        List<Change> changes = new ArrayList<>();
        for (String course : fresh.courses()) {
            if (!known.contains(course)) {
                changes.add(new Change("added", "New course on Blackboard · " + course + ": "
                        + bbCounts(fresh.items().stream().filter(i -> i.course().equals(course)).toList())));
            }
        }
        Map<ItemKey, BbItem> before = new LinkedHashMap<>();
        for (BbItem item : old.items()) {
            before.put(new ItemKey(item.kind(), item.bbId()), item);
        }
        Map<String, List<String>> newMaterials = new LinkedHashMap<>();
        for (BbItem item : fresh.items()) {
            if (!known.contains(item.course())) {
                continue;
            }
            BbItem previous = before.get(new ItemKey(item.kind(), item.bbId()));
            if (item.kind().equals("announcement") && previous == null) {
                changes.add(new Change("added", "New announcement · " + item.course() + ": " + item.title()));
            } else if (item.kind().equals("assignment") && previous == null) {
                String due = item.dueAt() == null ? "" : ", due " + when(item.dueAt());
                changes.add(new Change("added", "New assignment · " + item.course() + ": " + item.title() + due));
            } else if (item.kind().equals("assignment")) {
                if (item.dueAt() != null && previous.dueAt() != null && !item.dueAt().equals(previous.dueAt())) {
                    changes.add(new Change("changed", "Due date changed · " + item.course() + ", " + item.title() + ": "
                            + when(previous.dueAt()) + " → " + when(item.dueAt())));
                } else if (item.dueAt() != null && previous.dueAt() == null) {
                    changes.add(new Change("changed", "Due date set · " + item.course() + ", " + item.title() + ": "
                            + when(item.dueAt())));
                }
                if ("graded".equals(item.status())
                        && (!"graded".equals(previous.status()) || !Objects.equals(previous.score(), item.score()))) {
                    changes.add(new Change("changed", "New grade · " + item.course() + ", " + item.title() + ": "
                            + grade(item)));
                }
            } else if (item.kind().equals("material") && previous == null && !"folder".equals(item.materialKind())) {
                newMaterials.computeIfAbsent(item.course(), course -> new ArrayList<>()).add(item.title());
            }
        }
        newMaterials.forEach((course, titles) -> changes.add(materialsLine(course, titles)));
        return changes;
    }
}
