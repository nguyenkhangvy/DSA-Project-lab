package vn.edu.hcmiu.sla.school.pages;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.sync.ConnectCodes;
import vn.edu.hcmiu.sla.school.sync.SyncContract;

/**
 * The Connect page (spec 2026-10-04-connect-button-design.md, 2 and 4.1). The laptop's app opens it with its port,
 * state, challenge and the laptop's name. Connect sends the browser back to the app on this laptop,
 * http://127.0.0.1:&lt;port&gt;/callback, with a one-time code; Cancel sends it back with error=cancelled. Nothing is
 * added here: the laptop appears on Devices when the app trades the code (SyncApiController.connect).
 */
@Controller
@RequestMapping("/school/devices/connect")
public class ConnectController {

    static final String DEFAULT_NAME = "My laptop";
    static final int MAX_NAME = 100;

    private final ConnectCodes codes;

    public ConnectController(ConnectCodes codes) {
        this.codes = codes;
    }

    /** The app's link, checked: {@link #of} gives null for a link the app can't have made. */
    record Link(int port, String state, String challenge, String name) {

        static Link of(String port, String state, String challenge, String name) {
            if (port == null || !port.matches("[0-9]{4,5}") || state == null
                    || !state.matches(SyncContract.CONNECT_SECRET) || challenge == null
                    || !challenge.matches(SyncContract.CONNECT_SECRET)) {
                return null;
            }
            int number = Integer.parseInt(port);
            if (number < 1024 || number > 65535) {
                return null;
            }
            String laptop = name == null ? "" : name.strip();
            return new Link(number, state, challenge,
                    laptop.isEmpty() || laptop.length() > MAX_NAME ? DEFAULT_NAME : laptop);
        }

        /** Back to the app: always this laptop, at the app's port; never an address from the link. */
        String back(String answer) {
            return "http://127.0.0.1:" + port + "/callback?" + answer + "&state=" + state;
        }
    }

    private static String page(AppUser user, Link link, Model model) {
        model.addAttribute("email", user.email());
        model.addAttribute("fromApp", link != null);
        if (link != null) {
            model.addAttribute("appPort", link.port());
            model.addAttribute("appState", link.state());
            model.addAttribute("appChallenge", link.challenge());
            model.addAttribute("laptop", link.name());
        }
        return "school/connect";
    }

    @GetMapping
    String show(@AuthenticationPrincipal AppUser user, @RequestParam(required = false) String port,
            @RequestParam(required = false) String state, @RequestParam(required = false) String challenge,
            @RequestParam(required = false) String name, Model model) {
        return page(user, Link.of(port, state, challenge, name), model);
    }

    @PostMapping
    String answer(@AuthenticationPrincipal AppUser user, @RequestParam(required = false) String port,
            @RequestParam(required = false) String state, @RequestParam(required = false) String challenge,
            @RequestParam(required = false) String name, @RequestParam(defaultValue = "cancel") String action,
            Model model) {
        Link link = Link.of(port, state, challenge, name);
        if (link == null) {
            return page(user, null, model);
        }
        if (!action.equals("connect")) {
            return "redirect:" + link.back("error=cancelled");
        }
        return "redirect:" + link.back("code=" + codes.issue(user.id(), user.email(), link.name(), link.challenge()));
    }
}
