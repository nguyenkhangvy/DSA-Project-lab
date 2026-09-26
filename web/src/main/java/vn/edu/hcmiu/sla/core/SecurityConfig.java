package vn.edu.hcmiu.sla.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import vn.edu.hcmiu.sla.auth.WerkzeugPasswordEncoder;

/** Every page needs login except login, register and static files. Every form carries a CSRF token. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain pages(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(pages -> pages
                        .requestMatchers("/auth/login", "/auth/register", "/css/**", "/js/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage("/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .failureUrl("/auth/login?error")
                        .defaultSuccessUrl("/", false) // back to the page that asked for login, else home
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/auth/logout")
                        .logoutSuccessUrl("/auth/login"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new WerkzeugPasswordEncoder();
    }

    /** Where a login is kept between requests; the register page uses it to log the new account in. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
