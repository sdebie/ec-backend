package org.ecommerce.backend.utils;

/**
 * Minimum password strength gate for customer-set passwords (registration, reset
 * completion, self-service change). Length only, matching NIST 800-63B's
 * minimum-length-over-complexity-rules guidance. The upper bound exists because
 * BCrypt silently truncates input past 72 bytes, which would otherwise let two
 * different long passwords hash identically.
 */
public final class PasswordStrengthValidator {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 72;

    private PasswordStrengthValidator() {
    }

    /**
     * @param password the plain-text password to check
     * @throws IllegalArgumentException if password is null or outside [8, 72] characters
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Password must be at most " + MAX_LENGTH + " characters");
        }
    }
}
