package vn.edu.hcmiu.sla.auth;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The login page. Spring Security itself handles POST /auth/login and POST /auth/logout. */
@Controller
@RequestMapping("/auth")
public class AuthController {

    @GetMapping("/login")
    String login() {
        return "auth/login";
    }
}
