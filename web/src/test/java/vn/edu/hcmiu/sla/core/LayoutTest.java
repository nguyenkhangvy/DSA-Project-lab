package vn.edu.hcmiu.sla.core;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import vn.edu.hcmiu.sla.auth.AppUser;

class LayoutTest {

    static final AppUser AN = new AppUser(1, "an@example.com", "An", "x");

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class WithoutModules {

        @Autowired
        MockMvc mvc;

        @Test
        void theDashboardGreetsYouAndShowsEveryModuleAsComingSoon() throws Exception {
            mvc.perform(get("/").with(user(AN)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Hi, An")))
                    .andExpect(content().string(containsString("<span class=\"nav-soon\" title=\"Coming soon\">Expense</span>")))
                    .andExpect(content().string(containsString("Coming soon")))
                    .andExpect(content().string(containsString("href=\"/css/style.css\"")));
        }

        @Test
        void theMenuIsHiddenUntilYouLogIn() throws Exception {
            mvc.perform(get("/auth/login"))
                    .andExpect(content().string(not(containsString("Log out"))));
        }

        @Test
        void oneTimeMessagesAppearAtTheTop() throws Exception {
            mvc.perform(get("/auth/login").flashAttr("flashes", List.of(new Flash("error", "Something broke."))))
                    .andExpect(content().string(containsString("<p class=\"flash flash-error\">Something broke.</p>")));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(WithSchool.SchoolNav.class)
    class WithSchool {

        @TestConfiguration
        static class SchoolNav {
            @Bean
            NavModule schoolNav() {
                return new NavModule("School", "/school");
            }
        }

        @Autowired
        MockMvc mvc;

        @Test
        void aModuleThatRegistersItselfGetsALinkAndACard() throws Exception {
            mvc.perform(get("/").with(user(AN)))
                    .andExpect(content().string(containsString("<a href=\"/school\">School</a>")))
                    .andExpect(content().string(containsString("<a class=\"card module-card\" href=\"/school\">")))
                    .andExpect(content().string(containsString("title=\"Coming soon\">Health</span>")));
        }
    }

    @Test
    void flashHelpersCollectMessagesForTheNextPage() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        Flash.success(redirect, "Device renamed.");
        Flash.error(redirect, "A device name is required.");

        org.assertj.core.api.Assertions.assertThat(redirect.getFlashAttributes().get("flashes")).isEqualTo(List.of(
                new Flash("message", "Device renamed."), new Flash("error", "A device name is required.")));
    }
}
