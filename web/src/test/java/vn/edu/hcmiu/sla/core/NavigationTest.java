package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.core.Navigation.NavItem;

class NavigationTest {

    @Test
    void everyModuleIsListedAndComingSoonUntilItRegisters() {
        assertThat(new Navigation(List.of()).navItems()).containsExactly(new NavItem("School", null));
    }

    @Test
    void aRegisteredModuleGetsItsLink() {
        assertThat(new Navigation(List.of(new NavModule("School", "/school"))).navItems())
                .containsExactly(new NavItem("School", "/school"));
    }
}
