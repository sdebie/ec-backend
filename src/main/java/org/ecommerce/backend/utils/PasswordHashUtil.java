package org.ecommerce.backend.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Utility class for password hashing and verification using SHA-256 hex digest.
 */
public final class PasswordHashUtil {

    private PasswordHashUtil() {
        // Non-instantiable utility class
    }

    /**
     * Hashes a password using SHA-256 and returns the hex digest.
     *
     * @param password the plain-text password to hash
     * @return the SHA-256 hex digest of the password
     */
    public static String hash(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    /**
     * Verifies a plain-text password against a stored hash.
     *
     * @param plain      the plain-text password to verify
     * @param storedHash the stored SHA-256 hex digest to compare against
     * @return true if the hash of plain matches storedHash, false otherwise
     */
    public static boolean verify(String plain, String storedHash) {
        if (plain == null || storedHash == null) return false;
        return hash(plain).equals(storedHash);
    }
}
