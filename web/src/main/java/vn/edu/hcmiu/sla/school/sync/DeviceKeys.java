package vn.edu.hcmiu.sla.school.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;

/**
 * Device keys: how the laptop agent proves which user it syncs for. The raw key is shown to the user
 * once; the database keeps only its SHA-256 hash, so a leaked database can't be used to upload data.
 * Keys look the same as the Python site's, so a laptop set up there keeps working.
 */
@Service
public class DeviceKeys {

    public static final String KEY_PREFIX = "sla_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new device and its raw key, which is shown once and never stored. */
    public record NewDevice(SchoolSyncDevice device, String rawKey) {
    }

    private final SchoolSyncDeviceRepository devices;

    public DeviceKeys(SchoolSyncDeviceRepository devices) {
        this.devices = devices;
    }

    public static String hashKey(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Like Python's secrets.token_urlsafe(32): 32 random bytes, URL-safe Base64 without padding. */
    static String newRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public NewDevice create(Integer userId, String name, LocalDateTime now) {
        String rawKey = newRawKey();
        SchoolSyncDevice device = devices.save(new SchoolSyncDevice(userId, name, hashKey(rawKey), now));
        return new NewDevice(device, rawKey);
    }

    /** The active device for this key, or empty. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncDevice> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return Optional.empty();
        }
        return devices.findByTokenHashAndRevokedAtIsNull(hashKey(rawKey));
    }

    /** Like {@link #authenticate}, and records that the laptop checked in now. */
    @Transactional
    public Optional<SchoolSyncDevice> checkIn(String rawKey, LocalDateTime now) {
        Optional<SchoolSyncDevice> device = authenticate(rawKey);
        device.ifPresent(found -> found.setLastSeenAt(now));
        return device;
    }
}
