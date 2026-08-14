package org.ecommerce.backend.utils;

// Feature: customer-portal-backend, Property 7: Password Hash Round-Trip

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property 7: Password Hash Round-Trip
 * <p>
 * For any password string of length >= 8, PasswordHashUtil.verify(password, PasswordHashUtil.hash(password))
 * SHALL return true. For any two distinct passwords a and b,
 * PasswordHashUtil.verify(a, PasswordHashUtil.hash(b)) SHALL return false.
 * <p>
 */
class PasswordHashPropertyTest
{
    /**
     * Property: For any password string of length 8-128 (including special chars and unicode),
     * hashing and then verifying with the same password returns true.
     */
    @Property(tries = 100)
    void hashThenVerifyWithSamePasswordReturnsTrue(
            @ForAll("passwords") String password
    )
    {
        String hashed = PasswordHashUtil.hash(password);

        assertTrue(PasswordHashUtil.verify(password, hashed),
                "verify(password, hash(password)) should return true for any password");
    }

    /**
     * Property: For any two distinct passwords a and b,
     * verifying a against the hash of b returns false.
     */
    @Property(tries = 100)
    void verifyWithDifferentPasswordReturnsFalse(
            @ForAll("passwords") String a,
            @ForAll("passwords") String b
    )
    {
        Assume.that(!a.equals(b));

        String hashedB = PasswordHashUtil.hash(b);

        assertFalse(PasswordHashUtil.verify(a, hashedB),
                "verify(a, hash(b)) should return false when a != b");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> passwords()
    {
        // Generate strings of length 8-128 including alpha, numeric, special chars, and unicode
        Arbitrary<String> alpha = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1).ofMaxLength(20);

        Arbitrary<String> numeric = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(1).ofMaxLength(10);

        Arbitrary<String> special = Arbitraries.strings()
                .withChars('!', '@', '#', '$', '%', '^', '&', '*')
                .ofMinLength(1).ofMaxLength(10);

        Arbitrary<String> unicode = Arbitraries.strings()
                .withCharRange('À', 'ÿ')  // Latin Extended (accented chars)
                .withCharRange('Ѐ', 'ӿ')  // Cyrillic
                .withCharRange('一', '乐')  // CJK subset
                .ofMinLength(1).ofMaxLength(10);

        // Combine segments and constrain total length to 8-128
        return Combinators.combine(alpha, numeric, special, unicode)
                .as((a, n, s, u) -> a + n + s + u)
                .filter(combined -> combined.length() >= 8 && combined.length() <= 128);
    }
}
