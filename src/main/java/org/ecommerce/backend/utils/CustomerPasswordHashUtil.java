package org.ecommerce.backend.utils;

import io.quarkus.elytron.security.common.BcryptUtil;

/**
 * Hashes and verifies customer passwords using BCrypt (the scheme staff accounts
 * already use via {@link BcryptUtil} — see AdminAuthService/StaffService).
 * <p>
 * {@link #verify} also accepts the legacy unsalted SHA-256 digests {@link PasswordHashUtil}
 * wrote before this class existed, so existing accounts keep authenticating; callers
 * that successfully verify against a legacy hash should re-hash and persist it via
 * {@link #hash} (see the login rehash-on-login flow in {@code CustomerResource}) —
 * {@link #isLegacyHash} tells them when that's needed. BCrypt hashes are self-describing
 * via their {@code $2} prefix, so no schema change or version column is required.
 */
public final class CustomerPasswordHashUtil {

    private CustomerPasswordHashUtil() {
    }

    /**
     * Hashes a password with BCrypt.
     *
     * @param password the plain-text password to hash
     * @return a salted BCrypt hash (never equal across calls, even for the same input)
     */
    public static String hash(String password) {
        return BcryptUtil.bcryptHash(password);
    }

    /**
     * @param storedHash a stored password hash
     * @return true if storedHash is a pre-migration SHA-256 digest rather than a BCrypt hash
     */
    public static boolean isLegacyHash(String storedHash) {
        return storedHash != null && !storedHash.startsWith("$2");
    }

    /**
     * Verifies a plain-text password against a stored hash, whichever format it's in.
     *
     * @param plain      the plain-text password to verify
     * @param storedHash the stored hash to compare against (BCrypt or legacy SHA-256)
     * @return true if plain matches storedHash, false otherwise
     */
    public static boolean verify(String plain, String storedHash) {
        if (plain == null || storedHash == null) return false;
        if (isLegacyHash(storedHash)) {
            return PasswordHashUtil.verify(plain, storedHash);
        }
        return BcryptUtil.matches(plain, storedHash);
    }
}
