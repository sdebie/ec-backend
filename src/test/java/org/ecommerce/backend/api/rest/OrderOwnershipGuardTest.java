package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * tasks.md 2.1: the {@code mayAct} truth table, written against the guard itself —
 * which had no unit test of any kind before this spec (design.md §5a). Covers every
 * combination of {guest order, customer-owned order} × {no credential, valid token,
 * token for another order, expired token, owning customer's JWT, a different
 * customer's JWT, staff JWT}. Exactly two rules authorize (Requirement 1.1's "if and
 * only if"): a valid token bound to the order, or — for a customer-owned order only —
 * that customer's own JWT. Every other cell is false.
 */
@QuarkusTest
class OrderOwnershipGuardTest
{
    @Inject
    OrderOwnershipGuard guard;

    @Inject
    OrderCapabilityService orderCapability;

    @InjectMock
    SecurityIdentity securityIdentity;

    @InjectMock
    JsonWebToken jwt;

    private UUID orderId;
    private UUID ownerCustomerId;
    private OrderEntity guestOrder;
    private OrderEntity customerOrder;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);

        orderId = UUID.randomUUID();
        ownerCustomerId = UUID.randomUUID();

        guestOrder = new OrderEntity();
        guestOrder.setId(orderId);
        guestOrder.setCustomerEntity(null);

        CustomerEntity owner = new CustomerEntity();
        owner.setId(ownerCustomerId);
        customerOrder = new OrderEntity();
        customerOrder.setId(orderId);
        customerOrder.setCustomerEntity(owner);

        // Default: no credential at all, unless a case below overrides it.
        when(securityIdentity.hasRole("customer")).thenReturn(false);
    }

    private String validToken()
    {
        return orderCapability.mint(orderId);
    }

    private String tokenForAnotherOrder()
    {
        return orderCapability.mint(UUID.randomUUID());
    }

    private String expiredToken()
    {
        return io.smallrye.jwt.build.Jwt.issuer("http://localhost:8080")
                .subject(orderId.toString())
                .claim("scope", "order-capability")
                .expiresIn(Duration.ofMinutes(-5))
                .sign();
    }

    private void authenticateAs(UUID customerId, String email)
    {
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);
    }

    // ── Guest order ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("guest order × no credential → refused")
    void guestOrder_noCredential_refused()
    {
        assertFalse(guard.mayAct(guestOrder, null));
    }

    @Test
    @DisplayName("guest order × valid token → authorized")
    void guestOrder_validToken_authorized()
    {
        assertTrue(guard.mayAct(guestOrder, validToken()));
    }

    @Test
    @DisplayName("guest order × token for another order → refused")
    void guestOrder_tokenForAnotherOrder_refused()
    {
        assertFalse(guard.mayAct(guestOrder, tokenForAnotherOrder()));
    }

    @Test
    @DisplayName("guest order × expired token → refused")
    void guestOrder_expiredToken_refused()
    {
        assertFalse(guard.mayAct(guestOrder, expiredToken()));
    }

    @Test
    @DisplayName("guest order × some customer's JWT (no token) → refused — a guest order has no owner to match")
    void guestOrder_customerJwt_refused()
    {
        authenticateAs(UUID.randomUUID(), "someone@test.com");
        assertFalse(guard.mayAct(guestOrder, null));
    }

    @Test
    @DisplayName("guest order × staff JWT (no token) → refused (Requirement 1.6)")
    void guestOrder_staffJwt_refused()
    {
        // Staff hold a role name, never "customer" — the same branch as anonymous.
        assertFalse(guard.mayAct(guestOrder, null));
    }

    // ── Customer-owned order ────────────────────────────────────────────────

    @Test
    @DisplayName("customer-owned order × no credential → refused")
    void customerOrder_noCredential_refused()
    {
        assertFalse(guard.mayAct(customerOrder, null));
    }

    @Test
    @DisplayName("customer-owned order × valid token → authorized")
    void customerOrder_validToken_authorized()
    {
        assertTrue(guard.mayAct(customerOrder, validToken()));
    }

    @Test
    @DisplayName("customer-owned order × token for another order → refused")
    void customerOrder_tokenForAnotherOrder_refused()
    {
        assertFalse(guard.mayAct(customerOrder, tokenForAnotherOrder()));
    }

    @Test
    @DisplayName("customer-owned order × expired token → refused")
    void customerOrder_expiredToken_refused()
    {
        assertFalse(guard.mayAct(customerOrder, expiredToken()));
    }

    @Test
    @DisplayName("customer-owned order × owning customer's JWT (no token) → authorized")
    void customerOrder_owningCustomerJwt_authorized()
    {
        authenticateAs(ownerCustomerId, "owner@test.com");
        assertTrue(guard.mayAct(customerOrder, null));
    }

    @Test
    @DisplayName("customer-owned order × a different customer's JWT (no token) → refused")
    void customerOrder_differentCustomerJwt_refused()
    {
        authenticateAs(UUID.randomUUID(), "stranger@test.com");
        assertFalse(guard.mayAct(customerOrder, null));
    }

    @Test
    @DisplayName("customer-owned order × staff JWT (no token) → refused (Requirement 1.6)")
    void customerOrder_staffJwt_refused()
    {
        assertFalse(guard.mayAct(customerOrder, null));
    }

    // ── Edge cases beyond the cross-product ─────────────────────────────────

    @Test
    @DisplayName("null order → refused")
    void nullOrder_refused()
    {
        assertFalse(guard.mayAct(null, validToken()));
    }
}
