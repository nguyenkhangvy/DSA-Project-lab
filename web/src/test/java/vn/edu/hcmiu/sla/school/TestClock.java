package vn.edu.hcmiu.sla.school;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The site's clock in page tests: the real time until a test sets one. Use it with
 * {@code @Import(TestClock.Config.class)} and {@code @Autowired TestClock clock}; call {@link #reset} after.
 */
public class TestClock extends Clock {

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private Instant fixed;

    /** From now on the site's time is this UTC time. */
    public void set(LocalDateTime utc) {
        fixed = utc.toInstant(ZoneOffset.UTC);
    }

    public void reset() {
        fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return fixed != null ? fixed : Instant.now();
    }
}
