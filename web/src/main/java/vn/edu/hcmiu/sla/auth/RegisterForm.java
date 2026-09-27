package vn.edu.hcmiu.sla.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import vn.edu.hcmiu.sla.core.Text;

/** The register page's fields, with the same rules and messages as the Python site. */
public class RegisterForm {

    static final String REQUIRED = "This field is required.";

    @NotBlank(message = REQUIRED)
    @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email address.")
    @Size(max = 255, message = "Field cannot be longer than 255 characters.")
    private String email = "";

    @NotBlank(message = REQUIRED)
    @Size(max = 100, message = "Field cannot be longer than 100 characters.")
    private String displayName = "";

    @Size(min = 8, max = 128, message = "Field must be between 8 and 128 characters long.")
    private String password = "";

    @NotBlank(message = REQUIRED)
    private String confirm = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = AppUserDetailsService.normalizeEmail(email);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : Text.strip(displayName);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getConfirm() {
        return confirm;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm == null ? "" : confirm;
    }
}
