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
class OrderNotificationServiceTest
{
    private final OrderNotificationService service = new OrderNotificationService();

    private OrderEntity orderWith(String firstName, String email)
    {
        OrderEntity order = new OrderEntity();
        CustomerEntity customer = new CustomerEntity();
        customer.setFirstName(firstName);
        if (email != null) {
            UserEntity user = new UserEntity();
            user.setEmail(email);
            customer.setUser(user);
        }
        order.setCustomerEntity(customer);
        return order;
    }

    @Test
    @DisplayName("display name uses the customer's first name when present")
    void displayNameUsesFirstName()
    {
        assertEquals("Alice", service.resolveDisplayName(orderWith("Alice", "a@test.co")));
    }

    @Test
    @DisplayName("display name falls back to Guest for blank/null first name and null customer")
    void displayNameFallsBackToGuest()
    {
        assertEquals("Guest", service.resolveDisplayName(orderWith("   ", "a@test.co")));
        assertEquals("Guest", service.resolveDisplayName(orderWith(null, "a@test.co")));
        OrderEntity noCustomer = new OrderEntity();
        assertEquals("Guest", service.resolveDisplayName(noCustomer));
    }

    @Test
    @DisplayName("recipient resolves from customer→user email")
    void recipientFromUserEmail()
    {
        assertEquals("a@test.co", service.resolveRecipient(orderWith("Alice", "a@test.co")));
    }

    @Test
    @DisplayName("recipient is null when customer or user is missing")
    void recipientNullWhenMissing()
    {
        assertNull(service.resolveRecipient(orderWith("Alice", null))); // null user
        OrderEntity noCustomer = new OrderEntity();
        assertNull(service.resolveRecipient(noCustomer));
    }

    // ── Guest / contact-fallback coverage (bug #3 fix) ──────────────────────
    // customerEntity was always null before the customer-linking fix, so these
    // fallback paths never actually ran in production; they're new coverage,
    // not a regression check on existing behavior.

    private OrderEntity orderWithContactOnly(String contactFirstName, String contactEmail)
    {
        OrderEntity order = new OrderEntity();
        order.setContactFirstName(contactFirstName);
        order.setContactEmail(contactEmail);
        return order;
    }

    /**
     * customerEntity is present but unusable (no user, or a user with a blank
     * email) — distinct from the "no customerEntity at all" case above.
     */
    private OrderEntity orderWithUnusableCustomerAndContactFallback(String userEmail, String contactEmail)
    {
        OrderEntity order = new OrderEntity();
        CustomerEntity customer = new CustomerEntity();
        customer.setFirstName(null);
        if (userEmail != null) {
            UserEntity user = new UserEntity();
            user.setEmail(userEmail);
            customer.setUser(user);
        }
        order.setCustomerEntity(customer);
        order.setContactEmail(contactEmail);
        return order;
    }

    @Test
    @DisplayName("recipient falls back to contactEmail when there is no customerEntity at all (guest checkout)")
    void recipientFallsBackToContactEmail_noCustomerEntity()
    {
        assertEquals("guest@x.com", service.resolveRecipient(orderWithContactOnly("Guestperson", "guest@x.com")));
    }

    @Test
    @DisplayName("recipient falls back to contactEmail when customerEntity exists but has no usable email")
    void recipientFallsBackToContactEmail_customerHasNoUsableEmail()
    {
        // customer present, user is null
        assertEquals("guest@x.com",
                service.resolveRecipient(orderWithUnusableCustomerAndContactFallback(null, "guest@x.com")));
        // customer present, user present, but email is blank
        assertEquals("guest@x.com",
                service.resolveRecipient(orderWithUnusableCustomerAndContactFallback("   ", "guest@x.com")));
    }

    @Test
    @DisplayName("display name falls back to contactFirstName when there is no customerEntity at all (guest checkout)")
    void displayNameFallsBackToContactFirstName_noCustomerEntity()
    {
        assertEquals("Jane", service.resolveDisplayName(orderWithContactOnly("Jane", null)));
    }

    @Test
    @DisplayName("true last resort: no customer and no contact info at all")
    void trueLastResort_noCustomerNoContactInfo()
    {
        OrderEntity order = new OrderEntity();
        assertNull(service.resolveRecipient(order));
        assertEquals("Guest", service.resolveDisplayName(order));
    }
}
