package vn.edu.hcmiu.sla.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Gives every page the menu: the modules in a fixed order, with a link for each one that exists. */
@ControllerAdvice
public class Navigation {

    static final List<String> MODULES = List.of("School");

    /** One menu entry; {@code path} is null while the module doesn't exist yet. */
    public record NavItem(String label, String path) {
    }

    private final Map<String, String> paths;

    public Navigation(List<NavModule> modules) {
        this.paths = modules.stream().collect(Collectors.toMap(NavModule::label, NavModule::path, (a, b) -> a));
    }

    @ModelAttribute("navItems")
    public List<NavItem> navItems() {
        return MODULES.stream().map(label -> new NavItem(label, paths.get(label))).toList();
    }
}
