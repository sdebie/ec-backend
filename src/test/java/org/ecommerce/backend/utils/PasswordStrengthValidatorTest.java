package org.ecommerce.backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PasswordStrengthValidator.
 */
class PasswordStrengthValidatorTest
{
    @Test
    void validate_nullPassword_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PasswordStrengthValidator.validate(null));
        assertEquals("Password must be at least 8 characters", ex.getMessage());
    }

    @Test
    void validate_blankPassword_throws()
    {
        assertThrows(IllegalArgumentException.class, () -> PasswordStrengthValidator.validate(""));
    }

    @Test
    void validate_sevenCharacters_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PasswordStrengthValidator.validate("1234567"));
        assertEquals("Password must be at least 8 characters", ex.getMessage());
    }

    @Test
    void validate_eightCharacters_passes()
    {
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("12345678"));
    }

    @Test
    void validate_seventyTwoCharacters_passes()
    {
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("a".repeat(72)));
    }

    @Test
    void validate_seventyThreeCharacters_throws()
    {
        // BCrypt silently truncates input past 72 bytes; rejecting past that point
        // avoids two different long passwords hashing identically.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PasswordStrengthValidator.validate("a".repeat(73)));
        assertEquals("Password must be at most 72 characters", ex.getMessage());
    }

    @Test
    void validate_typicalPassword_passes()
    {
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("correct horse battery staple"));
    }
}
