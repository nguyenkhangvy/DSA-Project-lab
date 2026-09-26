package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.core.Navigation.NavItem;

class NavigationTest {

    @Test
    void everyModuleIsListedInOrderAndComingSoonUntilItRegisters() {
        assertThat(new Navigation(List.of()).navItems()).containsExactly(
                new NavItem("School", null), new NavItem("Expense", null), new NavItem("Health", null));
    }

    @Test
    void aRegisteredModuleGetsItsLink() {
        assertThat(new Navigation(List.of(new NavModule("Expense", "/expense"))).navItems()).containsExactly(
                new NavItem("School", null), new NavItem("Expense", "/expense"), new NavItem("Health", null));
    }
}
