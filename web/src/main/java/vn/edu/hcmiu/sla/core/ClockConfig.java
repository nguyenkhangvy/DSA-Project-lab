package vn.edu.hcmiu.sla.core;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The site's clock, in UTC. Pages ask it for "now" ({@code LocalDateTime.now(clock)}) instead of the
 * computer's clock, so a test can set the time with its own {@code @Primary} Clock bean.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
