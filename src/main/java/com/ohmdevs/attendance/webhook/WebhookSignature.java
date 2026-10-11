package com.ohmdevs.attendance.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generates signing secrets and signs requests.
 *
 * <p>The signature covers the timestamp as well as the body, so a receiver
 * that rejects old timestamps cannot be replayed a captured request later.
 * Documented for receivers in {@code docs/webhooks.md}.
 */
final class WebhookSignature {

    static final String HEADER = "X-Attendance-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSignature() {
    }

    static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The header value: {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">}. */
    static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + sign(secret, timestamp, body);
    }

    static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", e);
        }
    }
}
