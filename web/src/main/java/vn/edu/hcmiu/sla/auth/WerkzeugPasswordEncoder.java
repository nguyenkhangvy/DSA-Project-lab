package vn.edu.hcmiu.sla.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwords in the format of Python's Werkzeug, which the Python site used: "scrypt:N:r:p$salt$hex" and
 * "pbkdf2:sha256:iterations$salt$hex". Existing accounts keep their passwords, and new ones are saved as
 * scrypt:32768:8:1 so either site accepts them during the changeover.
 */
public class WerkzeugPasswordEncoder implements PasswordEncoder {

    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SALT_LENGTH = 16;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(CharSequence rawPassword) {
        StringBuilder salt = new StringBuilder(SALT_LENGTH);
        for (int i = 0; i < SALT_LENGTH; i++) {
            salt.append(SALT_CHARS.charAt(random.nextInt(SALT_CHARS.length())));
        }
        String method = "scrypt:32768:8:1";
        return method + "$" + salt + "$" + hash(method, salt.toString(), rawPassword.toString());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", 3);
        if (parts.length != 3) {
            return false;
        }
        try {
            String expected = hash(parts[0], parts[1], rawPassword.toString());
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    parts[2].getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String hash(String method, String salt, String password) {
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        String[] m = method.split(":");
        if (m[0].equals("scrypt") && m.length == 4) {
            int n = Integer.parseInt(m[1]);
            int r = Integer.parseInt(m[2]);
            int p = Integer.parseInt(m[3]);
            return HexFormat.of().formatHex(SCrypt.generate(passwordBytes, saltBytes, n, r, p, 64));
        }
        if (m[0].equals("pbkdf2") && m.length == 3 && m[1].equals("sha256")) {
            int iterations = Integer.parseInt(m[2]);
            try {
                KeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, iterations, 256);
                byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                return HexFormat.of().formatHex(key);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("Unsupported password hash method: " + m[0]);
    }
}
