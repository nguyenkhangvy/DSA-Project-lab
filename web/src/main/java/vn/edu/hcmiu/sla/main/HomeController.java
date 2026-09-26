package vn.edu.hcmiu.sla.main;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import vn.edu.hcmiu.sla.auth.AppUser;

/** The dashboard: a greeting and one card per module. */
@Controller
public class HomeController {

    @GetMapping("/")
    String index(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("displayName", user.displayName());
        return "main/index";
    }
}
