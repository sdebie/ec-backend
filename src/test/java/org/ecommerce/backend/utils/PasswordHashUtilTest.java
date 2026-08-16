package org.ecommerce.backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PasswordHashUtil.
 */
class PasswordHashUtilTest
{
    @Test
    void hash_samePlaintext_returnsSameResult()
    {
        String result1 = PasswordHashUtil.hash("password123");
        String result2 = PasswordHashUtil.hash("password123");

        assertEquals(result1, result2, "Hashing the same input must produce the same output");
    }

    @Test
    void hash_returnsSha256HexString()
    {
        String hashed = PasswordHashUtil.hash("password123");

        assertNotNull(hashed);
        assertEquals(64, hashed.length(), "SHA-256 produces a 64-char hex string");
        assertTrue(hashed.matches("[0-9a-f]{64}"), "Hash must be lowercase hex characters only");
    }

    @Test
    void verify_matchingPassword_returnsTrue()
    {
        String hashed = PasswordHashUtil.hash("password123");

        assertTrue(PasswordHashUtil.verify("password123", hashed));
    }

    @Test
    void verify_nonMatchingPassword_returnsFalse()
    {
        String hashed = PasswordHashUtil.hash("password123");

        assertFalse(PasswordHashUtil.verify("wrong", hashed));
    }

    @Test
    void verify_nullPlaintext_returnsFalse()
    {
        String hashed = PasswordHashUtil.hash("password123");

        assertFalse(PasswordHashUtil.verify(null, hashed));
    }

    @Test
    void verify_nullStoredHash_returnsFalse()
    {
        assertFalse(PasswordHashUtil.verify("password123", null));
    }

    @Test
    void hash_differentInputs_produceDifferentHashes()
    {
        String hashA = PasswordHashUtil.hash("a");
        String hashB = PasswordHashUtil.hash("b");

        assertNotEquals(hashA, hashB, "Different inputs must produce different hashes");
    }

    @Test
    void verify_specialCharacters_roundTripsCorrectly()
    {
        String specialPassword = "p@$$w0rd!";
        String hashed = PasswordHashUtil.hash(specialPassword);

        assertTrue(PasswordHashUtil.verify(specialPassword, hashed), "Special characters must hash and verify correctly");
    }
}
