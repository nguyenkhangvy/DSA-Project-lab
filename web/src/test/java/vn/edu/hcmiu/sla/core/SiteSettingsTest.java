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
        assertThat(settings().getProperty("server.port")).isEqualTo("5000");
    }

    @Test
    void onlyThisLaptopCanOpenTheSiteUnlessServerAddressSaysOtherwise() throws Exception {
        String address = settings().getProperty("server.address", "");

        assertThat(new MockEnvironment().resolvePlaceholders(address)).isEqualTo("127.0.0.1");
        assertThat(new MockEnvironment().withProperty("SERVER_ADDRESS", "0.0.0.0").resolvePlaceholders(address))
                .isEqualTo("0.0.0.0");
    }
}
