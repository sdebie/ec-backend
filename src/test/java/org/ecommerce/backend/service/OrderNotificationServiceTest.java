package org.ecommerce.backend.service;

import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for the recipient/display-name resolution in
 * {@link OrderNotificationService}. The mail send itself (reactive I/O over a real
 * SMTP mailer) is framework plumbing and is not exercised here; the branch logic is.
 */
@DisplayName("OrderNotificationService — recipient & display-name resolution")
class OrderNotificationServiceTest {

    private final OrderNotificationService service = new OrderNotificationService();

    private OrderEntity orderWith(String firstName, String email) {
        OrderEntity order = new OrderEntity();
        CustomerEntity customer = new CustomerEntity();
        customer.firstName = firstName;
        if (email != null) {
            UserEntity user = new UserEntity();
            user.email = email;
            customer.user = user;
        }
        order.customerEntity = customer;
        return order;
    }

    @Test
    @DisplayName("display name uses the customer's first name when present")
    void displayNameUsesFirstName() {
        assertEquals("Alice", service.resolveDisplayName(orderWith("Alice", "a@test.co")));
    }

    @Test
    @DisplayName("display name falls back to Guest for blank/null first name and null customer")
    void displayNameFallsBackToGuest() {
        assertEquals("Guest", service.resolveDisplayName(orderWith("   ", "a@test.co")));
        assertEquals("Guest", service.resolveDisplayName(orderWith(null, "a@test.co")));
        OrderEntity noCustomer = new OrderEntity();
        assertEquals("Guest", service.resolveDisplayName(noCustomer));
    }

    @Test
    @DisplayName("recipient resolves from customer→user email")
    void recipientFromUserEmail() {
        assertEquals("a@test.co", service.resolveRecipient(orderWith("Alice", "a@test.co")));
    }

    @Test
    @DisplayName("recipient is null when customer or user is missing")
    void recipientNullWhenMissing() {
        assertNull(service.resolveRecipient(orderWith("Alice", null))); // null user
        OrderEntity noCustomer = new OrderEntity();
        assertNull(service.resolveRecipient(noCustomer));
    }
}
