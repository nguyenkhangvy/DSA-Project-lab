package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;
import vn.edu.hcmiu.sla.school.model.SchoolMyEventRepository;

/** The event form: new, Check, Save, edit and Delete (docs/superpowers/specs/2026-09-30-my-events-design.md, 4.1). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class MyEventsPageTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    SchoolMyEventRepository events;

    SchoolTestData data;
    AppUser an;

    /** Web Application Development on Mon 05/10, Tue 06/10 and Wed 07/10, 17:15-19:45 in Vietnam. */
    @BeforeEach
    void anAccountWithEveningClasses() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        clock.set(NOW);
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 10, 5, 10, 15), LocalDateTime.of(2026, 10, 5, 12, 45), "A2.401"),
                new Meeting(LocalDateTime.of(2026, 10, 6, 10, 15), LocalDateTime.of(2026, 10, 6, 12, 45), "A2.401"),
                new Meeting(LocalDateTime.of(2026, 10, 7, 10, 15), LocalDateTime.of(2026, 10, 7, 12, 45), "A2.401"));
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(an))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    /**
     * The student's example: Tự học buổi tối, every week on Mon, Tue, Wed, 05/10-20/12, 17:00-19:00, with changes as
     * name/value pairs ("end", "16:00"; "weekdays", "1,2"). MockMvc's param() adds values, so fields are set once here.
     */
    static MockHttpServletRequestBuilder selfStudy(MockHttpServletRequestBuilder request, String action,
            String... changes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Tự học buổi tối");
        fields.put("place", "Library");
        fields.put("start", "17:00");
        fields.put("end", "19:00");
        fields.put("repeat", "weeks");
        fields.put("every", "1");
        fields.put("weekdays", "1,2,3");
        fields.put("firstDay", "2026-10-05");
        fields.put("lastDay", "2026-12-20");
        for (int i = 0; i < changes.length; i += 2) {
            fields.put(changes[i], changes[i + 1]);
        }
        request.with(csrf()).param("action", action);
        fields.forEach((name, value) -> request.param(name, name.equals("weekdays") ? value.split(",") : new String[] {value}));
        return request;
    }

    @SuppressWarnings("unchecked")
    static List<String> flashes(MvcResult result) {
        List<Flash> flashes = (List<Flash>) result.getFlashMap().get("flashes");
        return flashes == null ? List.of() : flashes.stream().map(Flash::text).toList();
    }

    SchoolMyEvent selfStudyEvent() {
        return data.myEvent(an, "Tự học buổi tối", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 20),
                LocalTime.of(17, 0), LocalTime.of(19, 0), DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
    }

    @Test
    void aNewEventStartsTodayOnce() throws Exception {
        String html = html(get("/school/events/new"));

        assertThat(html).contains("<h1>New event</h1>", "value=\"2026-09-28\"", "Check for conflicts", "Save")
                .containsPattern("value=\"once\"[^>]*checked")
                .containsPattern("name=\"weekdays\" value=\"1\"[^>]*checked")
                .doesNotContain("Delete");
    }

    @Test
    void checkListsTheClashingDaysAndSavesNothing() throws Exception {
        String html = html(selfStudy(post("/school/events/new"), "check"));

        assertThat(html).contains("⚠ 3 of 33 sessions clash",
                "Mon 05/10 17:00–19:00 ⚠ IT093IU Web Application Development (17:15–19:45)",
                "Wed 07/10 17:00–19:00 ⚠ IT093IU Web Application Development (17:15–19:45)",
                "value=\"Tự học buổi tối\"");
        assertThat(events.count()).isZero();
    }

    @Test
    void checkWithoutClashesSaysSo() throws Exception {
        String html = html(post("/school/events/new").with(csrf()).param("action", "check").param("title", "Gym")
                .param("start", "07:00").param("end", "08:00").param("repeat", "once").param("firstDay", "2026-10-08"));

        assertThat(html).contains("✓ No conflict in 1 session");
    }

    @Test
    void saveKeepsTheEventAndSaysWhichDaysClash() throws Exception {
        MvcResult result = mvc.perform(selfStudy(post("/school/events/new"), "save").with(user(an)))
                .andExpect(redirectedUrl("/school/timetable")).andReturn();

        assertThat(flashes(result)).containsExactly(
                "Saved \"Tự học buổi tối\". 3 of 33 sessions clash: Mon 05/10, Tue 06/10, Wed 07/10.");
        SchoolMyEvent saved = events.findAll().get(0);
        assertThat(List.of(saved.getUserId(), saved.getRepeatKind(), saved.getWeekdays(), saved.getPlace()))
                .containsExactly(an.id(), "weeks", "1,2,3", "Library");
    }

    @Test
    void mistakesAreShownUnderTheirFieldAndNothingIsSaved() throws Exception {
        String html = html(selfStudy(post("/school/events/new"), "save", "end", "16:00"));

        assertThat(html).contains("The end must be after the start.", "value=\"Tự học buổi tối\"");
        assertThat(events.count()).isZero();
    }

    @Test
    void theEditPageShowsTheEventAsSaved() throws Exception {
        SchoolMyEvent event = selfStudyEvent();

        String html = html(get("/school/events/" + event.getId() + "/edit"));

        assertThat(html).contains("<h1>Edit event</h1>", "value=\"Tự học buổi tối\"", "value=\"17:00\"",
                "value=\"2026-12-20\"", "Delete").containsPattern("value=\"weeks\"[^>]*checked")
                .containsPattern("name=\"weekdays\" value=\"3\"[^>]*checked");
    }

    @Test
    void savingAnEditChangesTheSeriesAndKeepsSkippedDaysStillOnIt() throws Exception {
        SchoolMyEvent event = selfStudyEvent();
        event.skip(LocalDate.of(2026, 10, 6)); // a Tuesday
        event.skip(LocalDate.of(2026, 10, 7)); // a Wednesday
        db.flush();

        MvcResult result = mvc.perform(selfStudy(post("/school/events/" + event.getId() + "/edit"), "save",
                "weekdays", "1,2").with(user(an))).andExpect(redirectedUrl("/school/timetable")).andReturn();

        // Mon and Tue now: 22 days, 21 with Tue 06/10 still skipped; only Mon 05/10 clashes.
        assertThat(flashes(result)).containsExactly(
                "Saved \"Tự học buổi tối\". 1 of 21 sessions clash: Mon 05/10.");
        db.flush();
        db.clear();
        assertThat(events.findOfUser(event.getId(), an.id()).orElseThrow().rule().skipped())
                .containsExactly(LocalDate.of(2026, 10, 6));
    }

    @Test
    void checkOnAnEditCountsTheSkippedDays() throws Exception {
        SchoolMyEvent event = selfStudyEvent();
        event.skip(LocalDate.of(2026, 10, 5));
        db.flush();

        String html = html(selfStudy(post("/school/events/" + event.getId() + "/edit"), "check"));

        assertThat(html).contains("⚠ 2 of 32 sessions clash").doesNotContain("Mon 05/10 17:00");
    }

    @Test
    void deleteRemovesTheEventAndItsSkippedDays() throws Exception {
        SchoolMyEvent event = selfStudyEvent();
        event.skip(LocalDate.of(2026, 10, 6));
        db.flush();

        MvcResult result = mvc.perform(post("/school/events/" + event.getId() + "/delete").with(csrf()).with(user(an)))
                .andExpect(redirectedUrl("/school/timetable")).andReturn();

        assertThat(flashes(result)).containsExactly("Deleted \"Tự học buổi tối\".");
        assertThat(events.count()).isZero();
    }

    @Test
    void someoneElsesEventIs404() throws Exception {
        SchoolMyEvent binhs = data.myEvent(data.user("binh@example.com"), "Binh's plan", LocalDate.of(2026, 10, 8), null,
                LocalTime.of(6, 0), LocalTime.of(7, 0));
        String edit = "/school/events/" + binhs.getId() + "/edit";

        mvc.perform(get(edit).with(user(an))).andExpect(status().isNotFound());
        mvc.perform(selfStudy(post(edit), "save").with(user(an))).andExpect(status().isNotFound());
        mvc.perform(post("/school/events/" + binhs.getId() + "/delete").with(csrf()).with(user(an)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/school/events/999999/edit").with(user(an))).andExpect(status().isNotFound());
    }

    @Test
    void postingNeedsTheFormsSecurityCode() throws Exception {
        mvc.perform(post("/school/events/new").param("title", "x").with(user(an))).andExpect(status().isForbidden());
    }

    @Test
    void fieldsTheFormHidesStayHiddenWhateverTheirLayout() throws Exception {
        // event-form.js hides "Last day" and the weekdays with the hidden attribute; the site's own display rules
        // (flex labels) would show them again without this rule.
        assertThat(mvc.perform(get("/css/style.css")).andReturn().getResponse().getContentAsString())
                .contains("[hidden] { display: none !important; }");
    }

    @Test
    void theEventPagesNeedLogin() throws Exception {
        mvc.perform(get("/school/events/new")).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void theEditPageForOneDayOffersToSkipIt() throws Exception {
        SchoolMyEvent event = selfStudyEvent();

        String html = html(get("/school/events/" + event.getId() + "/edit").param("day", "2026-10-06"));

        assertThat(html).contains("Skip Tue 06/10 only").doesNotContain("is skipped");
    }

    @Test
    void skippingADayAndBringingItBack() throws Exception {
        SchoolMyEvent event = selfStudyEvent();
        String base = "/school/events/" + event.getId();

        MvcResult skipped = mvc.perform(post(base + "/skip").param("day", "2026-10-06").with(csrf()).with(user(an)))
                .andExpect(redirectedUrl(base + "/edit?day=2026-10-06")).andReturn();

        assertThat(flashes(skipped)).containsExactly("Tue 06/10 is skipped.");
        assertThat(html(get(base + "/edit").param("day", "2026-10-06")))
                .contains("Tue 06/10 is skipped", "Skipped days", "Undo").doesNotContain("Skip Tue 06/10 only");

        MvcResult back = mvc.perform(post(base + "/unskip").param("day", "2026-10-06").with(csrf()).with(user(an)))
                .andExpect(redirectedUrl(base + "/edit?day=2026-10-06")).andReturn();

        assertThat(flashes(back)).containsExactly("Tue 06/10 is back.");
        db.flush();
        db.clear();
        assertThat(events.findOfUser(event.getId(), an.id()).orElseThrow().rule().skipped()).isEmpty();
    }

    @Test
    void aDayThatIsNotOneOfTheEventsDaysCantBeSkipped() throws Exception {
        SchoolMyEvent event = selfStudyEvent();
        String base = "/school/events/" + event.getId();

        mvc.perform(post(base + "/skip").param("day", "2026-10-08").with(csrf()).with(user(an)))
                .andExpect(status().isBadRequest()); // a Thursday
        mvc.perform(post(base + "/skip").param("day", "8/10").with(csrf()).with(user(an)))
                .andExpect(status().isBadRequest());
        assertThat(html(get(base + "/edit").param("day", "2026-10-08"))).doesNotContain("Skip Thu");
    }

    @Test
    void someoneElsesEventCantBeSkipped() throws Exception {
        SchoolMyEvent binhs = data.myEvent(data.user("binh@example.com"), "Binh's plan", LocalDate.of(2026, 10, 8), null,
                LocalTime.of(6, 0), LocalTime.of(7, 0));

        mvc.perform(post("/school/events/" + binhs.getId() + "/skip").param("day", "2026-10-08").with(csrf())
                .with(user(an))).andExpect(status().isNotFound());
        mvc.perform(post("/school/events/" + binhs.getId() + "/unskip").param("day", "2026-10-08").with(csrf())
                .with(user(an))).andExpect(status().isNotFound());
    }
}
