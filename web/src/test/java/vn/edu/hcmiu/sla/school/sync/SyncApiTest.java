package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.edu.hcmiu.sla.school.sync.Payloads.BB;
import static vn.edu.hcmiu.sla.school.sync.Payloads.at;
import static vn.edu.hcmiu.sla.school.sync.Payloads.blackboardPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;
import static vn.edu.hcmiu.sla.school.sync.Payloads.fullPayload;
import static vn.edu.hcmiu.sla.school.sync.Payloads.ok;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;

/** Java twin of tests/test_school_sync_api.py. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SyncApiTest {

    static final Sort BY_ID = Sort.by("id");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    DeviceKeys deviceKeys;

    @Autowired
    SchoolSyncDeviceRepository devices;

    @Autowired
    SchoolSyncRunRepository runs;

    String key;

    Integer makeUser(String email) {
        return users.save(new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    /** A sync device made the way the Devices page makes it; returns the raw key. */
    String makeDevice(Integer userId) {
        return deviceKeys.create(userId, "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
    }

    @BeforeEach
    void device() {
        key = makeDevice(makeUser("an@example.com"));
    }

    static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key) {
        return request.header("Authorization", "Bearer " + key);
    }

    ResultActions check(String key) throws Exception {
        return mvc.perform(withKey(get("/api/school/sync/check"), key));
    }

    ResultActions start(String key, String trigger) throws Exception {
        return mvc.perform(withKey(post("/api/school/sync/runs"), key)
                .contentType(MediaType.APPLICATION_JSON).content(bytes(Map.of("trigger", trigger))));
    }

    ResultActions finish(String key, int runId, Object payload) throws Exception {
        return mvc.perform(withKey(post("/api/school/sync/runs/" + runId + "/finish"), key)
                .contentType(MediaType.APPLICATION_JSON).content(bytes(payload)));
    }

    int startedRun(String key) throws Exception {
        start(key, "scheduled").andExpect(status().isCreated());
        return lastRun().getId();
    }

    SchoolSyncRun lastRun() {
        List<SchoolSyncRun> all = runs.findAll(BY_ID);
        return all.get(all.size() - 1);
    }

    SchoolSyncDevice theDevice() {
        return devices.findAll(BY_ID).get(0);
    }

    // ---- Device keys ----------------------------------------------------------

    @Test
    void aDeviceKeyIsStoredOnlyAsItsSha256Hash() {
        SchoolSyncDevice device = theDevice();

        assertThat(device.getTokenHash()).isEqualTo(DeviceKeys.hashKey(key)).hasSize(64).doesNotContain(key);
        assertThat(key).startsWith("sla_").hasSize(4 + 43);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Bearer not-a-real-key", "Basic dXNlcjpwYXNz", "Bearer "})
    void requestsWithoutAValidDeviceKeyGet401(String authorization) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/school/sync/check");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\": \"invalid_device_key\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aCancelledDeviceKeyGets401() throws Exception {
        theDevice().setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));

        check(key).andExpect(status().isUnauthorized());
    }

    @Test
    void theApiNeedsNoLoginAndNoCsrfTokenButTheRestOfTheSiteStillDoes() throws Exception {
        start(key, "manual").andExpect(status().isCreated());

        mvc.perform(get("/api/school/other")).andExpect(redirectedUrl("/auth/login"));
    }

    // ---- Check ----------------------------------------------------------------

    @Test
    void theFirstCheckSaysDueAndRecordsTheCheckIn() throws Exception {
        check(key)
                .andExpect(status().isOk())
                .andExpect(content().json("{\"due\": true, \"reason\": \"never\", \"interval_hours\": 12}",
                        JsonCompareMode.STRICT));

        assertThat(theDevice().getLastSeenAt()).isNotNull();
    }

    // ---- Start ----------------------------------------------------------------

    @Test
    void startCreatesARunningRunAndCheckThenSaysRunning() throws Exception {
        start(key, "scheduled")
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"run_id\": " + lastRun().getId() + "}", JsonCompareMode.STRICT));

        SchoolSyncRun run = lastRun();
        assertThat(List.of(run.getStatus(), run.getTrigger())).containsExactly("running", "scheduled");
        assertThat(run.getDeviceId()).isEqualTo(theDevice().getId());
        check(key).andExpect(jsonPath("$.reason").value("running"));
    }

    @Test
    void aSecondStartWhileOneIsRunningGets409() throws Exception {
        start(key, "scheduled");

        start(key, "manual")
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"run_in_progress\"}", JsonCompareMode.STRICT));
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    void startClosesAStuckRunAsTimedOut() throws Exception {
        int stuckId = startedRun(key);
        runs.findById(stuckId).orElseThrow().setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));

        start(key, "manual").andExpect(status().isCreated());

        SchoolSyncRun old = runs.findById(stuckId).orElseThrow();
        assertThat(List.of(old.getStatus(), old.getErrorCode())).containsExactly("failed", "timeout");
        assertThat(old.getFinishedAt()).isNotNull();
        assertThat(lastRun().getStatus()).isEqualTo("running");
    }

    @Test
    void startRejectsAnUnknownTrigger() throws Exception {
        start(key, "whenever").andExpect(status().isUnprocessableContent());

        assertThat(runs.count()).isZero();
    }

    // ---- Finish ---------------------------------------------------------------

    @Test
    void finishMarksTheRunWithItsOverallStatus() throws Exception {
        int runId = startedRun(key);

        finish(key, runId, fullPayload())
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\": \"success\"}", JsonCompareMode.STRICT));

        SchoolSyncRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo("success");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void anInvalidUploadGets422WithoutRepeatingItAndLeavesTheRunRunning() throws Exception {
        int runId = startedRun(key);
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data").put("student_id", "ITITIU20001");

        finish(key, runId, payload)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.details[0].loc[2]").value("student_id"))
                .andExpect(content().string(not(containsString("ITITIU20001"))));
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void aDeviceCannotFinishAnotherUsersRun() throws Exception {
        int runId = startedRun(key);
        String otherKey = makeDevice(makeUser("binh@example.com"));

        finish(otherKey, runId, fullPayload()).andExpect(status().isNotFound());

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void aFinishedRunCannotBeFinishedAgain() throws Exception {
        int runId = startedRun(key);
        finish(key, runId, fullPayload());

        finish(key, runId, fullPayload())
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"run_not_running\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anOversizedUploadGets413() throws Exception {
        int runId = startedRun(key);
        Map<String, Object> payload = fullPayload();
        at(payload, "timetable", "data").put("term_name", "x".repeat(5_100_000));

        finish(key, runId, payload)
                .andExpect(status().isContentTooLarge())
                .andExpect(content().json("{\"error\": \"payload_too_large\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aHeavySemesterOfBlackboardDataInVietnameseIsAccepted() throws Exception {
        // 11 courses x 40 announcements of about 1,500 Vietnamese characters: well over 1 MB once JSON
        // escapes every letter with a diacritic as \\uXXXX, as the agent's Python JSON does.
        int runId = startedRun(key);
        Map<String, Object> course = at(blackboardPayload(), "courses", 0);
        String text = "Thông báo: lớp học bù vào thứ Năm, phòng A2.401. ".repeat(30);
        List<Object> courses = new ArrayList<>();
        for (int c = 1; c <= 11; c++) {
            List<Object> announcements = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                announcements.add(Map.of("bb_id", "_" + c + i + "_1", "title", "Thông báo " + i, "text", text,
                        "posted_at", "2026-09-28T02:00:00+00:00", "url", BB + "/x"));
            }
            Map<String, Object> copy = new HashMap<>(course);
            copy.put("bb_id", "_" + c + "_1");
            copy.put("announcements", announcements);
            courses.add(copy);
        }
        Map<String, Object> payload = fullPayload();
        payload.put("blackboard", ok(Map.of("courses", courses)));

        finish(key, runId, payload).andExpect(status().isOk());

        assertThat(bytes(payload).length).isGreaterThan(1_200_000);
    }
}
