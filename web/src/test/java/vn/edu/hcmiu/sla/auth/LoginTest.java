package vn.edu.hcmiu.sla.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    User savedUser(String email, String passwordHash) {
        return users.save(new User(email, "An", passwordHash, LocalDateTime.of(2026, 9, 1, 0, 0)));
    }

    @Test
    void anAccountFromThePythonSiteCanLogIn() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(post("/auth/login").with(csrf())
                        .param("email", "  AN@example.com ").param("password", "correct-horse-8"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void anEmailPastedWithANonBreakingSpaceStillLogsIn() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(post("/auth/login").with(csrf())
                        .param("email", " an@example.com ").param("password", "correct-horse-8"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aVietnamesePasswordWorksEndToEnd() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT_VIETNAMESE);

        mvc.perform(post("/auth/login").with(csrf()).param("email", "an@example.com").param("password", "mật khẩu 123"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aWrongPasswordShowsTheSameMessageAsBefore() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(post("/auth/login").with(csrf()).param("email", "an@example.com").param("password", "wrong-password"))
                .andExpect(redirectedUrl("/auth/login?error"));
        mvc.perform(get("/auth/login").param("error", ""))
                .andExpect(content().string(containsString("Email or password is incorrect.")));
    }

    @Test
    void pagesNeedLogin() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void afterLoginYouGoBackToThePageYouAskedFor() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/school/timetable").session(session)).andExpect(redirectedUrl("/auth/login"));
        mvc.perform(post("/auth/login").session(session).with(csrf())
                        .param("email", "an@example.com").param("password", "correct-horse-8"))
                .andExpect(redirectedUrl("http://localhost/school/timetable?continue"));
    }

    @Test
    void logOutNeedsAFormWithItsSecurityCode() throws Exception {
        AppUser an = new AppUser(1, "an@example.com", "An", "x");

        mvc.perform(post("/auth/logout").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/auth/logout").with(user(an)).with(csrf())).andExpect(redirectedUrl("/auth/login"));
    }
}
