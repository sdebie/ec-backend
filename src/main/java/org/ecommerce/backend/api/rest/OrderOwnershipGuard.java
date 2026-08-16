package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;

/**
 * Decides whether the caller may act on a given order during checkout.
 * <p>
 * Guest checkout is deliberately unrestricted: an order's UUID is the bearer
 * capability that stands in for a session, so anyone holding it may complete
 * the checkout it belongs to (see KNOWN-LIMITATIONS.md §4). A customer JWT is
 * a different matter — someone signed in must not be able to reach across
 * accounts, so their orders are checked against the account they signed in as.
 * <p>
 * Callers report a refusal as "not found" rather than "forbidden", so the
 * endpoint cannot be used to work out which order IDs exist.
 */
@ApplicationScoped
public class OrderOwnershipGuard
{
    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    public boolean mayAccess(OrderEntity order)
    {
        if (order == null) {
            return false;
        }
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return true;
        }

        CustomerEntity customer = CustomerEntity.findByEmail(jwt.getSubject());
        return customer != null
                && order.getCustomerEntity() != null
                && order.getCustomerEntity().getId().equals(customer.getId());
    }

    /**
     * Whether the caller may have an order replayed to them
     * (.kiro/specs/checkout-idempotency, Requirement 4.2). Unlike
     * {@link #mayAccess}, this is keyed on the order's owner, not on the
     * requester's authentication state: an order belonging to a customer is
     * replayable only to that customer, whether the requester is anonymous or
     * signed in as someone else. An order with no customer is replayable on
     * possession of the key alone — the same bearer model as its
     * {@code sessionId} (KNOWN-LIMITATIONS.md §4).
     * <p>
     * {@code mayAccess} cannot serve this check: it returns {@code true} for
     * any caller without the {@code customer} role, whatever the order's
     * owner, which would hand a registered customer's order to an anonymous
     * key-holder. Do not widen {@code mayAccess} instead of using this method —
     * {@code confirmInStorePayment} and {@code OrderContactResource} depend on
     * its current permissiveness.
     */
    public boolean mayReplay(OrderEntity order)
    {
        if (order == null) {
            return false;
        }
        if (order.getCustomerEntity() == null) {
            return true;
        }
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return false;
        }

        CustomerEntity customer = CustomerEntity.findByEmail(jwt.getSubject());
        return customer != null
                && order.getCustomerEntity().getId().equals(customer.getId());
    }
}
