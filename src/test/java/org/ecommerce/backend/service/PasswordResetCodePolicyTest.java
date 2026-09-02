package org.ecommerce.backend.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ecommerce.backend.utils.PasswordHashUtil;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("PasswordResetCodePolicy — shared OTP mechanics for customer and staff resets")
class PasswordResetCodePolicyTest
{
    @Inject
    PasswordResetCodePolicy policy;

    // ── generateCode ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateCode produces a 6-digit numeric string")
    void generateCode_isSixDigits()
    {
        String code = policy.generateCode();
        assertTrue(Pattern.matches("\\d{6}", code), "expected 6 digits, got: " + code);
    }

    @Test
    @DisplayName("generateCode's leading digit is uniform over 1-9 (the range is 100000-999999, so a leading 0 is structurally impossible)")
    void generateCode_leadingDigitUniformOverOneToNine()
    {
        int sampleSize = 50_000;
        int[] leadingDigitCounts = new int[10];
        Set<String> distinctCodes = new HashSet<>();

        for (int i = 0; i < sampleSize; i++) {
            String code = policy.generateCode();
            distinctCodes.add(code);
            leadingDigitCounts[code.charAt(0) - '0']++;
        }

        assertEquals(0, leadingDigitCounts[0], "a 6-digit code in [100000, 999999] can never start with 0");

        // Every leading digit 1-9 should appear roughly sampleSize/9 times.
        // Modulo bias on a rejection-sampled SecureRandom would skew this heavily.
        int expected = sampleSize / 9;
        for (int digit = 1; digit <= 9; digit++) {
            int count = leadingDigitCounts[digit];
            double deviation = Math.abs(count - expected) / (double) expected;
            assertTrue(deviation < 0.15,
                    "leading digit " + digit + " appeared " + count + " times, expected ~" + expected);
        }

        // With 50k samples over a 900k-value space, collisions should be rare —
        // a large collision count would indicate a narrowed or biased range.
        assertTrue(distinctCodes.size() > sampleSize * 0.9,
                "expected mostly-distinct codes, got only " + distinctCodes.size() + " distinct out of " + sampleSize);
    }

    @Test
    @DisplayName("generateCode's trailing digit is uniform over 0-9")
    void generateCode_trailingDigitUniformOverZeroToNine()
    {
        int sampleSize = 50_000;
        int[] trailingDigitCounts = new int[10];

        for (int i = 0; i < sampleSize; i++) {
            String code = policy.generateCode();
            trailingDigitCounts[code.charAt(code.length() - 1) - '0']++;
        }

        int expected = sampleSize / 10;
        for (int digit = 0; digit <= 9; digit++) {
            int count = trailingDigitCounts[digit];
            double deviation = Math.abs(count - expected) / (double) expected;
            assertTrue(deviation < 0.15,
                    "trailing digit " + digit + " appeared " + count + " times, expected ~" + expected);
        }
    }

    // ── fingerprint / matches ────────────────────────────────────────────────

    @Test
    @DisplayName("fingerprint is not the bare SHA-256 digest of the code")
    void fingerprint_isNotBareSha256()
    {
        String code = "482913";
        String fingerprint = policy.fingerprint(code);
        String bareSha256 = PasswordHashUtil.hash(code);

        assertNotEquals(bareSha256, fingerprint,
                "the stored value must be keyed (HMAC), never the bare SHA-256 digest guarded by Requirement 9.1");
    }

    @Test
    @DisplayName("fingerprint is deterministic for the same code")
    void fingerprint_isDeterministic()
    {
        String code = "482913";
        assertEquals(policy.fingerprint(code), policy.fingerprint(code));
    }

    @Test
    @DisplayName("matches returns true for the correct code against its own fingerprint")
    void matches_correctCode_returnsTrue()
    {
        String code = "482913";
        String stored = policy.fingerprint(code);
        assertTrue(policy.matches(code, stored));
    }

    @Test
    @DisplayName("matches returns false for a wrong code")
    void matches_wrongCode_returnsFalse()
    {
        String stored = policy.fingerprint("482913");
        assertFalse(policy.matches("111111", stored));
    }

    @Test
    @DisplayName("matches returns false for malformed input (wrong length, non-numeric, null)")
    void matches_malformedInput_returnsFalse()
    {
        String stored = policy.fingerprint("482913");
        assertFalse(policy.matches("12345", stored));
        assertFalse(policy.matches("1234567", stored));
        assertFalse(policy.matches("abcdef", stored));
        assertFalse(policy.matches(null, stored));
        assertFalse(policy.matches("482913", null));
    }

    // ── isExpired ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isExpired is false for an expiry still in the future")
    void isExpired_futureExpiry_returnsFalse()
    {
        OffsetDateTime now = OffsetDateTime.now();
        assertFalse(policy.isExpired(now.plusMinutes(5), now));
    }

    @Test
    @DisplayName("isExpired is false exactly at the boundary, true just past it")
    void isExpired_boundary()
    {
        OffsetDateTime now = OffsetDateTime.now();
        // Preserves CustomerPasswordResetService's original !isBefore(now) semantics
        // exactly: a code expiring at this instant is still valid this instant.
        assertFalse(policy.isExpired(now, now));
        assertTrue(policy.isExpired(now.minusNanos(1), now));
    }

    @Test
    @DisplayName("isExpired is true for a null expiry")
    void isExpired_nullExpiry_returnsTrue()
    {
        assertTrue(policy.isExpired(null, OffsetDateTime.now()));
    }

    @Test
    @DisplayName("ttlMinutes is the single shared 5-minute constant")
    void ttlMinutes_isFive()
    {
        assertEquals(5, policy.ttlMinutes());
    }

    // ── isLocked ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isLocked is true while lockedUntil is in the future")
    void isLocked_futureLock_returnsTrue()
    {
        OffsetDateTime now = OffsetDateTime.now();
        assertTrue(policy.isLocked(now.plusMinutes(15), now));
    }

    @Test
    @DisplayName("isLocked is false once lockedUntil has passed")
    void isLocked_pastLock_returnsFalse()
    {
        OffsetDateTime now = OffsetDateTime.now();
        assertFalse(policy.isLocked(now.minusSeconds(1), now));
    }

    @Test
    @DisplayName("isLocked is false for a null lockedUntil")
    void isLocked_nullLockedUntil_returnsFalse()
    {
        assertFalse(policy.isLocked(null, OffsetDateTime.now()));
    }

    // ── shouldLock ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldLock is false below the 3-attempt threshold")
    void shouldLock_belowThreshold_returnsFalse()
    {
        assertFalse(policy.shouldLock(1));
        assertFalse(policy.shouldLock(2));
    }

    @Test
    @DisplayName("shouldLock is true at exactly the 3rd failed attempt and beyond")
    void shouldLock_atOrAboveThreshold_returnsTrue()
    {
        assertTrue(policy.shouldLock(3));
        assertTrue(policy.shouldLock(4));
    }
}
