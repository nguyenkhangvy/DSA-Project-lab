package vn.edu.hcmiu.sla.school.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.mail.Mailbox.Box;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.mail.Mailbox.View;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;

/** The Mailbox tab's cards and boxes (spec 2026-09-28-outlook-mailbox-design.md, section 6.3). */
class MailboxTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 28); // Mon, in Vietnam
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // UTC
    static final LocalDateTime NOW_IN_VIETNAM = LocalDateTime.of(2026, 9, 28, 8, 0);

    /** An email; key and subject are the same short name, e.g. "tcl". Times are hours before NOW. */
    static final class Mail {
        String key;
        String thread;
        int hoursAgo;
        String sender = "oss@hcmiu.edu.vn";
        String subject;
        List<String> categories = List.of();
        boolean lecturer;
        List<LocalDate> dates = List.of();
        LocalDate registerBy;
        boolean sorted = true;

        Mail(String key, int hoursAgo, String... categories) {
            this.key = key;
            this.subject = key;
            this.hoursAgo = hoursAgo;
            this.categories = List.of(categories);
        }

        Mail thread(String thread) {
            this.thread = thread;
            return this;
        }

        Mail subject(String subject) {
            this.subject = subject;
            return this;
        }

        Mail from(String sender) {
            this.sender = sender;
            return this;
        }

        Mail lecturer() {
            this.lecturer = true;
            return this;
        }

        Mail on(int... daysFromToday) {
            this.dates = Arrays.stream(daysFromToday).mapToObj(TODAY::plusDays).sorted().toList();
            return this;
        }

        Mail registerBy(int daysFromToday) {
            this.registerBy = TODAY.plusDays(daysFromToday);
            return this;
        }

        Mail unsorted() {
            this.sorted = false;
            return this;
        }

        SchoolMail row() {
            SchoolMail row = new SchoolMail(1, key, "00" + key.hashCode(), thread, NOW.minusHours(hoursAgo), "Sender",
                    sender, subject, categories, lecturer, dates, sorted, null);
            row.setRegisterBy(registerBy);
            return row;
        }
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions,
            Set<String> joinedKeys, Mail... mails) {
        List<SchoolMail> rows = new ArrayList<>(Arrays.stream(mails).map(Mail::row).toList());
        rows.sort(Comparator.comparing(SchoolMail::getReceivedAt).reversed());
        return Mailbox.build(rows, choices, sessions, joinedKeys, NOW_IN_VIETNAM);
    }

    static View build(Map<String, SchoolMailChoice> choices, Map<String, List<Session>> sessions, Mail... mails) {
        return build(choices, sessions, Set.of(), mails);
    }

    static View build(Map<String, SchoolMailChoice> choices, Mail... mails) {
        return build(choices, Map.of(), mails);
    }

    /** A session on the day `daysFromToday` from today, "13:30" to "16:30" (end may be null). */
    static Session session(int daysFromToday, String start, String end) {
        return new Session(TODAY.plusDays(daysFromToday), LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
    }

    static View build(Mail... mails) {
        return build(Map.of(), mails);
    }

    static Box box(View view, String id) {
        return view.boxes().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    static List<String> keys(List<Card> cards) {
        return cards.stream().map(Card::key).toList();
    }

    static SchoolMailChoice done(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.setDone(true, NOW);
        return choice;
    }

    static SchoolMailChoice opened(String key) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.open(NOW);
        return choice;
    }

    static SchoolMailChoice moved(String key, boolean lecturer, String... categories) {
        SchoolMailChoice choice = new SchoolMailChoice(1, key, NOW);
        choice.move(List.of(categories), lecturer, NOW);
        return choice;
    }

    @Test
    void theBoxesComeInPriorityOrder() {
        View view = build();

        assertThat(view.boxes()).extracting(Box::title)
                .containsExactly("From lecturers", "School tasks", "Money", "Events", "Everything else");
        assertThat(view.boxes()).allMatch(Box::empty);
    }

    @Test
    void eachCardGoesInTheFirstBoxItFits() {
        View view = build(
                new Mail("lecturer", 1, "class").lecturer(),
                new Mail("survey", 2, "school_task", "training_points"),
                new Mail("invoice", 3, "money"),
                new Mail("workshop", 4, "event", "training_points"),
                new Mail("receipt", 5, "class"),
                new Mail("nothing", 6));

        assertThat(keys(box(view, "lecturers").cards())).containsExactly("lecturer");
        assertThat(keys(box(view, "tasks").cards())).containsExactly("survey");
        assertThat(keys(box(view, "money").cards())).containsExactly("invoice");
        assertThat(keys(box(view, "events").cards())).containsExactly("workshop");
        assertThat(keys(box(view, "other").cards())).containsExactly("receipt", "nothing");
    }

    @Test
    void eventsWithTrainingPointsComeFirstThenTheSoonest() {
        View view = build(
                new Mail("later-points", 1, "event", "training_points").on(5),
                new Mail("soon-no-points", 2, "event").on(1),
                new Mail("soon-points", 3, "event", "training_points").on(2),
                new Mail("undated", 4, "event"),
                new Mail("undated-points", 5, "event", "training_points"));

        assertThat(keys(box(view, "events").cards()))
                .containsExactly("soon-points", "later-points", "undated-points", "soon-no-points", "undated");
    }

    @Test
    void theNextDateIsTheEarliestFromTodayOn() {
        Card card = build(new Mail("closing", 1, "event").on(-6, 2)).card("closing");

        assertThat(card.nextDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(build(new Mail("today", 1, "event").on(0)).card("today").nextDate()).isEqualTo(TODAY);
    }

    @Test
    void pastEventsAndTasksFoldAwayButUndatedOnesStay() {
        View view = build(
                new Mail("over", 1, "event").on(-3),
                new Mail("long-over", 2, "event").on(-10),
                new Mail("undated", 3, "event"),
                new Mail("task-over", 4, "school_task").on(-1),
                new Mail("task-due", 5, "school_task").on(5));

        assertThat(keys(box(view, "events").cards())).containsExactly("undated");
        assertThat(keys(box(view, "events").past())).containsExactly("over", "long-over");
        assertThat(box(view, "events").pastTitle()).isEqualTo("Past events");
        assertThat(keys(box(view, "tasks").cards())).containsExactly("task-due");
        assertThat(keys(box(view, "tasks").past())).containsExactly("task-over");
    }

    @Test
    void otherBoxesNeverFoldPastDates() {
        assertThat(keys(box(build(new Mail("invoice", 1, "money").on(-5)), "money").cards())).containsExactly("invoice");
    }

    @Test
    void aThreadIsOneCardShowingItsNewestEmail() {
        View view = build(
                new Mail("first", 30, "class").lecturer().thread("T1").on(-2),
                new Mail("reply", 2, "class").lecturer().thread("T1"));

        Card card = box(view, "lecturers").cards().get(0);
        assertThat(box(view, "lecturers").cards()).hasSize(1);
        assertThat(List.of(card.key(), card.messages(), card.copies())).containsExactly("reply", 2, 1);
        assertThat(card.keys()).containsExactly("reply", "first");
    }

    @Test
    void theSameEmailSentTwiceIsOneCard() {
        View view = build(
                new Mail("tcl-1", 50, "event", "training_points").subject("[THƯ MỜI] sinh viên tham gia WORKSHOP “TỪ GIẢNG ĐƯỜNG TỚI CÔNG SỞ\""),
                new Mail("tcl-2", 30, "event", "training_points").subject("[THƯ MỜI] SINH VIÊN THAM GIA WORKSHOP “TỪ GIẢNG ĐƯỜNG TỚI CÔNG SỞ”"));

        Card card = box(view, "events").cards().get(0);
        assertThat(box(view, "events").cards()).hasSize(1);
        assertThat(List.of(card.key(), card.copies())).containsExactly("tcl-2", 2);
    }

    @Test
    void aBigInboxIsGroupedWithoutComparingEveryEmailWithEveryOther() {
        // Each opening rebuilds the tab, so building it must stay quick as the Inbox grows.
        Mail[] mails = IntStream.range(0, 3000).mapToObj(i -> new Mail("notice " + i, i % 700, "class"))
                .toArray(Mail[]::new);

        View view = assertTimeout(Duration.ofSeconds(1), () -> build(mails));

        assertThat(box(view, "other").cards()).hasSize(3000).allMatch(card -> card.copies() == 1);
    }

    @Test
    void theSameSubjectFromAnotherSenderOrMonthsApartStaysSeparate() {
        View view = build(
                new Mail("a", 1, "event").subject("Workshop"),
                new Mail("b", 2, "event").subject("Workshop").from("hoisinhvien@hcmiu.edu.vn"),
                new Mail("c", 24 * 40, "event").subject("Workshop"));

        assertThat(box(view, "events").cards()).hasSize(3);
    }

    @Test
    void aCardsDatesComeFromAllItsEmails() {
        Card card = build(
                new Mail("new", 1, "event").thread("T").on(4),
                new Mail("old", 5, "event").thread("T").on(2)).card("new");

        assertThat(card.dates()).containsExactly(TODAY.plusDays(2), TODAY.plusDays(4));
    }

    @Test
    void doneCardsLeaveTheirBoxAndANewReplyBringsThemBack() {
        Map<String, SchoolMailChoice> choices = new HashMap<>(Map.of("first", done("first")));

        View before = build(choices, new Mail("first", 30, "class").lecturer().thread("T"));
        View after = build(choices, new Mail("first", 30, "class").lecturer().thread("T"),
                new Mail("reply", 1, "class").lecturer().thread("T"));

        assertThat(keys(before.done())).containsExactly("first");
        assertThat(box(before, "lecturers").cards()).isEmpty();
        assertThat(after.done()).isEmpty();
        assertThat(keys(box(after, "lecturers").cards())).containsExactly("reply");
    }

    @Test
    void moveToWinsOverTheLaptopsSortingAndSurvivesANewReply() {
        Map<String, SchoolMailChoice> choices = Map.of("first", moved("first", true, "class"));

        View view = build(choices, new Mail("first", 30, "promotion").thread("T"),
                new Mail("reply", 1, "promotion").thread("T"));

        Card card = box(view, "lecturers").cards().get(0);
        assertThat(List.of(card.categories(), card.fromLecturer(), card.moved()))
                .containsExactly(List.of("class"), true, true);
    }

    @Test
    void everythingElseShowsEveryCard() {
        Mail[] mails = new Mail[14];
        for (int i = 0; i < 14; i++) {
            mails[i] = new Mail("m" + i, i + 1);
        }

        assertThat(box(build(mails), "other").cards()).hasSize(14);
    }

    @Test
    void anEventsSessionsDecideItsNextDate() {
        Card card = build(Map.of(), Map.of("talk", List.of(session(0, "06:00", "07:30"), session(1, "13:30", "16:30"))),
                new Mail("talk", 1, "event").on(0, 1, 5)).card("talk");

        assertThat(card.sessions()).containsExactly(session(1, "13:30", "16:30"));
        assertThat(List.of(card.nextDate(), card.past())).containsExactly(TODAY.plusDays(1), false);
    }

    @Test
    void anEventIsPastOnceEverySessionHasEndedEvenWithALaterDate() {
        View view = build(Map.of(), Map.of("talk", List.of(session(0, "06:00", "07:30"))),
                new Mail("talk", 1, "event").on(0, 5));

        assertThat(keys(box(view, "events").past())).containsExactly("talk");
        assertThat(view.card("talk").sessions()).isEmpty();
    }

    @Test
    void aSessionWithoutAnEndLastsAnHour() {
        Card going = build(Map.of(), Map.of("a", List.of(session(0, "07:30", null))), new Mail("a", 1, "event"))
                .card("a");
        Card over = build(Map.of(), Map.of("b", List.of(session(0, "06:30", null))), new Mail("b", 1, "event"))
                .card("b");

        assertThat(List.of(going.past(), over.past())).containsExactly(false, true);
        assertThat(going.sessions().get(0).endAt()).isEqualTo(TODAY.atTime(8, 30));
    }

    @Test
    void onlyEventsAndSchoolTasksUseTheirSessions() {
        Card invoice = build(Map.of(), Map.of("invoice", List.of(session(1, "09:00", null))),
                new Mail("invoice", 1, "money").on(3)).card("invoice");

        assertThat(invoice.sessions()).isEmpty();
        assertThat(invoice.nextDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void aThreadsSessionsAreKeptOnceWithAnEnd() {
        Card card = build(Map.of(), Map.of("new", List.of(session(1, "13:30", null)),
                        "old", List.of(session(1, "13:30", "16:30"), session(2, "08:00", null))),
                new Mail("new", 1, "event").thread("T"), new Mail("old", 5, "event").thread("T")).card("new");

        assertThat(card.sessions()).containsExactly(session(1, "13:30", "16:30"), session(2, "08:00", null));
    }

    @Test
    void openedComesFromTheNewestEmailSoANewReplyIsUnread() {
        Map<String, SchoolMailChoice> choices = Map.of("first", opened("first"));

        Card before = build(choices, new Mail("first", 30, "class").thread("T")).card("first");
        Card after = build(choices, new Mail("first", 30, "class").thread("T"),
                new Mail("reply", 1, "class").thread("T")).card("reply");

        assertThat(List.of(before.opened(), after.opened())).containsExactly(true, false);
    }

    @Test
    void openingMarksDoneUnlessItIsAnEventOrTaskStillAhead() {
        View view = build(Map.of(), Map.of("talk", List.of(session(2, "13:30", null))),
                new Mail("talk", 1, "event"), new Mail("survey", 2, "school_task").on(4),
                new Mail("over", 3, "event").on(-2), new Mail("undated", 4, "event"), new Mail("invoice", 5, "money").on(4));

        assertThat(List.of("talk", "survey", "over", "undated", "invoice")).map(k -> view.card(k).doneWhenOpened())
                .containsExactly(false, false, true, true, true);
    }

    @Test
    void anEventWhoseRegistrationClosedIsPastUnlessJoined() {
        Map<String, List<Session>> sessions = Map.of("closing", List.of(session(2, "09:45", null)));
        Mail closing = new Mail("closing", 1, "event", "training_points").on(2).registerBy(-6);

        View notJoined = build(Map.of(), sessions, closing);
        View joined = build(Map.of(), sessions, Set.of("closing"), closing);

        assertThat(keys(box(notJoined, "events").past())).containsExactly("closing");
        assertThat(keys(box(joined, "events").cards())).containsExactly("closing");
        assertThat(List.of(notJoined.card("closing").closed(), notJoined.card("closing").registerBy()))
                .containsExactly(true, TODAY.minusDays(6));
    }

    @Test
    void openingACardPastOnlyBecauseItsRegistrationClosedDoesNotMarkItDone() {
        View view = build(Map.of(), Map.of("closing", List.of(session(2, "09:45", null))),
                new Mail("closing", 1, "event").on(2).registerBy(-6), new Mail("undated", 2, "event").registerBy(-3),
                new Mail("over", 3, "event").on(-2).registerBy(-6));

        assertThat(List.of("closing", "undated", "over")).map(k -> view.card(k).past())
                .containsExactly(true, true, true);
        assertThat(List.of("closing", "undated", "over")).map(k -> view.card(k).doneWhenOpened())
                .containsExactly(false, false, true);
    }

    @Test
    void registrationIsOpenThroughItsDeadline() {
        Card card = build(new Mail("talk", 1, "event").on(3).registerBy(0)).card("talk");

        assertThat(List.of(card.closed(), card.past())).containsExactly(false, false);
    }

    @Test
    void aThreadKeepsItsLatestDeadline() {
        Card card = build(new Mail("reminder", 1, "event").thread("T").on(4),
                new Mail("first", 30, "event").thread("T").on(4).registerBy(-1),
                new Mail("extended", 10, "event").thread("T").registerBy(2)).card("reminder");

        assertThat(List.of(card.registerBy(), card.closed(), card.past())).containsExactly(TODAY.plusDays(2), false,
                false);
    }

    @Test
    void onlyEventsAndSchoolTasksCloseWithTheirRegistration() {
        View view = build(new Mail("invoice", 1, "money").registerBy(-3));

        assertThat(keys(box(view, "money").cards())).containsExactly("invoice");
    }

    @Test
    void aSchoolTaskStaysInItsBoxWhenItsRegistrationClosed() {
        View view = build(new Mail("civic", 1, "school_task").on(3).registerBy(-2));

        assertThat(keys(box(view, "tasks").cards())).containsExactly("civic");
    }

    @Test
    void everyCategoryHasAShortLabel() {
        assertThat(Mailbox.LABELS.keySet()).containsExactlyElementsOf(Mailbox.CATEGORIES.keySet());
        assertThat(Mailbox.LABELS.get("training_points")).isEqualTo("★ Points");
    }

    @Test
    void anUnsortedEmailSaysSo() {
        assertThat(build(new Mail("x", 1).unsorted()).card("x").sorted()).isFalse();
    }

    @Test
    void validCategories() {
        assertThat(Mailbox.validCategories(List.of("event", "training_points"))).isTrue();
        assertThat(Mailbox.validCategories(List.of("class"))).isTrue();
        assertThat(Mailbox.validCategories(List.of())).isFalse();
        assertThat(Mailbox.validCategories(List.of("event", "event"))).isFalse();
        assertThat(Mailbox.validCategories(List.of("homework"))).isFalse();
        assertThat(Mailbox.validCategories(List.of("event", "money", "class"))).isFalse();
    }

    @Test
    void sameSubjectIgnoresCaseQuotesAndPunctuation() {
        assertThat(Mailbox.sameSubject("[THƯ MỜI] Workshop “A”")).isEqualTo(Mailbox.sameSubject("[thư mời] WORKSHOP \"a\""));
    }
}
