package vn.edu.hcmiu.sla.school.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.schedule.Schedule.Item;

class MyEventConflictsTest {

    /** Mon 05/10/2026 at hh:mm Vietnam time, as UTC. */
    static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 5, hour, minute).minusHours(7);
    }

    static Item mine(int eventId, int fromHour, int toHour) {
        return new Item("mine", at(fromHour, 0), at(toHour, 0), null, "Tự học " + eventId, null, "My event", null, null,
                false, eventId);
    }

    static final Item WEB = new Item("class", at(17, 15), at(19, 45), "IT093IU", "Web Application Development", "A2.401",
            null, null, null, false, null);

    @Test
    void anOwnEventOverlappingAClassClashesWithIt() {
        Item selfStudy = mine(1, 17, 19);

        assertThat(MyEventConflicts.clashes(List.of(WEB, selfStudy)))
                .containsExactly(new MyEventConflicts.Clash(selfStudy, List.of(WEB)));
    }

    @Test
    void endingAsTheOtherStartsIsNoClash() {
        assertThat(MyEventConflicts.clashes(List.of(WEB, mine(1, 15, 17), mine(2, 20, 21)))).isEmpty();
        Item endsAtClassStart = new Item("mine", at(16, 15), at(17, 15), null, "x", null, "My event", null, null, false, 3);
        assertThat(MyEventConflicts.clashes(List.of(WEB, endsAtClassStart))).isEmpty();
    }

    @Test
    void cancelledAndUntimedClassesAreNoClash() {
        Item cancelled = new Item("class", at(17, 15), at(19, 45), "IT093IU", "Web", null, null, "cancelled", null, false,
                null);
        Item untimedMakeUp = new Item("class", at(0, 0), null, "IT093IU", "Web", null, null, "makeup", null, true, null);

        assertThat(MyEventConflicts.clashes(List.of(cancelled, untimedMakeUp, mine(1, 17, 19)))).isEmpty();
    }

    @Test
    void anExamWithoutALengthTakes90Minutes() {
        Item exam = new Item("exam", at(16, 0), null, "IT093IU", "Web", "A1.101", "Final exam", null, null, false, null);

        assertThat(MyEventConflicts.clashes(List.of(exam, mine(1, 17, 19)))).hasSize(1);
        assertThat(MyEventConflicts.clashes(List.of(exam, mine(1, 18, 19)))).isEmpty();
    }

    @Test
    void joinedEventsAndOtherOwnEventsClashButAnEventNeverWithItself() {
        Item talk = new Item("event", at(18, 0), at(20, 0), null, "Talkshow", "Hall A2", "Event", null, null, false, null);
        Item first = mine(1, 17, 19);
        Item second = mine(2, 18, 19);
        Item firstAgain = mine(1, 17, 19); // same event, same time: never a clash with itself

        List<MyEventConflicts.Clash> clashes = MyEventConflicts.clashes(List.of(first, talk, second, firstAgain));

        assertThat(clashes.get(0)).isEqualTo(new MyEventConflicts.Clash(first, List.of(talk, second)));
        assertThat(clashes.get(1)).isEqualTo(new MyEventConflicts.Clash(second, List.of(first, firstAgain, talk)));
    }

    @Test
    void namesSayWhatAndWhen() {
        Item exam = new Item("exam", at(16, 0), null, "IT093IU", "Web Application Development", null, "Final exam", null,
                null, false, null);

        assertThat(MyEventConflicts.name(WEB)).isEqualTo("IT093IU Web Application Development (17:15–19:45)");
        assertThat(MyEventConflicts.name(exam)).isEqualTo("Final exam: Web Application Development (16:00–17:30)");
        assertThat(MyEventConflicts.name(mine(2, 18, 19))).isEqualTo("My event: Tự học 2 (18:00–19:00)");
    }
}
