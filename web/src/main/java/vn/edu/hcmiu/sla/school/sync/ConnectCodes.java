package vn.edu.hcmiu.sla.school.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Connect this laptop (spec 2026-10-04-connect-button-design.md, 3 and 4.2): the one-time codes the Connect page
 * hands the browser, which the laptop's app trades for its device key. A code lasts {@link #LIFETIME} and one try,
 * and is kept only as its SHA-256, in memory: one server runs the site, and a restart only means pressing Connect
 * again.
 */
@Component
public class ConnectCodes {

    static final Duration LIFETIME = Duration.ofMinutes(2);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    /** A Connect waiting for its trade-in: whose account, the laptop's name, and the app's challenge. */
    public record Pending(Integer userId, String email, String name, String challenge, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ConnectCodes(Clock clock) {
        this.clock = clock;
    }

    /** A new code for this account and laptop. The raw code goes to the browser and is never kept. */
    public String issue(Integer userId, String email, String name, String challenge) {
        Instant now = clock.instant();
        pending.values().removeIf(old -> !now.isBefore(old.expiresAt()));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = URL_SAFE.encodeToString(bytes);
        pending.put(DeviceKeys.hashKey(code), new Pending(userId, email, name, challenge, now.plus(LIFETIME)));
        return code;
    }

    /**
     * The Connect this code belongs to, if the code is still there, hasn't expired, and the verifier matches its
     * challenge. The code is gone after this call whatever the answer: one try per code.
     */
    public Optional<Pending> redeem(String code, String verifier) {
        Pending found = pending.remove(DeviceKeys.hashKey(code));
        if (found == null || !clock.instant().isBefore(found.expiresAt())) {
            return Optional.empty();
        }
        boolean matches = MessageDigest.isEqual(found.challenge().getBytes(StandardCharsets.US_ASCII),
                challenge(verifier).getBytes(StandardCharsets.US_ASCII));
        return matches ? Optional.of(found) : Optional.empty();
    }

    /** What the app sends as its challenge: the verifier's SHA-256, URL-safe Base64 without padding. */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_SAFE.encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
