package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** Settings that other programs depend on. */
class SiteSettingsTest {

    @Test
    void theSiteRunsOnPort5000WhereTheLaptopAgentLooksForIt() throws Exception {
        Properties settings = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(settings.getProperty("server.port")).isEqualTo("5000");
    }
}
