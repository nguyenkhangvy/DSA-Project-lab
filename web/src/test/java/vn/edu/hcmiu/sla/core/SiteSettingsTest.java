package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

/** Settings that other programs depend on. */
class SiteSettingsTest {

    static Properties settings() throws Exception {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    }

    @Test
    void theSiteRunsOnPort5000WhereTheLaptopAgentLooksForIt() throws Exception {
        String port = settings().getProperty("server.port", "");

        assertThat(new MockEnvironment().resolvePlaceholders(port)).isEqualTo("5000");
    }

    @Test
    void onlineTheHostChoosesThePort() throws Exception {
        String port = settings().getProperty("server.port", "");

        assertThat(new MockEnvironment().withProperty("PORT", "10000").resolvePlaceholders(port)).isEqualTo("10000");
    }

    @Test
    void onlyThisLaptopCanOpenTheSiteUnlessServerAddressSaysOtherwise() throws Exception {
        String address = settings().getProperty("server.address", "");

        assertThat(new MockEnvironment().resolvePlaceholders(address)).isEqualTo("127.0.0.1");
        assertThat(new MockEnvironment().withProperty("SERVER_ADDRESS", "0.0.0.0").resolvePlaceholders(address))
                .isEqualTo("0.0.0.0");
    }
}
