package org.ecommerce.backend.utils;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests for CustomerPasswordHashUtil, mirroring PasswordHashPropertyTest's
 * shape for the BCrypt-backed replacement: round-trip, cross-password mismatch, and
 * the legacy-format backward-compatibility guarantee rehash-on-login depends on.
 */
class CustomerPasswordHashPropertyTest
{
    @Property(tries = 30)
    void hashThenVerifyWithSamePasswordReturnsTrue(
            @ForAll("passwords") String password
    )
    {
        String hashed = CustomerPasswordHashUtil.hash(password);

        assertTrue(CustomerPasswordHashUtil.verify(password, hashed),
                "verify(password, hash(password)) should return true for any password");
    }

    @Property(tries = 30)
    void verifyWithDifferentPasswordReturnsFalse(
            @ForAll("passwords") String a,
            @ForAll("passwords") String b
    )
    {
        Assume.that(!a.equals(b));

        String hashedB = CustomerPasswordHashUtil.hash(b);

        assertFalse(CustomerPasswordHashUtil.verify(a, hashedB),
                "verify(a, hash(b)) should return false when a != b");
    }

    @Property(tries = 30)
    void verifyAcceptsLegacyHashOfSamePassword(
            @ForAll("passwords") String password
    )
    {
        String legacyHash = PasswordHashUtil.hash(password);

        assertTrue(CustomerPasswordHashUtil.verify(password, legacyHash),
                "A pre-migration SHA-256 hash must still verify until rehash-on-login upgrades it");
    }

    @Provide
    Arbitrary<String> passwords()
    {
        // Same shape as PasswordHashPropertyTest.passwords(), bounded well under
        // BCrypt's 72-byte effective input limit so no case is silently truncated.
        Arbitrary<String> alpha = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1).ofMaxLength(15);

        Arbitrary<String> numeric = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(1).ofMaxLength(10);

        Arbitrary<String> special = Arbitraries.strings()
                .withChars('!', '@', '#', '$', '%', '^', '&', '*')
                .ofMinLength(1).ofMaxLength(10);

        return Combinators.combine(alpha, numeric, special)
                .as((a, n, s) -> a + n + s)
                .filter(combined -> combined.length() >= 8 && combined.length() <= 35);
    }
}
