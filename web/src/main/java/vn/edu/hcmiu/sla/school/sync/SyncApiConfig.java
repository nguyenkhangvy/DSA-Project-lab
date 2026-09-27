package vn.edu.hcmiu.sla.school.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The sync API at /api/school/sync/** is for the laptop agent, not a browser: no login page, no session
 * and no CSRF token. Instead {@link DeviceKeyInterceptor} checks the device key on every request.
 */
@Configuration
public class SyncApiConfig implements WebMvcConfigurer {

    static final String PATHS = "/api/school/sync/**";

    private final DeviceKeyInterceptor deviceKeyInterceptor;

    public SyncApiConfig(DeviceKeyInterceptor deviceKeyInterceptor) {
        this.deviceKeyInterceptor = deviceKeyInterceptor;
    }

    @Bean
    @Order(1) // before the pages' filter chain, which covers every other address
    SecurityFilterChain syncApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PATHS)
                .authorizeHttpRequests(api -> api.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceKeyInterceptor).addPathPatterns(PATHS);
    }
}
