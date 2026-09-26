package vn.edu.hcmiu.sla.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    MockHttpServletRequestBuilder register(String email, String name, String password, String confirm) {
        return post("/auth/register").with(csrf())
                .param("email", email).param("displayName", name)
                .param("password", password).param("confirm", confirm);
    }

    User savedUser(String email, String passwordHash) {
        return users.save(new User(email, "An", passwordHash, LocalDateTime.of(2026, 9, 1, 0, 0)));
    }

    @Test
    void registeringLogsYouInAndSavesAWerkzeugStylePassword() throws Exception {
        MvcResult result = mvc.perform(register("  An@Example.COM ", " An ", "correct-horse-8", "correct-horse-8"))
                .andExpect(redirectedUrl("/"))
                .andReturn();

        User user = users.findByEmail("an@example.com").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("An");
        assertThat(user.getPasswordHash()).startsWith("scrypt:32768:8:1$");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hi, An")));
    }

    @Test
    void anEmailThatIsAlreadyRegisteredIsRefused() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(register("AN@example.com", "An again", "correct-horse-8", "correct-horse-8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This email is already registered.")));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void registerChecksEveryField() throws Exception {
        mvc.perform(register("not-an-email", "  ", "short", "different"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invalid email address.")))
                .andExpect(content().string(containsString("This field is required.")))
                .andExpect(content().string(containsString("Field must be between 8 and 128 characters long.")))
                .andExpect(content().string(containsString("Passwords don&#39;t match.")));
        assertThat(users.count()).isZero();
    }

    @Test
    void tooLongFieldsGetAFormMessageNotADatabaseError() throws Exception {
        mvc.perform(register("a".repeat(250) + "@x.com", "n".repeat(101), "correct-horse-8", "correct-horse-8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Field cannot be longer than 255 characters.")))
                .andExpect(content().string(containsString("Field cannot be longer than 100 characters.")));
        assertThat(users.count()).isZero();
    }

    @Test
    void formsWithoutTheirSecurityCodeAreRefused() throws Exception {
        mvc.perform(post("/auth/register").param("email", "an@example.com"))
                .andExpect(status().isForbidden());
    }
}
