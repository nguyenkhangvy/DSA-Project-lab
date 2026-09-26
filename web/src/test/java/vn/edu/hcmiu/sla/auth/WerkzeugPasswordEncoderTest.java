package vn.edu.hcmiu.sla.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class WerkzeugPasswordEncoderTest {

    // Made by the Python site's Werkzeug: generate_password_hash("correct-horse-8"), and so on.
    static final String SCRYPT = "scrypt:32768:8:1$ueZCDJ4RK5S9GaVe$d6d8d224601f811c03b2dd57870ec046e8bbda5e2b46338f"
            + "7ff949552d22cda2eddbb16a3ed5c96508def3c501f70558543cd0c0f39ba3d360d24867ccce091d";
    static final String SCRYPT_VIETNAMESE = "scrypt:32768:8:1$OS9zvd2XxLG2foqc$14334d0a6981235b7d78aff4e99e2bdc21ebb5"
            + "f2989171df35dc58e7084829c9498997633cb3afa2e71d9457a6c3e5848e1d5d1fd3d2f9fd0051616a941afe75";
    static final String PBKDF2 = "pbkdf2:sha256:1000$JuajhCPVQgbn0zAC$380b7e5da69ae11feba310b3a30a87a62478c4bee78676"
            + "c3273554b8163dec35";

    private final WerkzeugPasswordEncoder encoder = new WerkzeugPasswordEncoder();

    @Test
    void aPasswordSavedByThePythonSiteStillWorks() {
        assertThat(encoder.matches("correct-horse-8", SCRYPT)).isTrue();
        assertThat(encoder.matches("correct-horse-9", SCRYPT)).isFalse();
    }

    @Test
    void vietnameseLettersInAPasswordAreReadTheSameWay() {
        assertThat(encoder.matches("mật khẩu 123", SCRYPT_VIETNAMESE)).isTrue();
        assertThat(encoder.matches("mat khau 123", SCRYPT_VIETNAMESE)).isFalse();
    }

    @Test
    void olderPbkdf2PasswordsWorkToo() {
        assertThat(encoder.matches("correct-horse-8", PBKDF2)).isTrue();
    }

    @Test
    void newPasswordsAreSavedInWerkzeugsScryptFormat() {
        String hash = encoder.encode("correct-horse-8");

        assertThat(hash).matches("scrypt:32768:8:1\\$[A-Za-z0-9]{16}\\$[0-9a-f]{128}");
        assertThat(encoder.matches("correct-horse-8", hash)).isTrue();
        assertThat(encoder.encode("correct-horse-8")).isNotEqualTo(hash); // a new salt every time
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "scrypt:x$salt$00", "scrypt:a:b:c$salt$00", "md5$salt$00"})
    void aDamagedHashIsJustAWrongPassword(String hash) {
        assertThat(encoder.matches("correct-horse-8", hash)).isFalse();
    }
}
