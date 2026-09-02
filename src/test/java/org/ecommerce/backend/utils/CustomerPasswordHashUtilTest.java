package org.ecommerce.backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CustomerPasswordHashUtil.
 */
class CustomerPasswordHashUtilTest
{
    @Test
    void hash_returnsBcryptFormat()
    {
        String hashed = CustomerPasswordHashUtil.hash("password123");

        assertNotNull(hashed);
        assertTrue(hashed.startsWith("$2"), "BCrypt hashes are self-describing via a $2 prefix");
    }

    @Test
    void hash_samePlaintext_producesDifferentHashesEachTime()
    {
        String result1 = CustomerPasswordHashUtil.hash("password123");
        String result2 = CustomerPasswordHashUtil.hash("password123");

        assertNotEquals(result1, result2, "BCrypt salts each hash, so repeated hashing of the same input must differ");
    }

    @Test
    void verify_matchingPasswordAgainstBcryptHash_returnsTrue()
    {
        String hashed = CustomerPasswordHashUtil.hash("password123");

        assertTrue(CustomerPasswordHashUtil.verify("password123", hashed));
    }

    @Test
    void verify_nonMatchingPasswordAgainstBcryptHash_returnsFalse()
    {
        String hashed = CustomerPasswordHashUtil.hash("password123");

        assertFalse(CustomerPasswordHashUtil.verify("wrong", hashed));
    }

    @Test
    void verify_matchingPasswordAgainstLegacySha256Hash_returnsTrue()
    {
        // Backward compatibility: accounts hashed before this class existed store a
        // raw SHA-256 hex digest (PasswordHashUtil's format). Login must keep working
        // for them until rehash-on-login upgrades the stored hash.
        String legacyHash = PasswordHashUtil.hash("oldStylePassword");

        assertTrue(CustomerPasswordHashUtil.verify("oldStylePassword", legacyHash));
    }

    @Test
    void verify_nonMatchingPasswordAgainstLegacySha256Hash_returnsFalse()
    {
        String legacyHash = PasswordHashUtil.hash("oldStylePassword");

        assertFalse(CustomerPasswordHashUtil.verify("wrong", legacyHash));
    }

    @Test
    void verify_nullPlaintext_returnsFalse()
    {
        String hashed = CustomerPasswordHashUtil.hash("password123");

        assertFalse(CustomerPasswordHashUtil.verify(null, hashed));
    }

    @Test
    void verify_nullStoredHash_returnsFalse()
    {
        assertFalse(CustomerPasswordHashUtil.verify("password123", null));
    }

    @Test
    void verify_emptyStoredHash_returnsFalse()
    {
        // The "" sentinel marks accounts with no local password (Google/wholesale-created).
        assertFalse(CustomerPasswordHashUtil.verify("password123", ""));
    }

    @Test
    void isLegacyHash_sha256Digest_returnsTrue()
    {
        assertTrue(CustomerPasswordHashUtil.isLegacyHash(PasswordHashUtil.hash("anything")));
    }

    @Test
    void isLegacyHash_bcryptHash_returnsFalse()
    {
        assertFalse(CustomerPasswordHashUtil.isLegacyHash(CustomerPasswordHashUtil.hash("anything")));
    }

    @Test
    void isLegacyHash_emptySentinel_returnsTrue()
    {
        // "" never matches CustomerPasswordHashUtil.verify regardless of which branch
        // isLegacyHash routes it to, but it must route somewhere without throwing.
        assertTrue(CustomerPasswordHashUtil.isLegacyHash(""));
    }

    @Test
    void isLegacyHash_null_returnsFalse()
    {
        assertFalse(CustomerPasswordHashUtil.isLegacyHash(null));
    }
}
