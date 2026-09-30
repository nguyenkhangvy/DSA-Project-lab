package vn.edu.hcmiu.sla.school.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;

/**
 * The Timetable's calendar feed: Java twin of the feed tests in tests/test_school_schedule_pages.py,
 * tests/test_school_blackboard_pages.py and tests/test_school_class_change_pages.py. Times in the
 * database are UTC; the feed shows Vietnam time (UTC+7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CalendarFeedTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    // Tue 29/09/2026 08:00-10:30 in Vietnam = 01:00-03:30 UTC
    static final Meeting WEB_TUESDAY = new Meeting(LocalDateTime.of(2026, 9, 29, 1, 0), LocalDateTime.of(2026, 9, 29, 3, 30),
            "A2.508");
    static final String NEXT_WEEK_START = "2026-09-28T00:00:00Z"; // as FullCalendar sends them
    static final String NEXT_WEEK_END = "2026-10-05T00:00:00Z";

    static final String PROBABILITY = "Probability, Statistic & Random Process";
    // Thursdays 13:15-15:45 in Vietnam = 06:15-08:45 UTC
    static final Meeting THU_24 = new Meeting(LocalDateTime.of(2026, 9, 24, 6, 15), LocalDateTime.of(2026, 9, 24, 8, 45),
            "A2.407");
    static final Meeting THU_01 = new Meeting(LocalDateTime.of(2026, 10, 1, 6, 15), LocalDateTime.of(2026, 10, 1, 8, 45),
            "A2.407");
    static final LocalDateTime POSTED = LocalDateTime.of(2026, 9, 20, 2, 0);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    List<Map<String, Object>> feed(String start, String end) throws Exception {
        String body = mvc.perform(get("/school/api/calendar").param("start", start).param("end", end).with(user(an)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    List<Map<String, Object>> nextWeek() throws Exception {
        return feed(NEXT_WEEK_START, NEXT_WEEK_END);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> props(Map<String, Object> event) {
        return (Map<String, Object>) event.get("extendedProps");
    }

    /** The classes in the week of Mon 21/09 - Sun 27/09, or another range. */
    List<Map<String, Object>> classes(String start, String end) throws Exception {
        return feed(start, end).stream().filter(e -> props(e).get("kind").equals("class")).toList();
    }

    List<Map<String, Object>> classes() throws Exception {
        return classes("2026-09-21", "2026-09-28");
    }

    List<Map<String, Object>> makeups() throws Exception {
        return classes().stream().filter(e -> "makeup".equals(props(e).get("change"))).toList();
    }

    Integer announce(AppUser who, String code, String title, String text, LocalDateTime postedAt) {
        SchoolBbCourse course = data.bbCourse(who, code, code + " on Blackboard");
        data.announce(course, title, text, postedAt);
        return data.save(course);
    }

    void join(AppUser who, LocalDate day, LocalTime start, LocalTime end, String place, boolean points) {
        db.persist(new SchoolMailJoined(who.id(), "a".repeat(64), day, start, end, "[THƯ MỜI] Workshop A", place,
                points, false, LocalDateTime.of(2026, 9, 28, 1, 0)));
        db.flush();
    }

    // ---- Classes and exams ------------------------------------------------------------

    @Test
    void theCalendarFeedGivesClassesInVietnamTime() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);

        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END)
                        .with(user(an)))
                .andExpect(content().json("""
                        [{"title": "Web Application Development",
                          "start": "2026-09-29T08:00:00",
                          "end": "2026-09-29T10:30:00",
                          "classNames": ["event-class"],
                          "extendedProps": {"kind": "class", "code": "IT093IU", "room": "A2.508"}}]
                        """, JsonCompareMode.STRICT));
    }

    // ---- Events joined from Mailbox (spec 2026-09-28-mailbox-events-design.md, 4.7) ----

    @Test
    void aJoinedEventIsGreenInTheCalendarAndLinksToItsEmail() throws Exception {
        join(an, LocalDate.of(2026, 9, 29), LocalTime.of(14, 0), LocalTime.of(16, 0), "Hall A2", true);

        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END)
                        .with(user(an)))
                .andExpect(content().json("""
                        [{"title": "★ Training points: [THƯ MỜI] Workshop A",
                          "start": "2026-09-29T14:00:00",
                          "end": "2026-09-29T16:00:00",
                          "classNames": ["event-event"],
                          "url": "/school/mailbox#mail-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "extendedProps": {"kind": "event", "code": null, "room": "Hall A2"}}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void aJoinedSessionWithoutAnEndLastsAnHourNextToAClass() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        join(an, LocalDate.of(2026, 9, 29), LocalTime.of(9, 0), null, null, false);

        List<Map<String, Object>> week = nextWeek();

        assertThat(week).extracting(e -> e.get("title")).containsExactly("Web Application Development",
                "Event: [THƯ MỜI] Workshop A");
        assertThat(week.get(1)).containsEntry("start", "2026-09-29T09:00:00").containsEntry("end", "2026-09-29T10:00:00");
    }

    @Test
    void someoneElsesJoinedEventsAreNeverInMyCalendar() throws Exception {
        join(data.user("binh@example.com"), LocalDate.of(2026, 9, 29), LocalTime.of(14, 0), null, null, false);

        assertThat(nextWeek()).isEmpty();
    }

    @Test
    void theCalendarFeedHasOnlyMyClasses() throws Exception {
        data.course(data.user("binh@example.com"), "BA001IU", "Binh's Business Course", WEB_TUESDAY);

        assertThat(nextWeek()).isEmpty();
    }

    @Test
    void aClassEarlyOnMondayInVietnamBelongsToThatMonday() throws Exception {
        // Mon 05/10/2026 06:00 in Vietnam is still Sunday 04/10 23:00 in UTC.
        data.course(an, "MA001IU", "Early Maths",
                new Meeting(LocalDateTime.of(2026, 10, 4, 23, 0), LocalDateTime.of(2026, 10, 5, 0, 30), "A1.1"));

        assertThat(nextWeek()).isEmpty();
        assertThat(feed("2026-10-05T00:00:00Z", "2026-10-12T00:00:00Z"))
                .singleElement().extracting(e -> e.get("start")).isEqualTo("2026-10-05T06:00:00");
    }

    @Test
    void examsAreInTheCalendarWithTheirOwnColour() throws Exception {
        data.exam(an, "IT093IU", "Web Application Development", LocalDateTime.of(2026, 9, 30, 1, 0), "A1.101", "final");

        Map<String, Object> event = nextWeek().get(0);

        assertThat(List.of(event.get("title"), event.get("start"), event.get("classNames"))).containsExactly(
                "Final exam: Web Application Development", "2026-09-30T08:00:00", List.of("event-exam"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NONE", value = {
        "missing, NONE, NONE",
        "not-a-date, not-a-date, 2026-10-05",
        "end-before-start, 2026-10-05, 2026-09-28",
        "too-long, 2026-01-01, 2026-12-31"})
    void theCalendarFeedRefusesBadRanges(String name, String start, String end) throws Exception {
        var request = get("/school/api/calendar").with(user(an));
        if (start != null) {
            request.param("start", start).param("end", end);
        }

        mvc.perform(request).andExpect(status().isBadRequest());
    }

    @Test
    void theCalendarFeedNeedsLogin() throws Exception {
        mvc.perform(get("/school/api/calendar").param("start", NEXT_WEEK_START).param("end", NEXT_WEEK_END))
                .andExpect(redirectedUrl("/auth/login"));
    }

    // ---- Blackboard deadlines ---------------------------------------------------------------

    List<Map<String, Object>> deadlines() throws Exception {
        return feed("2026-09-28", "2026-10-05").stream().filter(e -> props(e).get("kind").equals("due")).toList();
    }

    @Test
    void aDeadlineAt2359VietnamTimeIsInTheCalendarOnThatDay() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.save(data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null));

        Map<String, Object> due = deadlines().get(0);

        assertThat(List.of(due.get("start"), due.get("allDay"), due.get("classNames")))
                .containsExactly("2026-10-02", true, List.of("event-due"));
        assertThat(due.get("title")).isEqualTo("Due 23:59: Lab 3 · Web Application Development");
    }

    @Test
    void aDeadlineJustAfterMidnightBelongsToTheNextVietnamDay() throws Exception {
        // Sat 03/10 00:30 in Vietnam is still Fri 02/10 17:30 in UTC.
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.save(data.assign(course, "Quiz", LocalDateTime.of(2026, 10, 2, 17, 30), "not_graded", null, null, null));

        assertThat(deadlines()).extracting(e -> e.get("start")).containsExactly("2026-10-03");
    }

    @Test
    void doneDeadlinesGetACheckMarkInTheCalendar() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "needs_grading", null, null, null);
        data.save(data.assign(course, "Lab 4", LocalDateTime.of(2026, 10, 3, 16, 59), "not_graded", null, null, null));

        assertThat(deadlines()).extracting(e -> e.get("title")).containsExactlyInAnyOrder(
                "Due 23:59: Lab 4 · Web Application Development", "✓ Due 23:59: Lab 3 · Web Application Development");
    }

    // ---- Classes changed by Blackboard announcements ----------------------------------------

    @Test
    void anOnlineClassIsPurpleAndLinksToTheAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24, THU_01);
        Integer courseId = announce(an, "MA026IU", "ONLINE CLASS ON SEPTEMBER 24", "", LocalDateTime.of(2026, 9, 23, 16, 7));

        Map<String, Object> event = classes().get(0);

        assertThat(classes()).hasSize(1);
        assertThat(event.get("title")).isEqualTo("Online: " + PROBABILITY);
        assertThat(List.of(event.get("classNames"), props(event).get("room"), props(event).get("change")))
                .containsExactly(List.of("event-changed"), "Online", "online");
        assertThat(event.get("url")).isEqualTo("/school/courses/" + courseId);
        Map<String, Object> nextWeek = classes("2026-09-28", "2026-10-05").get(0);
        assertThat(nextWeek.get("classNames")).isEqualTo(List.of("event-class"));
        assertThat(nextWeek).doesNotContainKey("url");
    }

    @Test
    void aCancelledClassStaysInItsSlotGreyed() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", POSTED);

        Map<String, Object> event = classes().get(0);

        assertThat(List.of(event.get("title"), event.get("classNames"), event.get("start"))).containsExactly(
                "Cancelled: " + PROBABILITY, List.of("event-cancelled"), "2026-09-24T13:15:00");
        assertThat(props(event).get("room")).isEqualTo("A2.407");
    }

    @Test
    void aMakeUpClassWithATimeIsAdded() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on Saturday 26/9 from 8:00 to 9:40 in A2.401", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(
                List.of(e.get("title"), e.get("start"), e.get("end"), props(e).get("room"))).containsExactly(
                "Make-up: " + PROBABILITY, "2026-09-26T08:00:00", "2026-09-26T09:40:00", "A2.401"));
        assertThat(classes()).hasSize(2); // the normal Thursday class is still there
    }

    @Test
    void aMakeUpClassWithoutAnEndLastsAsLongAsTheUsualClass() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24, THU_01);
        announce(an, "MA026IU", "Make-up class on 26/9 at 13h15", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(List.of(e.get("start"), e.get("end")))
                .containsExactly("2026-09-26T13:15:00", "2026-09-26T15:45:00"));
    }

    @Test
    void aMakeUpClassEndingBeforeItStartsUsesTheUsualLength() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on 26/9 from 13:15 to 9:40", "", POSTED);

        assertThat(makeups()).singleElement().satisfies(e -> assertThat(List.of(e.get("start"), e.get("end")))
                .containsExactly("2026-09-26T13:15:00", "2026-09-26T15:45:00"));
    }

    @Test
    void aMakeUpClassWithoutATimeIsAnAllDayNote() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Học bù ngày 26/9", "", POSTED);

        assertThat(classes().stream().filter(e -> Boolean.TRUE.equals(e.get("allDay"))).toList()).singleElement()
                .satisfies(note -> assertThat(List.of(note.get("title"), note.get("start"), note.get("classNames")))
                        .containsExactly("Make-up class: " + PROBABILITY + " (time not given, see announcement)",
                                "2026-09-26",
                                List.of("event-changed")));
    }

    @Test
    void aMakeUpClassAtTheTimeOfAClassIsNotAddedTwice() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Make-up class on 24/9 at 13:15", "", POSTED);

        assertThat(classes()).hasSize(1);
    }

    @Test
    void aMakeUpNoticeNamingTheCancelledDayKeepsItCancelled() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", LocalDateTime.of(2026, 9, 15, 0, 0));
        announce(an, "MA026IU", "The make-up for the class on September 24 will be on 3/10 at 8:00", "",
                LocalDateTime.of(2026, 9, 16, 0, 0));

        assertThat(classes()).extracting(e -> e.get("title"), e -> e.get("start"))
                .containsExactly(tuple("Cancelled: " + PROBABILITY, "2026-09-24T13:15:00"));
        assertThat(classes("2026-09-28", "2026-10-05")).extracting(e -> e.get("title"), e -> e.get("start"))
                .containsExactly(tuple("Make-up: " + PROBABILITY, "2026-10-03T08:00:00"));
    }

    @Test
    void changesNeedAClassOfThatCourseOnThatDay() throws Exception {
        // The real "Logistics Reminder": "Starting the week of 12/10, lectures will be taught online" is read as
        // Mon 12/10, and this Saturday course has no class that day.
        data.course(an, "IT007WE", "Skills for Communicating Information",
                new Meeting(LocalDateTime.of(2026, 10, 10, 6, 15), LocalDateTime.of(2026, 10, 10, 8, 45), "A1.603"),
                new Meeting(LocalDateTime.of(2026, 10, 17, 6, 15), LocalDateTime.of(2026, 10, 17, 8, 45), "A1.603"));
        announce(an, "IT007WE", "Logistics Reminder", "Our last in-person lecture is on 10/10. Starting the week of 12/10 , "
                + "lectures will be taught online by Dr. Nguyen Van A via MS Teams.", LocalDateTime.of(2026, 9, 22, 3, 59));

        assertThat(classes("2026-10-05", "2026-10-19")).extracting(e -> e.get("classNames"))
                .containsExactly(List.of("event-class"), List.of("event-class"));
    }

    @Test
    void aMakeUpForACourseNotInTheTimetableIsIgnored() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "EN011IU", "Make-up class on 26/9 at 8:00", "", POSTED);

        assertThat(classes()).hasSize(1);
    }

    @Test
    void anotherUsersAnnouncementsNeverChangeMyClasses() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(data.user("binh@example.com"), "MA026IU", "ONLINE CLASS ON SEPTEMBER 24", "", POSTED);

        assertThat(classes()).singleElement().extracting(e -> e.get("classNames")).isEqualTo(List.of("event-class"));
    }

    // ---- Class changes from lecturers' emails ----------------------------------------

    static final String KEY = "e".repeat(64);
    static final LocalDate SEPT_24 = LocalDate.of(2026, 9, 24);

    @Test
    void anEmailsChangeMarksTheClassAndLinksToTheEmail() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED, null), "MA026IU", "online", SEPT_24, null, null,
                null));

        Map<String, Object> event = classes().get(0);

        assertThat(event.get("title")).isEqualTo("Online: " + PROBABILITY);
        assertThat(event.get("url")).isEqualTo("/school/mailbox#mail-" + KEY);
    }

    @Test
    void aBlackboardCopyIsSkippedWhenTheAnnouncementIsStoredWhicheverCameFirst() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        Integer courseId = announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.minusMinutes(5), " online class on september 24 "),
                "MA026IU", "online", SEPT_24, null, null, null));

        Map<String, Object> event = classes().get(0);

        assertThat(classes()).hasSize(1);
        assertThat(event.get("url")).isEqualTo("/school/courses/" + courseId);
    }

    @Test
    void aBlackboardCopyCountsWhileBlackboardHasNotSyncedIt() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED, "Online class on September 24"), "MA026IU",
                "online", SEPT_24, null, null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsExactly("online");
    }

    @Test
    void theSameChangeSentBothWaysIsOneClass() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(1), null), "MA026IU", "online",
                SEPT_24, null, null, null));

        assertThat(classes()).hasSize(1);
        assertThat(classes().get(0).get("url")).isEqualTo("/school/mailbox#mail-" + KEY);
    }

    @Test
    void aNewerEmailOverridesAnOlderAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Online class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(3), null), "MA026IU", "cancelled",
                SEPT_24, null, null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsExactly("cancelled");
    }

    @Test
    void anEmailedMakeUpClassWithoutATimeSaysToSeeTheEmail() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED, null), "MA026IU", "makeup",
                LocalDate.of(2026, 9, 26), null, null, null));

        assertThat(makeups()).singleElement().satisfies(note -> assertThat(List.of(note.get("title"), note.get("url")))
                .containsExactly("Make-up class: " + PROBABILITY + " (time not given, see email)",
                        "/school/mailbox#mail-" + KEY));
    }

    @Test
    void aMakeUpByEmailKeepsTheCancellationByAnnouncement() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        announce(an, "MA026IU", "Cancel class on September 24", "", POSTED);
        data.save(data.emailChange(data.lecturerEmail(an, KEY, POSTED.plusHours(3), null), "MA026IU", "makeup",
                LocalDate.of(2026, 9, 26), LocalTime.of(8, 0), LocalTime.of(9, 40), "A2.401"));

        List<Map<String, Object>> week = classes();

        assertThat(week).extracting(e -> props(e).get("change")).containsExactly("cancelled", "makeup");
        assertThat(week.get(1).get("start")).isEqualTo("2026-09-26T08:00:00");
        assertThat(props(week.get(1)).get("room")).isEqualTo("A2.401");
    }

    @Test
    void anotherUsersEmailsNeverChangeMyClasses() throws Exception {
        data.course(an, "MA026IU", PROBABILITY, THU_24);
        AppUser binh = data.user("binh@example.com");
        data.save(data.emailChange(data.lecturerEmail(binh, KEY, POSTED, null), "MA026IU", "cancelled", SEPT_24, null,
                null, null));

        assertThat(classes()).extracting(e -> props(e).get("change")).containsOnlyNulls();
    }

    @Test
    void ownEventsAreInTheFeedWithALinkToEditThatDay() throws Exception {
        SchoolMyEvent selfStudy = data.myEvent(an, "Tự học buổi tối", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 12, 20),
                LocalTime.of(17, 0), LocalTime.of(19, 0), DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        selfStudy.skip(LocalDate.of(2026, 9, 29));
        db.flush();

        List<Map<String, Object>> mine = nextWeek().stream().filter(e -> "mine".equals(props(e).get("kind"))).toList();

        assertThat(mine).extracting(e -> e.get("start") + " " + e.get("end")).containsExactly(
                "2026-09-28T17:00:00 2026-09-28T19:00:00", "2026-09-30T17:00:00 2026-09-30T19:00:00");
        assertThat(mine.get(0)).containsEntry("title", "My event: Tự học buổi tối")
                .containsEntry("classNames", List.of("event-mine"))
                .containsEntry("url", "/school/events/" + selfStudy.getId() + "/edit?day=2026-09-28");
    }

    @Test
    void anotherStudentsEventsAreNotInMyFeed() throws Exception {
        data.myEvent(data.user("binh@example.com"), "Binh's plan", LocalDate.of(2026, 9, 29), null,
                LocalTime.of(17, 0), LocalTime.of(19, 0));

        assertThat(nextWeek()).noneMatch(e -> String.valueOf(e.get("title")).contains("Binh"));
    }

    @Test
    void anOwnEventClashingWithAClassHasAWarning() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY); // Tue 29/09 08:00-10:30
        data.myEvent(an, "Tự học sáng", LocalDate.of(2026, 9, 29), null, LocalTime.of(9, 0), LocalTime.of(10, 0));
        data.myEvent(an, "Ăn trưa", LocalDate.of(2026, 9, 29), null, LocalTime.of(10, 30), LocalTime.of(11, 30));

        List<Map<String, Object>> mine = nextWeek().stream().filter(e -> "mine".equals(props(e).get("kind"))).toList();

        assertThat(mine).extracting(e -> e.get("title") + " " + e.get("classNames")).containsExactly(
                "⚠ My event: Tự học sáng [event-mine, event-conflict]", "My event: Ăn trưa [event-mine]");
    }
}
