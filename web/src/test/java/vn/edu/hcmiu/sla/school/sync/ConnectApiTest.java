package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;

/** Connect's trade-in, POST /api/school/sync/connect (spec 2026-10-04-connect-button-design.md, 3, step 5). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class ConnectApiTest {

    static final String VERIFIER = ConnectCodesTest.VERIFIER;
    static final String CHALLENGE = ConnectCodesTest.CHALLENGE;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    ConnectCodes codes;

    @Autowired
    SchoolSyncDeviceRepository devices;

    @Autowired
    TestClock clock;

    Integer an;

    @BeforeEach
    void anAccount() {
        an = users.save(new User("an@example.com", "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    String code() {
        return codes.issue(an, "an@example.com", "LAPTOP-AN", CHALLENGE);
    }

    ResultActions trade(String code, String verifier) throws Exception {
        return mvc.perform(post("/api/school/sync/connect").contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("code", code, "verifier", verifier))));
    }

    static Map<String, Object> answer(ResultActions result) throws Exception {
        return Payloads.parse(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void theRightCodeAndVerifierGiveAWorkingKeyForTheAccount() throws Exception {
        ResultActions result = trade(code(), VERIFIER)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("an@example.com"));
        String key = (String) answer(result).get("key");

        assertThat(key).matches("sla_" + SyncContract.CONNECT_SECRET);
        mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());
        assertThat(devices.findAll()).singleElement().satisfies(device -> {
            assertThat(device.getUserId()).isEqualTo(an);
            assertThat(device.getName()).isEqualTo("LAPTOP-AN");
        });
    }

    @Test
    void theAnswerHasTheSamplesFieldsAndNoOthers() throws Exception {
        assertThat(answer(trade(code(), VERIFIER)).keySet())
                .isEqualTo(Payloads.sample("connect/result.json").keySet());
    }

    @Test
    void aWrongVerifierIsRefusedAndAddsNothing() throws Exception {
        String code = code();

        trade(code, "x".repeat(43))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_code"));
        trade(code, VERIFIER).andExpect(status().isBadRequest());  // one try per code
        assertThat(devices.count()).isZero();
    }

    @Test
    void aCodeWorksOnce() throws Exception {  // Review Focus 5
        String code = code();

        trade(code, VERIFIER).andExpect(status().isOk());
        trade(code, VERIFIER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_code"));
        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {  // Review Focus 5
        LocalDateTime noon = LocalDateTime.of(2026, 10, 4, 12, 0);
        clock.set(noon);
        String code = code();
        clock.set(noon.plusMinutes(2));

        trade(code, VERIFIER).andExpect(status().isBadRequest());
        assertThat(devices.count()).isZero();
    }

    @Test
    void theTradeInNeedsNoDeviceKeyButEverythingElseStillDoes() throws Exception {
        trade(code(), VERIFIER).andExpect(status().isOk());

        mvc.perform(get("/api/school/sync/check")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/school/sync/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("trigger", "manual"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBodyThatBreaksTheContractIsRefusedAsOnEverySyncAddress() throws Exception {
        mvc.perform(post("/api/school/sync/connect").contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("code", "short", "verifier", VERIFIER))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("invalid_payload"));
        assertThat(devices.count()).isZero();
    }
}
