package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/** School → Accounts: what each system's sync says, the laptops, and the link that opens the window on the laptop. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountsPageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    DeviceKeys deviceKeys;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    String page() throws Exception {
        return mvc.perform(get("/school/accounts").with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    static String main(String html) {
        return html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    }

    @Test
    void theAccountsPageOpensTheWindowOnTheLaptopAndAsksForNothing() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));

        String html = main(page());

        assertThat(html).contains("href=\"sla-agent:accounts\"", "Open Accounts on this laptop", "My laptop",
                "Start menu", "School-Life-Assistant.cmd");
        assertThat(html).doesNotContain("type=\"password\"").doesNotContain("<form").doesNotContain("<input");
    }

    @Test
    void eachSystemShowsItsStateOrThatItIsNotSetUp() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled", LocalDateTime.of(2026, 9, 28, 1, 0));
        run.finish(SchoolSyncRun.PARTIAL, LocalDateTime.of(2026, 9, 28, 1, 5), null, null);
        run.setSections(Map.of("timetable", Map.of("status", "ok"), "exams", Map.of("status", "ok"),
                "iupay", Map.of("status", "ok"),
                "blackboard", Map.of("status", "failed", "error_code", "bad_credentials", "error_message", "no")));
        db.persist(run);
        db.flush();

        String html = main(page());

        assertThat(html).contains("EduSoft:", "IUPay:", "synced",
                "Blackboard:", "paused: wrong username or password. Change it in Accounts.",
                "Outlook:", "not set up");
    }

    @Test
    void withoutALaptopThePageSaysWhereToStart() throws Exception {
        String html = main(page());

        assertThat(html).contains("Devices", "School-Life-Assistant.cmd").doesNotContain("sla-agent:accounts");
    }

    @Test
    void theAccountsTabComesAfterDevicesAndIsMarkedCurrent() throws Exception {
        String html = page();

        int devices = html.indexOf(">Devices</a>");
        int accounts = html.indexOf(">Accounts</a>");
        assertThat(devices).isPositive();
        assertThat(accounts).isGreaterThan(devices);
        assertThat(html.substring(html.lastIndexOf("<a", accounts), accounts))
                .contains("href=\"/school/accounts\"", "aria-current=\"page\"");
    }

    @Test
    void theAccountsPageNeedsLogin() throws Exception {
        mvc.perform(get("/school/accounts")).andExpect(redirectedUrl("/auth/login"));
    }
}
