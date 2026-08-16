package org.ecommerce.backend.service;

// Feature: guest-order-authorization, Property 4 (repointed): mayAct ownership enforcement

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.api.rest.OrderOwnershipGuard;
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
 * Drives the real {@link OrderOwnershipGuard#mayAct}, not a re-implementation.
 * <p>
 * The class this replaces declared its own private {@code enforceOwnership(order,
 * customer)} — a simulator that imported no guard, resource or service and so could
 * never fail for any change made to production code (design.md §5a). It is repointed
 * here at the real guard, mocking only {@code SecurityIdentity}/{@code JsonWebToken}
 * (the guard's own CDI dependencies), exactly as {@code OrderResourceGetOrderDetailTest}
 * already does for the resource layer.
 * <p>
 * jqwik's {@code @Property} does not compose with {@code @QuarkusTest} — jqwik runs as
 * its own JUnit Platform engine and does not process JUnit Jupiter's
 * {@code @ExtendWith}-based CDI injection, and no other test in this codebase combines
 * the two. The "many random cases" value of the original property tests is kept via a
 * plain loop of random UUIDs inside ordinary {@code @Test} methods instead.
 */
@QuarkusTest
class OrderDetailOwnershipPropertyTest
{
    private static final int TRIES = 100;

    @Inject
    OrderOwnershipGuard guard;

    @InjectMock
    SecurityIdentity securityIdentity;

    @InjectMock
    JsonWebToken jwt;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);
    }

    /** Mirrors design.md §3.1 without depending on OrderCapabilityService, which does not exist until wave 1. */
    private String generateOrderToken(UUID orderId)
    {
        return Jwt.issuer("http://localhost:8080")
                .subject(orderId.toString())
                .claim("scope", "order-capability")
                .expiresIn(Duration.ofMinutes(60))
                .sign();
    }

    private void authenticateAsCustomer(UUID customerId, String email)
    {
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);
    }

    private void authenticateAsNobody()
    {
        when(securityIdentity.hasRole("customer")).thenReturn(false);
    }

    /**
     * For any pair of distinct UUIDs (authenticated customer, order's actual owner),
     * a different customer's JWT and no token together must always be refused.
     */
    @Test
    @DisplayName("a different customer's JWT, with no token, never authorizes (Requirement 1.1)")
    void ownershipMismatch_noToken_alwaysRefused()
    {
        for (int i = 0; i < TRIES; i++) {
            UUID authenticatedCustomerId = UUID.randomUUID();
            UUID orderOwnerCustomerId = UUID.randomUUID();
            String email = "customer-" + authenticatedCustomerId + "@test.com";

            authenticateAsCustomer(authenticatedCustomerId, email);

            OrderEntity order = new OrderEntity();
            order.setId(UUID.randomUUID());
            order.setCustomerEntity(new CustomerEntity());
            order.getCustomerEntity().setId(orderOwnerCustomerId);

            assertFalse(guard.mayAct(order, null),
                    "customer " + authenticatedCustomerId + " must not reach order owned by " + orderOwnerCustomerId);
        }
    }

    /**
     * This is the property that inverts under the new model (tasks.md 0.3b): a guest
     * order (no linked customer) used to always refuse an authenticated customer, and
     * still must. But it must now succeed for ANYONE — authenticated or not — holding a
     * valid token for that exact order, since a guest order is reachable by token alone.
     */
    @Test
    @DisplayName("a guest order refuses any customer JWT and any absent token, but authorizes a valid token (Requirement 1.1, 2.8)")
    void guestOrder_needsAToken_notACustomerJwt()
    {
        for (int i = 0; i < TRIES; i++) {
            UUID authenticatedCustomerId = UUID.randomUUID();
            String email = "customer-" + authenticatedCustomerId + "@test.com";

            OrderEntity order = new OrderEntity();
            UUID orderId = UUID.randomUUID();
            order.setId(orderId);
            order.setCustomerEntity(null);

            // No customer JWT, and no token — refused.
            authenticateAsNobody();
            assertFalse(guard.mayAct(order, null), "a guest order must refuse a bare, credential-less request");

            // Some other customer's JWT, and no token — still refused. Being signed in as
            // anyone does not stand in for the token a guest order requires.
            authenticateAsCustomer(authenticatedCustomerId, email);
            assertFalse(guard.mayAct(order, null),
                    "a guest order must refuse a customer JWT that names no relationship to it");

            // A valid token for THIS order — authorized, regardless of who (if anyone) is signed in.
            assertTrue(guard.mayAct(order, generateOrderToken(orderId)),
                    "a guest order must authorize possession of its own valid capability token");
        }
    }

    /**
     * The owning customer's own JWT continues to authorize with no token at all —
     * mayAct's second disjunct (Requirement 1.1).
     */
    @Test
    @DisplayName("the order's own customer JWT authorizes with no token (Requirement 1.1)")
    void ownershipMatch_ownJwt_neverRefused()
    {
        for (int i = 0; i < TRIES; i++) {
            UUID customerId = UUID.randomUUID();
            String email = "customer-" + customerId + "@test.com";

            authenticateAsCustomer(customerId, email);

            OrderEntity order = new OrderEntity();
            order.setId(UUID.randomUUID());
            order.setCustomerEntity(new CustomerEntity());
            order.getCustomerEntity().setId(customerId);

            assertTrue(guard.mayAct(order, null),
                    "customer " + customerId + " must be able to reach their own order with no token");
        }
    }
}
