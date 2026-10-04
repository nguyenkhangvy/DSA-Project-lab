package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.sync.ConnectCodes;

/** The Connect page (spec 2026-10-04-connect-button-design.md, 2 and 4.1). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConnectPageTest {

    static final String STATE = "state-0123456789_abcdefghijklmnopqrstuvwxyz";
    // RFC 7636's example verifier and its challenge (ConnectCodesTest and test_connect.py use the same pair).
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    static final Pattern BACK_WITH_A_CODE =
            Pattern.compile("http://127\\.0\\.0\\.1:51234/callback\\?code=([A-Za-z0-9_-]{43})&state=" + STATE);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    ConnectCodes codes;

    @Autowired
    SchoolSyncDeviceRepository devices;

    AppUser an;

    @BeforeEach
    void anAccount() {
        an = new SchoolTestData(db).user("an@example.com");
    }

    static MockHttpServletRequestBuilder link(MockHttpServletRequestBuilder request, String port, String state,
            String challenge, String name) {
        return request.param("port", port).param("state", state).param("challenge", challenge).param("name", name);
    }

    MockHttpServletRequestBuilder page(String name) {
        return link(get("/school/devices/connect"), "51234", STATE, CHALLENGE, name).with(user(an));
    }

    MockHttpServletRequestBuilder press(String action) {
        return link(post("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN")
                .param("action", action).with(user(an)).with(csrf());
    }

    String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theAppsLinkAsksForLoginFirst() throws Exception {
        mvc.perform(link(get("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN"))
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void thePageNamesTheLaptopAndTheAccountAndCantBeFramed() throws Exception {
        mvc.perform(page("LAPTOP-AN")).andExpect(header().string("X-Frame-Options", "DENY"));

        assertThat(html(page("LAPTOP-AN"))).contains("Connect this laptop?", "LAPTOP-AN", "an@example.com",
                "value=\"connect\"", "value=\"cancel\"", "value=\"51234\"", "value=\"" + STATE + "\"",
                "value=\"" + CHALLENGE + "\"", "Not your account?");
    }

    static Stream<Arguments> linksTheAppCantHaveMade() {
        return Stream.of(
                Arguments.of("80", STATE, CHALLENGE),
                Arguments.of("65536", STATE, CHALLENGE),
                Arguments.of("5123a", STATE, CHALLENGE),
                Arguments.of("51234", "short", CHALLENGE),
                Arguments.of("51234", STATE, CHALLENGE.substring(0, 42) + "!"),
                Arguments.of(null, null, null));
    }

    @ParameterizedTest
    @MethodSource("linksTheAppCantHaveMade")
    void aLinkTheAppCantHaveMadeOffersNothingToPress(String port, String state, String challenge) throws Exception {
        MockHttpServletRequestBuilder request = get("/school/devices/connect").with(user(an));
        if (port != null) {
            request = link(request, port, state, challenge, "LAPTOP-AN");
        }

        assertThat(html(request)).contains("This link didn't come from School-Life-Assistant.")
                .doesNotContain("value=\"connect\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aLaptopWithoutANameIsMyLaptop(String name) throws Exception {
        assertThat(html(page(name))).contains("My laptop");
    }

    @Test
    void aNameTooLongIsMyLaptop() throws Exception {
        assertThat(html(page("x".repeat(101)))).contains("My laptop").doesNotContain("x".repeat(101));
    }

    @Test
    void connectSendsTheBrowserBackToTheAppWithACodeAndAddsNoLaptopYet() throws Exception {
        String location = mvc.perform(press("connect")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        Matcher back = BACK_WITH_A_CODE.matcher(location);
        assertThat(back.matches()).as(location).isTrue();
        assertThat(devices.count()).isZero();
        assertThat(codes.redeem(back.group(1), VERIFIER)).hasValueSatisfying(pending -> {
            assertThat(pending.userId()).isEqualTo(an.id());
            assertThat(pending.email()).isEqualTo("an@example.com");
            assertThat(pending.name()).isEqualTo("LAPTOP-AN");
        });
    }

    @Test
    void cancelSendsTheBrowserBackWithNoCode() throws Exception {
        mvc.perform(press("cancel"))
                .andExpect(redirectedUrl("http://127.0.0.1:51234/callback?error=cancelled&state=" + STATE));
        assertThat(devices.count()).isZero();
    }

    @Test
    void aPressWithABadLinkGoesNowhere() throws Exception {
        String html = html(link(post("/school/devices/connect"), "80", STATE, CHALLENGE, "LAPTOP-AN")
                .param("action", "connect").with(user(an)).with(csrf()));

        assertThat(html).contains("This link didn't come from School-Life-Assistant.");
    }

    @Test
    void theAddressBackIsBuiltFromThePortAlone() throws Exception {
        String location = mvc.perform(press("connect")
                        .param("redirect", "https://evil.example/steal")
                        .param("callback", "https://evil.example/steal"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("http://127.0.0.1:51234/callback?");
    }

    @Test
    void connectNeedsTheFormsSecurityCode() throws Exception {
        mvc.perform(link(post("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN")
                        .param("action", "connect").with(user(an)))
                .andExpect(status().isForbidden());
    }
}
