package vn.edu.hcmiu.sla.core;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A one-time message shown at the top of the next page, like "Device renamed." Use it before a redirect:
 * <pre>
 * Flash.success(redirect, "Device renamed.");
 * return "redirect:/school/devices";
 * </pre>
 */
public record Flash(String category, String text) {

    public static void success(RedirectAttributes redirect, String text) {
        add(redirect, new Flash("message", text));
    }

    public static void error(RedirectAttributes redirect, String text) {
        add(redirect, new Flash("error", text));
    }

    @SuppressWarnings("unchecked")
    private static void add(RedirectAttributes redirect, Flash flash) {
        List<Flash> flashes = (List<Flash>) redirect.getFlashAttributes().get("flashes");
        if (flashes == null) {
            flashes = new ArrayList<>();
            redirect.addFlashAttribute("flashes", flashes);
        }
        flashes.add(flash);
    }
}
