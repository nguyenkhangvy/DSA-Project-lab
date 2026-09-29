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
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;

/** The Mailbox tab: boxes, cards, Done, Move to…, and what it says when Outlook has a problem. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class MailboxPageTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam
    static final String TCL = "a".repeat(64);
    static final String LAB_QUESTION = "b".repeat(64);
    static final String LAB_REPLY = "c".repeat(64);
    static final String INVOICE = "d".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    SchoolMailChoiceRepository choices;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
        clock.set(NOW);
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    void mail(AppUser who, String key, String thread, int hoursAgo, String sender, String subject,
            List<String> categories, boolean lecturer, List<LocalDate> dates, boolean sorted) {
        db.persist(new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), thread,
                NOW.minusHours(hoursAgo), sender, sender.toLowerCase().replace(' ', '.') + "@hcmiu.edu.vn", subject,
                categories, lecturer, dates, sorted, null));
        db.flush();
    }

    void inbox(AppUser who) {
        mail(who, TCL, null, 5, "P.CTSV [OSS]", "[THƯ MỜI] Workshop “Từ giảng đường tới công sở”",
                List.of("event", "training_points"), false, List.of(LocalDate.of(2026, 9, 30)), true);
        mail(who, LAB_QUESTION, "T1", 30, "Vo Minh Khoa", "Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), true);
        mail(who, LAB_REPLY, "T1", 2, "Vo Minh Khoa", "Re: Slide bài tập bị thiếu số trang", List.of("class"), true,
                List.of(), true);
        mail(who, INVOICE, null, 8, "M-Invoice", "[ M-Invoice ] TB: Xuất hóa đơn điện tử số 80652", List.of("money"),
                false, List.of(), false);
        db.persist(new SchoolMailStatus(who.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
        db.flush();
    }

    void run(AppUser who, Map<String, Map<String, String>> sections, String status) {
        SchoolSyncRun run = new SchoolSyncRun(who.id(), null, "scheduled", NOW.minusMinutes(10));
        run.finish(status, NOW.minusMinutes(9), null, null);
        run.setSections(sections);
        db.persist(run);
        db.flush();
    }

    String page() throws Exception {
        return mvc.perform(get("/school/mailbox").with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The HTML of one box. */
    static String box(String html, String id) {
        int start = html.indexOf("id=\"box-" + id + "\"");
        int end = html.indexOf("id=\"box-", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    @Test
    void theBoxesShowEachCardInItsPlace() throws Exception {
        inbox(an);

        String html = page();

        assertThat(html.indexOf("From lecturers")).isLessThan(html.indexOf("School tasks"));
        assertThat(box(html, "lecturers")).contains("Re: Slide bài tập bị thiếu số trang").contains("2 messages")
                .contains("<span class=\"count\">(1)</span>").doesNotContain("id=\"mail-" + LAB_QUESTION + "\"");
        assertThat(box(html, "money")).contains("Xuất hóa đơn điện tử")
                .contains("title=\"Couldn't sort this email automatically. Use Move to…\">Not sorted</span>");
        assertThat(box(html, "events")).contains("<span class=\"cat cat-event\">Event</span>")
                .contains("<span class=\"cat cat-training_points\">★ Points</span>").contains("Next: Wed 30/09")
                .doesNotContain("lose points");
        assertThat(box(html, "tasks")).contains("Nothing here.");
        assertThat(html).contains("Mail read from Outlook Mon 28/09 07:00").doesNotContain("Show all")
                .doesNotContain("Open in Outlook");
    }

    static final String EDGE_ON_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like "
            + "Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0";
    static final String CHROME_ON_ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/140.0.0.0 Mobile Safari/537.36";

    String pageIn(String browser) throws Exception {
        return mvc.perform(get("/school/mailbox").with(user(an)).header("User-Agent", browser))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachCardOpensTheExactEmailOnTheLaptopOrOutlookOnTheWeb() throws Exception {
        inbox(an);

        String card = box(pageIn(EDGE_ON_WINDOWS), "events");

        assertThat(card).containsPattern("class=\"mail-subject\" href=\"sla-mail:00A1AAAA\"[^>]*"
                + "data-opened=\"/school/mailbox/" + TCL + "/opened\"");
        assertThat(card).contains("href=\"https://outlook.office.com/mail/\" target=\"_blank\" rel=\"noopener noreferrer\"");
        assertThat(card).contains("class=\"done-button\">✓ Done</button>");
    }

    @Test
    void onAPhoneTheSubjectCanNotOpenTheEmailSoItRecordsNothing() throws Exception {
        inbox(an);

        String card = box(pageIn(CHROME_ON_ANDROID), "events");

        assertThat(card).containsPattern("class=\"mail-subject\" href=\"sla-mail:00A1AAAA\"[^>]*>")
                .doesNotContainPattern("class=\"mail-subject\"[^>]*data-opened");
        assertThat(card).containsPattern("href=\"https://outlook.office.com/mail/\"[^>]*"
                + "data-opened=\"/school/mailbox/" + TCL + "/opened\"");
    }

    @Test
    void beforeOutlookIsConnectedThePageSaysHow() throws Exception {
        assertThat(page()).contains("Outlook isn't connected yet.").contains("sla-agent setup --outlook")
                .doesNotContain("From lecturers");
    }

    @Test
    void anOutlookProblemIsShownAtTheTop() throws Exception {
        inbox(an);
        run(an, Map.of("timetable", Map.of("status", "ok"), "outlook",
                Map.of("status", "failed", "error_code", "outlook_blocked", "error_message", "x")), "partial");

        String html = page();

        assertThat(html).contains("Sync failed: Outlook didn&#39;t let the agent read your mail")
                .contains("The agent never clicks past it.");
        assertThat(html.indexOf("role=\"alert\"")).isLessThan(html.indexOf("From lecturers"));
    }

    @Test
    void anOfflineOutlookGetsAYellowNote() throws Exception {
        db.persist(new SchoolMailStatus(an.id(), LocalDate.of(2026, 8, 1), false, NOW));
        db.flush();

        assertThat(page()).contains("Outlook was offline when your laptop last read it");
    }

    @Test
    void doneMovesACardToTheDoneListAndUndoBringsItBack() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + INVOICE + "/done").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + INVOICE));
        assertThat(box(page(), "money")).doesNotContain("Xuất hóa đơn");
        assertThat(box(page(), "done")).contains("Done (1)").contains("Xuất hóa đơn").contains(">Undo<");

        mvc.perform(post("/school/mailbox/" + INVOICE + "/undone").with(user(an)).with(csrf()));
        assertThat(box(page(), "money")).contains("Xuất hóa đơn");
    }

    String open(String key) throws Exception {
        return mvc.perform(post("/school/mailbox/" + key + "/opened").with(user(an)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void openingAnEmailMarksItDoneWhenAutoDoneIsOn() throws Exception {
        inbox(an);

        assertThat(open(INVOICE)).isEqualTo("{\"done\":true}");

        assertThat(box(page(), "money")).doesNotContain("Xuất hóa đơn");
        assertThat(box(page(), "done")).contains("Done (1)").contains("mail-row is-opened is-done");
    }

    @Test
    void openingAnEventStillAheadOnlyMarksItOpened() throws Exception {
        inbox(an);

        assertThat(open(TCL)).isEqualTo("{\"done\":false}");

        assertThat(box(page(), "events")).containsPattern("id=\"mail-" + TCL + "\"\\s+class=\"mail-row is-opened\"");
    }

    @Test
    void withAutoDoneOffOpeningOnlyMarksItOpened() throws Exception {
        inbox(an);
        mvc.perform(post("/school/mailbox/settings").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/mailbox"));

        assertThat(open(INVOICE)).isEqualTo("{\"done\":false}");

        String html = page();
        assertThat(box(html, "money")).contains("class=\"mail-row is-opened\"");
        assertThat(html).containsPattern("id=\"auto-done\" name=\"autoDone\" value=\"true\">");
    }

    @Test
    void autoDoneIsOnUntilTheStudentTurnsItOff() throws Exception {
        inbox(an);

        assertThat(page()).contains("id=\"auto-done\" name=\"autoDone\" value=\"true\" checked=\"checked\"");

        mvc.perform(post("/school/mailbox/settings").with(user(an)).with(csrf()).param("autoDone", "true"));
        assertThat(page()).contains("checked=\"checked\"");
    }

    @Test
    void moveToPutsACardWhereTheStudentChoseForEveryEmailOfTheThread() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + LAB_REPLY + "/edit").with(user(an)).with(csrf())
                        .param("category1", "school_task").param("category2", "").param("fromLecturer", "false"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + LAB_REPLY));

        assertThat(box(page(), "tasks")).contains("Re: Slide bài tập").contains(">Task</span>");
        assertThat(choices.findByUserId(an.id())).extracting(SchoolMailChoice::getMailKey)
                .containsExactlyInAnyOrder(LAB_QUESTION, LAB_REPLY);
    }

    /** An event on `day` whose registration closes on `registerBy`. */
    void event(AppUser who, String key, String subject, LocalDate day, LocalDate registerBy) {
        SchoolMail mail = new SchoolMail(who.id(), key, "00A1" + key.substring(0, 4).toUpperCase(), null,
                NOW.minusHours(20), "P.CTSV [OSS]", "oss@hcmiu.edu.vn", subject, List.of("event", "training_points"),
                false, List.of(day), true, null);
        mail.setRegisterBy(registerBy);
        db.persist(mail);
        db.flush();
    }

    @Test
    void theRowSaysUntilWhenRegistrationIsOpen() throws Exception {
        inbox(an);
        event(an, "e".repeat(64), "Lễ Bế mạc HTSV", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 29));

        assertThat(box(page(), "events")).contains("<span class=\"tag tag-register\">Register by Tue 29/09</span>");
    }

    @Test
    void anEventWhoseRegistrationClosedGoesToPastUnlessJoined() throws Exception {
        inbox(an);
        String closing = "e".repeat(64);
        event(an, closing, "Lễ Bế mạc HTSV", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 22));

        String events = box(page(), "events");
        assertThat(events.substring(0, events.indexOf("<details"))).doesNotContain("Bế mạc");
        assertThat(events.substring(events.indexOf("<details"))).contains("Lễ Bế mạc HTSV")
                .contains(">Registration closed</span>");

        db.persist(new SchoolMailJoined(an.id(), closing, LocalDate.of(2026, 9, 30), java.time.LocalTime.of(9, 45),
                null, "Lễ Bế mạc HTSV", null, true, false, NOW));
        db.flush();
        events = box(page(), "events");
        assertThat(events.substring(0, events.indexOf("<details") < 0 ? events.length() : events.indexOf("<details")))
                .contains("Lễ Bế mạc HTSV").doesNotContain("Registration closed");
    }

    @Test
    void anUnsortedEmailMovedByTheStudentIsNoLongerMarkedNotSorted() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + INVOICE + "/edit").with(user(an)).with(csrf())
                .param("category1", "money").param("category2", ""));

        assertThat(box(page(), "money")).contains("Xuất hóa đơn").doesNotContain("Not sorted");
    }

    @Test
    void theMoveToPageStartsFromTheCardsCategories() throws Exception {
        inbox(an);

        String html = mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("<option value=\"event\" selected=\"selected\">Event</option>");
        assertThat(html).containsPattern(
                "id=\"category2\"[\\s\\S]*<option value=\"training_points\" selected=\"selected\">Training points</option>");
    }

    @Test
    void badCategoriesAreRefusedWithAMessage() throws Exception {
        inbox(an);

        String html = mvc.perform(post("/school/mailbox/" + TCL + "/edit").with(user(an)).with(csrf())
                        .param("category1", "event").param("category2", "event"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MailboxController.CATEGORY_ERROR);
        assertThat(choices.findByUserId(an.id())).isEmpty();
    }

    @Test
    void backToAutomaticUndoesMoveTo() throws Exception {
        inbox(an);
        mvc.perform(post("/school/mailbox/" + TCL + "/edit").with(user(an)).with(csrf())
                .param("category1", "promotion").param("category2", ""));

        mvc.perform(post("/school/mailbox/" + TCL + "/automatic").with(user(an)).with(csrf()));

        assertThat(box(page(), "events")).contains("Workshop");
    }

    @Test
    void someoneElsesCardIs404() throws Exception {
        AppUser binh = data.user("binh@example.com");
        inbox(binh);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/school/mailbox/" + TCL + "/edit").with(user(an))).andExpect(status().isNotFound());
        mvc.perform(post("/school/mailbox/" + TCL + "/opened").with(user(an)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(page()).doesNotContain("Workshop");
    }

    @Test
    void formsNeedTheCsrfToken() throws Exception {
        inbox(an);

        mvc.perform(post("/school/mailbox/" + TCL + "/done").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/" + TCL + "/opened").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/school/mailbox/settings").with(user(an))).andExpect(status().isForbidden());
    }

    @Test
    void theSchoolMenuHasMailboxAfterOverview() throws Exception {
        String menu = page();
        menu = menu.substring(menu.indexOf("class=\"subnav\""));

        assertThat(menu.indexOf(">Overview<")).isLessThan(menu.indexOf(">Mailbox<"));
        assertThat(menu.indexOf(">Mailbox<")).isLessThan(menu.indexOf(">Timetable<"));
    }
}
