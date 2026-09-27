package vn.edu.hcmiu.sla.school;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.hcmiu.sla.core.NavModule;

/** Puts School in the menu and on the dashboard. */
@Configuration
class SchoolModule {

    @Bean
    NavModule schoolMenu() {
        return new NavModule("School", "/school");
    }
}
