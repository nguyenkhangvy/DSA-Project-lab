package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSession;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;

/**
 * Join…: an event's sessions with their Conflict / No conflict marks, joining some, adding one by hand, leaving
 * (spec 2026-09-28-mailbox-events-design.md, sections 4.2, 4.5 and 4.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class JoinPageTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam
    static final LocalDate TUE = LocalDate.of(2026, 9, 29);
    static final String TALK = "a".repeat(64);
    static final String INVOICE = "b".repeat(64);
    static final String NOTICE = "c".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    SchoolMailJoinedRepository joined;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anInboxAndATimetable() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        clock.set(NOW);
        inbox(an);
        // Tue 29/09 13:00-15:30
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 9, 29, 6, 0), LocalDateTime.of(2026, 9, 29, 8, 30), "A2.401"));
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    SchoolMail mail(AppUser who, String key, String subject, List<String> categories) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), null, NOW.minusDays(1),
                "P.CTSV [OSS]", "oss@hcmiu.edu.vn", subject, categories, false, List.of(), true, null);
        db.persist(mail);
        return mail;
    }

    void inbox(AppUser who) {
        SchoolMail talk = mail(who, TALK, "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                List.of("event", "training_points"));
        talk.getSessions().add(new SchoolMailSession(talk, TUE, LocalTime.of(14, 0), LocalTime.of(16, 0)));
        talk.getSessions().add(new SchoolMailSession(talk, TUE.plusDays(2), LocalTime.of(13, 30), null));
        mail(who, INVOICE, "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", List.of("money"));
        SchoolMail notice = mail(who, NOTICE, "Thông báo họp lớp", List.of());
        notice.getSessions().add(new SchoolMailSession(notice, TUE.plusDays(4), LocalTime.of(9, 0), null));
        db.persist(new SchoolMailStatus(who.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
        db.flush();
    }

    String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(an))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    MockHttpServletRequestBuilder save(String key) {
        return post("/school/mailbox/" + key + "/join").with(user(an)).with(csrf());
    }

    List<String> saved() {
        return joined.findAll().stream().map(j -> j.getDay() + " " + j.getStart() + "-" + j.getEnd() + " "
                + j.getPlace() + " " + j.isTrainingPoints() + " " + j.isByHand()).toList();
    }

    @Test
    void theJoinPageMarksEachSession() throws Exception {
        String html = html(get("/school/mailbox/" + TALK + "/join"));

        assertThat(html).contains("<legend>Sessions</legend>").contains("Tue 29/09 14:00–16:00")
                .contains("⚠ Conflict: Web Application Development").contains("Thu 01/10 from 13:30")
                .contains("✓ No conflict").contains("value=\"2026-09-29T14:00\"").doesNotContain("Leave event");
    }

    @Test
    void theMailboxShowsEachEventsSessionsAndAJoinLink() throws Exception {
        String html = html(get("/school/mailbox"));

        String events = MailboxPageTest.box(html, "events");
        assertThat(events).contains("Tue 29/09 14:00–16:00").contains("⚠ Conflict: Web Application Development")
                .contains("href=\"/school/mailbox/" + TALK + "/join\">Join…</a>");
        assertThat(MailboxPageTest.box(html, "money")).doesNotContain("Join…");
    }

    @Test
    void joiningSavesTheTickedSessionsAndShowsThemAsJoined() throws Exception {
        mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30").param("place", "Hall A2"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-10-01 13:30-null Hall A2 true false");
        SchoolMailJoined row = joined.findAll().get(0);
        assertThat(row.getTitle()).isEqualTo("[THƯ MỜI] Workshop “Từ giảng đường tới công sở”");
        assertThat(MailboxPageTest.box(html(get("/school/mailbox")), "events"))
                .contains("<span class=\"mark mark-joined\">Joined</span>");
        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("value=\"2026-10-01T13:30\" checked=\"checked\"")
                .contains("value=\"Hall A2\"").contains("Leave event");
    }

    @Test
    void aSessionCanBeAddedByHand() throws Exception {
        mvc.perform(save(TALK).param("day", "2026-10-05").param("start", "18:00").param("end", "20:00"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-10-05 18:00-20:00 null true true");
        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("Mon 05/10 18:00–20:00")
                .contains("added by you");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-09-27 | 18:00 | ''    | ''  | " + MailboxController.PAST_DAY,
            "2026-10-05 | ''    | ''    | ''  | " + MailboxController.NO_START,
            "2026-10-05 | 18:00 | 17:00 | ''  | " + MailboxController.END_BEFORE_START,
            "''         | ''    | ''    | 101 | " + MailboxController.PLACE_TOO_LONG,
    })
    void badSessionsAreRefusedWithAMessage(String day, String start, String end, String place, String message)
            throws Exception {
        String html = mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30").param("day", day)
                        .param("start", start).param("end", end).param("place", place.isEmpty() ? "" : "x".repeat(101)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(message.replace("'", "&#39;"));
        assertThat(saved()).isEmpty();
    }

    @Test
    void oneEmailHasAtMostTenJoinedSessions() throws Exception {
        MockHttpServletRequestBuilder request = save(TALK).param("sessions", "2026-09-29T14:00", "2026-10-01T13:30");
        for (int day = 5; day < 14; day++) {
            db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 10, day), LocalTime.of(18, 0), null,
                    "Workshop", null, true, true, NOW));
            request.param("sessions", "2026-10-" + String.format("%02d", day) + "T18:00");
        }
        db.flush();

        String html = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MailboxController.TOO_MANY);
        assertThat(joined.count()).isEqualTo(9);
    }

    @Test
    void theTenSessionLimitCountsSessionsAlreadyOver() throws Exception {
        // 3 over + 2 found + 6 added by hand = 11
        MockHttpServletRequestBuilder request = save(TALK).param("sessions", "2026-09-29T14:00", "2026-10-01T13:30");
        for (int day = 20; day < 23; day++) {
            db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 9, day), LocalTime.of(18, 0), null,
                    "Workshop", null, true, true, NOW));
        }
        for (int day = 5; day < 11; day++) {
            db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 10, day), LocalTime.of(18, 0), null,
                    "Workshop", null, true, true, NOW));
            request.param("sessions", "2026-10-" + String.format("%02d", day) + "T18:00");
        }
        db.flush();

        String html = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MailboxController.TOO_MANY);
        assertThat(joined.count()).isEqualTo(9);
    }

    @Test
    void leavingRemovesTheSessionsAheadButKeepsThoseOver() throws Exception {
        db.persist(new SchoolMailJoined(an.id(), TALK, LocalDate.of(2026, 9, 27), LocalTime.of(18, 0), null,
                "Workshop", null, true, true, NOW));
        db.persist(new SchoolMailJoined(an.id(), TALK, TUE.plusDays(2), LocalTime.of(13, 30), null, "Workshop", null,
                true, false, NOW));
        db.flush();

        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-09-27 18:00-null null true true");
    }

    @Test
    void anEventMovedAwayAfterJoiningCanStillBeLeft() throws Exception {
        mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30"));
        mvc.perform(post("/school/mailbox/" + TALK + "/edit").with(user(an)).with(csrf())
                .param("category1", "money").param("category2", ""));

        assertThat(MailboxPageTest.box(html(get("/school/mailbox")), "money"))
                .contains("href=\"/school/mailbox/" + TALK + "/join\">Join…</a>")
                .contains("<span class=\"mark mark-joined\">Joined</span>");
        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("Thu 01/10 from 13:30")
                .contains("Leave event");

        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));
        assertThat(saved()).isEmpty();
    }

    @Test
    void aJoinedEventWhoseEmailIsGoneCanStillBeLeft() throws Exception {
        String gone = "f".repeat(64);
        db.persist(new SchoolMailJoined(an.id(), gone, LocalDate.of(2026, 9, 27), LocalTime.of(18, 0), null,
                "Hội thao IU", null, true, false, NOW));
        db.persist(new SchoolMailJoined(an.id(), gone, TUE.plusDays(3), LocalTime.of(18, 0), null, "Hội thao IU",
                "Sân A", true, false, NOW));
        db.flush();

        String html = html(get("/school/mailbox"));
        assertThat(html).contains("id=\"box-gone\"");
        assertThat(MailboxPageTest.box(html, "gone")).contains("id=\"mail-" + gone + "\"").contains("Hội thao IU")
                .contains("Fri 02/10 from 18:00").doesNotContain("Sun 27/09")
                .contains("action=\"/school/mailbox/" + gone + "/leave\"");
        AppUser binh = data.user("binh@example.com");
        mvc.perform(post("/school/mailbox/" + gone + "/leave").with(user(binh)).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/school/mailbox/" + gone + "/leave").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox"));

        assertThat(saved()).containsExactly("2026-09-27 18:00-null null true false");
        assertThat(html(get("/school/mailbox"))).doesNotContain("Hội thao IU");
    }

    @Test
    void anEmailMovedToEventSuggestsTheTimesFoundInIt() throws Exception {
        mvc.perform(post("/school/mailbox/" + NOTICE + "/edit").with(user(an)).with(csrf())
                .param("category1", "event").param("category2", ""));

        String html = html(get("/school/mailbox/" + NOTICE + "/join"));

        assertThat(html).contains("<legend>Found in this email</legend>").contains("Sat 03/10 from 09:00")
                .doesNotContain("checked=\"checked\"");
    }

    @Test
    void trainingPointsFollowMoveTo() throws Exception {
        mvc.perform(post("/school/mailbox/" + TALK + "/edit").with(user(an)).with(csrf())
                .param("category1", "event").param("category2", ""));

        mvc.perform(save(TALK).param("sessions", "2026-10-01T13:30"));

        assertThat(saved()).containsExactly("2026-10-01 13:30-null null false false");
    }

    @Test
    void onlyEventsAndSchoolTasksCanBeJoined() throws Exception {
        mvc.perform(get("/school/mailbox/" + INVOICE + "/join").with(user(an))).andExpect(status().isNotFound());
        mvc.perform(save(INVOICE).param("day", "2026-10-05").param("start", "18:00")).andExpect(status().isNotFound());
    }

    @Test
    void someoneElsesEventIs404() throws Exception {
        AppUser binh = data.user("binh@example.com");

        mvc.perform(get("/school/mailbox/" + TALK + "/join").with(user(binh))).andExpect(status().isNotFound());
        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(binh)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void joiningAndLeavingNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/school/mailbox/" + TALK + "/join").with(user(an)).param("sessions", "2026-10-01T13:30"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/" + TALK + "/leave").with(user(an))).andExpect(status().isForbidden());
        assertThat(saved()).isEmpty();
    }

    @Test
    void anOwnEventIsAConflictForAnEventsSession() throws Exception {
        // Thu 01/10 from 13:30 (the email gives no end) against the student's own 13:00-14:00.
        data.myEvent(an, "Tự học", TUE.plusDays(2), null, LocalTime.of(13, 0), LocalTime.of(14, 0));

        assertThat(html(get("/school/mailbox/" + TALK + "/join"))).contains("⚠ Conflict: My event: Tự học");
    }
}
