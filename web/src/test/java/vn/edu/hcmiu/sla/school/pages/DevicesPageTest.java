package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/** The Devices page: Java twin of the device tests in tests/test_school_pages.py. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DevicesPageTest {

    static final Pattern KEY = Pattern.compile("sla_[A-Za-z0-9_\\-]{40,}");

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    DeviceKeys deviceKeys;

    @Autowired
    SchoolSyncDeviceRepository devices;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    ResultActions addDevice(String name) throws Exception {
        return mvc.perform(post("/school/devices").with(user(an)).with(csrf()).param("name", name));
    }

    static String keyIn(String html) {
        Matcher match = KEY.matcher(html);
        return match.find() ? match.group() : null;
    }

    List<SchoolSyncDevice> all() {
        db.flush();
        db.clear();
        return devices.findAll(Sort.by("id"));
    }

    ResultActions check(String key) throws Exception {
        return mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key));
    }

    String devicesPage() throws Exception {
        return mvc.perform(get("/school/devices").with(user(an))).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewDeviceKeyIsShownOnceAndWorks() throws Exception {
        String html = addDevice("My laptop")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String key = keyIn(html);

        assertThat(key).isNotNull();
        assertThat(html).contains("open School-Life-Assistant on the laptop");
        check(key).andExpect(status().isOk());
        assertThat(devicesPage()).doesNotContain(key);
    }

    @Test
    void aDeviceNeedsAName() throws Exception {
        String html = addDevice("   ").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(keyIn(html)).isNull();
        assertThat(html).contains("This field is required.");
        assertThat(all()).isEmpty();
    }

    @Test
    void cancellingADeviceStopsItsKey() throws Exception {
        String key = keyIn(addDevice("My laptop").andReturn().getResponse().getContentAsString());
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/revoke").with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/devices"))
                .andExpect(flash().attribute("flashes", List.of(new Flash("message", "“My laptop” can no longer sync."))));

        assertThat(all().get(0).getRevokedAt()).isNotNull();
        check(key).andExpect(status().isUnauthorized());
    }

    @Test
    void aCancelledDeviceDisappearsFromTheList() throws Exception {
        addDevice("Old laptop");
        addDevice("New laptop");
        Integer oldId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + oldId + "/revoke").with(user(an)).with(csrf()));

        assertThat(devicesPage()).doesNotContain("Old laptop").contains("New laptop");
    }

    @Test
    void renamingADevice() throws Exception {
        addDevice("My laptop");
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/rename").with(user(an)).with(csrf()).param("name", "Dorm laptop"))
                .andExpect(flash().attribute("flashes", List.of(new Flash("message", "Device renamed."))));

        assertThat(all().get(0).getName()).isEqualTo("Dorm laptop");
    }

    @Test
    void aDeviceCantBeRenamedToNothing() throws Exception {
        addDevice("My laptop");
        Integer deviceId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + deviceId + "/rename").with(user(an)).with(csrf()).param("name", " "))
                .andExpect(flash().attribute("flashes",
                        List.of(new Flash("error", "A device name is required (up to 100 characters)."))));

        assertThat(all().get(0).getName()).isEqualTo("My laptop");
    }

    @ParameterizedTest
    @CsvSource({"revoke, ''", "rename, Mine now"})
    void nobodyCanChangeAnotherUsersDevice(String action, String name) throws Exception {
        AppUser binh = data.user("binh@example.com");
        String otherKey = deviceKeys.create(binh.id(), "Binh's laptop", LocalDateTime.of(2026, 9, 1, 0, 0)).rawKey();
        Integer otherId = all().get(0).getId();

        mvc.perform(post("/school/devices/" + otherId + "/" + action).with(user(an)).with(csrf()).param("name", name))
                .andExpect(status().isNotFound());

        SchoolSyncDevice device = all().get(0);
        assertThat(device.getName()).isEqualTo("Binh's laptop");
        assertThat(device.getRevokedAt()).isNull();
        check(otherKey).andExpect(status().isOk());
    }

    @Test
    void theDevicesPageListsOnlyMyDevices() throws Exception {
        deviceKeys.create(data.user("binh@example.com").id(), "Binh's laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        addDevice("An's laptop");

        assertThat(devicesPage()).contains("An&#39;s laptop").doesNotContain("Binh");
    }

    @Test
    void formsWithoutTheirSecurityCodeAreRefused() throws Exception {
        mvc.perform(post("/school/devices").with(user(an)).param("name", "My laptop")).andExpect(status().isForbidden());

        assertThat(all()).isEmpty();
    }
}
