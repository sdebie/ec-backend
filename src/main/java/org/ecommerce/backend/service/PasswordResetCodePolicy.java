package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * Shared, entity-agnostic OTP mechanics for both {@link CustomerPasswordResetService}
 * and the staff self-service reset flow: code generation, keyed fingerprinting,
 * expiry, and lockout-threshold decisions. Holds no persistence — callers own their
 * own entity reads/writes and post-conditions.
 * <p>
 * Codes are fingerprinted with HMAC-SHA256 rather than a bare digest: with only
 * ~900,000 possible 6-digit codes, a bare unsalted hash (the legacy
 * {@code PasswordHashUtil} scheme) is recoverable from a single database read in
 * milliseconds. Keying the digest with a server-side secret means a database read alone
 * is not enough to brute-force a live code.
 */
@ApplicationScoped
public class PasswordResetCodePolicy
{
    private static final int CODE_LENGTH = 6;
    private static final int TTL_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @ConfigProperty(name = "password-reset.code.hmac-secret")
    String hmacSecret;

    /**
     * Generates a 6-digit numeric code via {@link SecureRandom#nextInt(int)}, which
     * rejection-samples internally, so the result is uniform over its range with no
     * modulo bias.
     */
    public String generateCode()
    {
        int floor = (int) Math.pow(10, CODE_LENGTH - 1);
        int bound = floor * 9;
        int code = floor + SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", code);
    }

    /**
     * HMAC-SHA256 of the raw code, keyed with the configured secret. Deliberately
     * named {@code fingerprint}, not {@code hash}, to keep it distinct from
     * {@code PasswordHashUtil.hash} — the unsalted digest this policy exists to stop
     * using for reset codes.
     */
    public String fingerprint(String rawCode)
    {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(rawCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint reset code", e);
        }
    }

    /**
     * Constant-time comparison of a submitted code against its stored fingerprint.
     * Rejects malformed input (wrong length, non-numeric, null) without throwing.
     */
    public boolean matches(String rawCode, String stored)
    {
        if (rawCode == null || stored == null || !isValidFormat(rawCode)) {
            return false;
        }
        return MessageDigest.isEqual(
                fingerprint(rawCode.trim()).getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isExpired(OffsetDateTime expiry, OffsetDateTime now)
    {
        return expiry == null || expiry.isBefore(now);
    }

    public boolean isLocked(OffsetDateTime lockedUntil, OffsetDateTime now)
    {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean shouldLock(int attemptsAfterFailure)
    {
        return attemptsAfterFailure >= MAX_ATTEMPTS;
    }

    /**
     * The single TTL shared by both the customer and staff flows (Requirement 7.3) —
     * changing it is one edit affecting both.
     */
    public int ttlMinutes()
    {
        return TTL_MINUTES;
    }

    private static boolean isValidFormat(String code)
    {
        String trimmed = code.trim();
        if (trimmed.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
