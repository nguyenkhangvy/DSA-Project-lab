package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseSettingsTest {

    @Test
    void databaseUrlFromDotEnvBecomesTheConnection() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DATABASE_URL", "mysql+pymysql://sla_app:secret@localhost:3306/school_life?charset=utf8mb4");

        DatabaseSettings.apply(env);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://localhost:3306/school_life?characterEncoding=UTF-8");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("sla_app");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void aConnectionSetDirectlyWins() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:x")
                .withProperty("DATABASE_URL", "mysql+pymysql://u:p@localhost:3306/db");

        DatabaseSettings.apply(env);

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:x");
    }

    @Test
    void withoutDatabaseUrlTheSiteStopsWithAClearMessage() {
        assertThatThrownBy(() -> DatabaseSettings.apply(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing settings: DATABASE_URL. Copy .env.example to .env and fill them in.");
    }
}
