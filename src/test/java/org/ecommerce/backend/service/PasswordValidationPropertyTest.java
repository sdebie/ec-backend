package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 8: Password Minimum Length Validation

import jakarta.ws.rs.WebApplicationException;
import net.jqwik.api.*;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 8: Password Minimum Length Validation
 *
 * For any string with length < 8, the password change operation SHALL reject it
 * with HTTP 400 and message "Password must be at least 8 characters".
 * For any string with length >= 8 (given correct current password), the operation
 * SHALL accept it without throwing.
 *
 * This test validates the password length validation logic in CustomerPortalService.changePassword()
 * by mocking the static entity finder and testing the validation gate directly.
 *
 * Validates: Requirements 5.5
 */
class PasswordValidationPropertyTest {

    private static final String TEST_EMAIL = "test@example.com";
    private static final String CURRENT_PASSWORD = "current123";
    private static final String CURRENT_PASSWORD_HASH = PasswordHashUtil.hash("current123");

    private MockedStatic<CustomerEntity> mockedCustomerEntity;
    private CustomerPortalService service;

    @BeforeTry
    void setup() {
        service = new CustomerPortalService();
        mockedCustomerEntity = Mockito.mockStatic(CustomerEntity.class);

        // Set up a customer with a known password hash
        CustomerEntity customer = new CustomerEntity();
        customer.id = UUID.randomUUID();
        customer.firstName = "Test";
        customer.lastName = "User";
        customer.addresses = new java.util.ArrayList<>();

        UserEntity user = Mockito.mock(UserEntity.class);
        user.id = UUID.randomUUID();
        user.email = TEST_EMAIL;
        user.passwordHash = CURRENT_PASSWORD_HASH;
        // Mock persist() to do nothing (avoid transaction issues in test context)
        Mockito.doNothing().when(user).persist();
        customer.user = user;

        mockedCustomerEntity.when(() -> CustomerEntity.findByEmail(TEST_EMAIL)).thenReturn(customer);
    }

    @AfterTry
    void teardown() {
        if (mockedCustomerEntity != null) {
            mockedCustomerEntity.close();
        }
    }

    /**
     * Validates: Requirements 5.5
     *
     * For any new password with length < 8, changePassword SHALL throw
     * a WebApplicationException with status 400 and message "Password must be at least 8 characters".
     */
    @Property(tries = 100)
    void passwordsShorterThan8CharactersAreRejected(
            @ForAll("shortPasswords") String newPassword
    ) {
        assertTrue(newPassword.length() < 8,
                "Test precondition: password must be shorter than 8 characters");

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.changePassword(TEST_EMAIL, CURRENT_PASSWORD, newPassword));

        assertEquals(400, ex.getResponse().getStatus(),
                "Short passwords should be rejected with HTTP 400");
    }

    /**
     * Validates: Requirements 5.5
     *
     * For any new password with length >= 8 (given correct current password),
     * changePassword SHALL accept it without throwing an exception.
     */
    @Property(tries = 100)
    void passwordsWithAtLeast8CharactersAreAccepted(
            @ForAll("validPasswords") String newPassword
    ) {
        assertTrue(newPassword.length() >= 8,
                "Test precondition: password must be at least 8 characters");

        assertDoesNotThrow(
                () -> service.changePassword(TEST_EMAIL, CURRENT_PASSWORD, newPassword),
                "Passwords with length >= 8 should be accepted when current password is correct");
    }

    /**
     * Validates: Requirements 5.5
     *
     * Null new password should be rejected with HTTP 400.
     */
    @Property(tries = 10)
    void nullPasswordIsRejected() {
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.changePassword(TEST_EMAIL, CURRENT_PASSWORD, null));

        assertEquals(400, ex.getResponse().getStatus(),
                "Null password should be rejected with HTTP 400");
    }

    @Provide
    Arbitrary<String> shortPasswords() {
        // Generate random strings of length 0-7
        return Arbitraries.integers().between(0, 7)
                .flatMap(length -> {
                    if (length == 0) {
                        return Arbitraries.just("");
                    }
                    return Arbitraries.strings()
                            .withCharRange('a', 'z')
                            .withCharRange('A', 'Z')
                            .withCharRange('0', '9')
                            .withChars('!', '@', '#', '$', '%', '^', '&', '*')
                            .ofLength(length);
                });
    }

    @Provide
    Arbitrary<String> validPasswords() {
        // Generate random strings of length 8-100
        return Arbitraries.integers().between(8, 100)
                .flatMap(length -> Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .withCharRange('A', 'Z')
                        .withCharRange('0', '9')
                        .withChars('!', '@', '#', '$', '%', '^', '&', '*')
                        .ofLength(length));
    }
}
