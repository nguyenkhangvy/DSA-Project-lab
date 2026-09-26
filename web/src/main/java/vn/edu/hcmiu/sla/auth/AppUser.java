package vn.edu.hcmiu.sla.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in user as Spring Security keeps it. Controllers get it with
 * {@code @AuthenticationPrincipal AppUser user} and filter every query by {@code user.id()}.
 */
public record AppUser(Integer id, String email, String displayName, String passwordHash) implements UserDetails {

    public static AppUser of(User user) {
        return new AppUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** For templates: {@code ${#authentication.principal.displayName}}. */
    public String getDisplayName() {
        return displayName;
    }
}
