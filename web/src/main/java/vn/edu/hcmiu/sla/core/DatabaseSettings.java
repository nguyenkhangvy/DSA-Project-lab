package vn.edu.hcmiu.sla.core;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Connects to the database named by DATABASE_URL (from .env or the environment), the same setting the
 * Python site uses. A spring.datasource.url set directly (as the tests do) wins.
 */
public class DatabaseSettings implements EnvironmentPostProcessor {

    static final String MISSING = "Missing settings: DATABASE_URL. Copy .env.example to .env and fill them in.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment);
    }

    static void apply(ConfigurableEnvironment environment) {
        if (environment.containsProperty("spring.datasource.url")) {
            return;
        }
        String url = environment.getProperty("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(MISSING);
        }
        DatabaseUrl database = DatabaseUrl.parse(url);
        environment.getPropertySources().addFirst(new MapPropertySource("DATABASE_URL", Map.of(
                "spring.datasource.url", database.jdbcUrl(),
                "spring.datasource.username", database.username(),
                "spring.datasource.password", database.password())));
    }
}
