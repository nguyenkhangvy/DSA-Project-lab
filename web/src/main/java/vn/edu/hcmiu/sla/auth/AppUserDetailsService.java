package vn.edu.hcmiu.sla.auth;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Finds the account for the email typed on the login page (trimmed and lower-cased, as at register). */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(normalizeEmail(email))
                .map(AppUser::of)
                .orElseThrow(() -> new UsernameNotFoundException("No account for that email"));
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : stripSpaces(email).toLowerCase(Locale.ROOT);
    }

    /** Like Python's str.strip(): also removes non-breaking spaces pasted from Word, Outlook or a web page. */
    static String stripSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
