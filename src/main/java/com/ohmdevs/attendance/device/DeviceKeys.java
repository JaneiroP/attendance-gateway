package com.ohmdevs.attendance.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and verifies device API keys.
 *
 * <p>Keys are 256 random bits, so a plain SHA-256 is enough to store them: a
 * slow password hash protects low-entropy secrets from guessing, and there is
 * nothing to guess here. It also keeps verification cheap on the ingest path.
 */
final class DeviceKeys {

    private static final String PREFIX = "agw_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** Constant-time comparison, so response timing does not leak how much of a key matched. */
    static boolean matches(String presentedKey, String storedHash) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presentedKey).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
