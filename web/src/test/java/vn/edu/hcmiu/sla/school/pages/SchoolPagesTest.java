package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolChange;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * The School pages: Java twin of the page tests in tests/test_school_pages.py, test_school_schedule_pages.py,
 * test_school_blackboard_pages.py and test_school_class_change_pages.py. Times in the database are UTC;
 * pages show Vietnam time (UTC+7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class SchoolPagesTest {

    // Tue 29/09/2026 08:00-10:30 in Vietnam = 01:00-03:30 UTC
    static final Meeting WEB_TUESDAY = new Meeting(LocalDateTime.of(2026, 9, 29, 1, 0),
            LocalDateTime.of(2026, 9, 29, 3, 30), "A2.508");
    static final String BB = SchoolTestData.BB;

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    DeviceKeys deviceKeys;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url).with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The HTML from a heading to the next one. */
    static String section(String html, String heading) {
        int start = html.indexOf("<h2>" + heading + "</h2>");
        int end = html.indexOf("<h2>", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    /** The text in each element with this class, tags removed and spaces tidied. */
    static List<String> texts(String html, String cssClass) {
        List<String> texts = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"" + cssClass + "\"[^>]*>(.*?)</span>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            texts.add(m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip());
        }
        return texts;
    }

    /** The opening tag of the first link to this address. */
    static String linkTo(String html, String href) {
        Matcher m = Pattern.compile("<a [^>]*href=\"" + Pattern.quote(href) + "\"[^>]*>").matcher(html);
        return m.find() ? m.group() : "";
    }

    void change(AppUser who, String section, String kind, String summary) {
        SchoolSyncRun run = new SchoolSyncRun(who.id(), null, "manual", LocalDateTime.of(2026, 9, 28, 0, 0));
        db.persist(run);
        db.persist(new SchoolChange(who.id(), run.getId(), section, kind, summary, LocalDateTime.of(2026, 9, 28, 0, 0)));
        db.flush();
    }

    // ---- Login and status ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/school", "/school/", "/school/timetable", "/school/courses", "/school/exams",
        "/school/tuition", "/school/devices"})
    void schoolPagesNeedLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void schoolHomeShowsTheSetupHintBeforeAnyDevice() throws Exception {
        assertThat(page("/school")).contains("Devices page");
    }

    @Test
    void anotherUserLoggingInSeesTheirOwnStatus() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        AppUser binh = data.user("binh@example.com");

        assertThat(mvc.perform(get("/school").with(user(binh))).andReturn().getResponse().getContentAsString())
                .contains("Not set up yet");
    }

    @Test
    void schoolHomeShowsALinePerSystem() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled", LocalDateTime.of(2026, 9, 28, 1, 0));
        run.finish(SchoolSyncRun.PARTIAL, LocalDateTime.of(2026, 9, 28, 1, 5), null, null);
        run.setSections(Map.of("timetable", Map.of("status", "ok"), "exams", Map.of("status", "ok"),
                "tuition", Map.of("status", "ok"),
                "blackboard", Map.of("status", "failed", "error_code", "bad_credentials", "error_message", "rejected")));
        db.persist(run);
        db.flush();

        String html = page("/school");

        assertThat(html).contains("EduSoft:", "Blackboard:", "sla-agent setup --blackboard");
    }

    @Test
    void syncNowMakesTheNextCheckDue() throws Exception {
        String key = deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
        // A failed run 10 minutes ago, past the 5-minute gap.
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        run.finish(SchoolSyncRun.FAILED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(9), "network", "EduSoft timed out");
        db.persist(run);
        db.flush();

        mvc.perform(post("/school/sync-now").with(user(an)).with(csrf())).andExpect(redirectedUrl("/school"));

        mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.reason").value("requested"));
        assertThat(page("/school")).contains("waiting for your laptop");
    }

    @Test
    void syncNowNeedsTheFormsSecurityCode() throws Exception {
        mvc.perform(post("/school/sync-now").with(user(an))).andExpect(status().isForbidden());
    }

    // ---- Overview ------------------------------------------------------------------------

    @Test
    void schoolHomeShowsTodaysClassesAndWhatChanged() throws Exception {
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        change(an, "timetable", "changed", "IT093IU Web Application Development: room A2.307 → A2.508");
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30)); // Tue 07:30 in Vietnam

        String html = page("/school");

        assertThat(html).contains("Today", "Tue 29/09", "08:00–10:30", "room A2.307 → A2.508");
    }

    @Test
    void schoolHomeDoesNotShowOtherUsersChanges() throws Exception {
        change(data.user("binh@example.com"), "exams", "added", "Binh's secret exam");

        assertThat(page("/school")).doesNotContain("Binh");
    }

    @Test
    void overviewShowsThe3LatestAnnouncements() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        for (int i = 0; i < 5; i++) {
            data.announce(course, "Note " + i, "t", LocalDateTime.of(2026, 9, 20 + i, 2, 0));
        }
        data.save(course);

        String html = page("/school");

        assertThat(html).contains("Note 4", "Note 2").doesNotContain("Note 1");
    }

    @Test
    void toSubmitListsWhatIHaventSubmittedWithALink() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Final report", LocalDateTime.of(2026, 11, 30, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Missed quiz", LocalDateTime.of(2026, 9, 25, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Long gone", LocalDateTime.of(2026, 9, 1, 16, 59), "not_graded", null, null, null);
        data.assign(course, "Handed in", LocalDateTime.of(2026, 10, 3, 16, 59), "needs_grading", null, null, null);
        data.assign(course, "Marked", LocalDateTime.of(2026, 10, 4, 16, 59), "graded", 9.0, null, null);
        data.assign(course, "Excused", LocalDateTime.of(2026, 10, 5, 16, 59), "exempt", null, null, null);
        data.assign(course, "Attendance", null, "not_graded", null, null, null);
        data.save(course);
        SchoolBbCourse binhs = data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course");
        data.save(data.assign(binhs, "Binh's lab", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null));
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30));

        String toSubmit = section(page("/school"), "To submit");

        assertThat(texts(toSubmit, "item-title")).containsExactly("Missed quiz", "Lab 3", "Final report");
        String[] rows = toSubmit.split("<li ");
        assertThat(rows[1]).contains("Overdue");
        assertThat(rows[2]).doesNotContain("Overdue");
        assertThat(linkTo(toSubmit, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(toSubmit.split("Open assignment ↗", -1)).hasSize(4);
    }

    @Test
    void toSubmitSaysWhenNothingIsLeft() throws Exception {
        assertThat(page("/school")).contains("Nothing left to submit.");
    }

    @Test
    void theOverviewShowsTodaysOnlineClass() throws Exception {
        data.course(an, "MA026IU", "Probability, Statistic & Random Process",
                new Meeting(LocalDateTime.of(2026, 9, 24, 6, 15), LocalDateTime.of(2026, 9, 24, 8, 45), "A2.407"));
        SchoolBbCourse course = data.bbCourse(an, "MA026IU", "MA026IU on Blackboard");
        Integer courseId = data.save(data.announce(course, "ONLINE CLASS ON SEPTEMBER 24", "",
                LocalDateTime.of(2026, 9, 23, 16, 7)));
        clock.set(LocalDateTime.of(2026, 9, 24, 0, 30)); // Thu 07:30 in Vietnam

        String html = page("/school");

        Matcher item = Pattern.compile("<li class=\"item item-class item-changed\">(.*?)</li>", Pattern.DOTALL).matcher(html);
        assertThat(item.find()).isTrue();
        assertThat(item.group(1)).contains("<span class=\"badge badge-changed\">Online</span>");
        assertThat(item.group(1)).contains("<a href=\"/school/courses/" + courseId + "\">See announcement</a>");
    }

    @Test
    void theOverviewShowsAnEventJoinedForToday() throws Exception {
        db.persist(new SchoolMailJoined(an.id(), "a".repeat(64), LocalDate.of(2026, 9, 29), LocalTime.of(18, 0), null,
                "Talkshow B", "Hall A2", false, false, LocalDateTime.of(2026, 9, 28, 1, 0)));
        db.flush();
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 30)); // Tue 07:30 in Vietnam

        String html = page("/school");

        Matcher item = Pattern.compile("<li class=\"item item-event\">(.*?)</li>", Pattern.DOTALL).matcher(html);
        assertThat(item.find()).isTrue();
        assertThat(item.group(1)).contains("18:00–19:00", "<strong>Event:</strong>", "Talkshow B")
                .containsPattern("Hall A2 · <a\\s+href=\"/school/mailbox#mail-" + "a".repeat(64) + "\">See email</a>");
    }

    // ---- Timetable, exams, tuition --------------------------------------------------------

    @Test
    void theTimetablePageLoadsThePinnedCalendarScript() throws Exception {
        String html = page("/school/timetable");

        assertThat(html).contains("id=\"calendar\"", "data-feed=\"/school/api/calendar\"",
                "fullcalendar@6.1.21/index.global.min.js", "integrity=\"sha384-", "src=\"/js/timetable.js\"");
    }

    @Test
    void theTimetableLegendExplainsTheNewColours() throws Exception {
        assertThat(page("/school/timetable")).contains("Online / make-up", "Cancelled", "Joined event");
    }

    @Test
    void theExamsPageListsUpcomingExams() throws Exception {
        data.exam(an, "IT093IU", "Web Application Development", LocalDateTime.of(2099, 12, 12, 1, 0), "A1.309", "final");

        assertThat(page("/school/exams")).contains("Web Application Development", "12/12/2099", "08:00", "A1.309");
    }

    @Test
    void theExamsPageSaysWhenNothingIsPublished() throws Exception {
        assertThat(page("/school/exams")).contains("No exams published yet");
    }

    @Test
    void theTuitionPageShowsBalanceAndDueDate() throws Exception {
        data.tuition(an, 12_500_000, LocalDate.of(2026, 10, 15), "Chưa đóng");

        assertThat(page("/school/tuition")).contains("12,500,000", "15/10/2026", "Chưa đóng");
    }

    @Test
    void theTuitionPageLinksToTheIuPaymentSiteInANewTab() throws Exception {
        assertThat(linkTo(page("/school/tuition"), "https://iupay.hcmiu.edu.vn/search/dhqt"))
                .contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
    }

    @Test
    void theTuitionPageSaysWhenNothingIsSynced() throws Exception {
        assertThat(page("/school/tuition")).contains("No tuition information yet");
    }

    // ---- Courses ---------------------------------------------------------------------------

    @Test
    void theCoursesPageListsOnlyMyCourses() throws Exception {
        data.save(data.bbCourse(an, "IT093IU", "Web Application Development"));
        data.save(data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course"));

        assertThat(page("/school/courses")).contains("Web Application Development").doesNotContain("Binh");
    }

    @Test
    void someoneElsesCoursePageIs404() throws Exception {
        Integer other = data.save(data.bbCourse(data.user("binh@example.com"), "IT093IU", "Binh's Course"));

        mvc.perform(get("/school/courses/" + other).with(user(an))).andExpect(status().isNotFound());
    }

    @Test
    void theCoursePageShowsAllFourPartsWithEscapedText() throws Exception {
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.announce(course, "Heads up", "<script>alert(1)</script> No class Thursday", LocalDateTime.of(2026, 9, 28, 2, 0));
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "graded", 8.5, 10.0, "Good work");
        data.material(course, "Week 5 slides.pdf", "file", "Week 5", LocalDateTime.of(2026, 9, 28, 1, 0));
        Integer courseId = data.save(course);

        String html = page("/school/courses/" + courseId);

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
        assertThat(html).contains("Heads up", "Lab 3", "Fri 02/10 23:59", "8.5/10", "Good work", "Week 5 slides.pdf");
        assertThat(linkTo(html, BB)).contains("target=\"_blank\"", "rel=\"noopener noreferrer\"");
        assertThat(html.split("Open in Blackboard ↗", -1)).hasSize(5); // the course and its three items
    }

    @Test
    void aClassChangedByEmailLinksToTheEmailInMailbox() throws Exception {
        clock.set(LocalDateTime.of(2026, 9, 29, 0, 0)); // Tue 29/09 07:00 in Vietnam
        data.course(an, "IT093IU", "Web Application Development", WEB_TUESDAY);
        String key = "f".repeat(64);
        data.save(data.emailChange(data.lecturerEmail(an, key, LocalDateTime.of(2026, 9, 28, 2, 0), null), "IT093IU",
                "online", LocalDate.of(2026, 9, 29), null, null, null));

        String html = page("/school");

        assertThat(linkTo(html, "/school/mailbox#mail-" + key)).isNotEmpty();
        assertThat(html).contains(">See email</a>");
    }
}
