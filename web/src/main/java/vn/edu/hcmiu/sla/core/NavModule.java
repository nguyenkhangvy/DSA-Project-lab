package vn.edu.hcmiu.sla.core;

/**
 * A module's menu entry. A module turns its link on by declaring one bean, e.g. in its package:
 * <pre>
 * &#64;Bean NavModule schoolMenu() { return new NavModule("School", "/school"); }
 * </pre>
 * Until then the menu shows it as "coming soon".
 */
public record NavModule(String label, String path) {
}
