package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.jboss.logging.Logger;

/**
 * Decides whether the caller may act on a given order.
 * <p>
 * The single authorization rule for every order-scoped surface (guest-order-authorization
 * Requirement 1.3): {@link #mayAct} authorizes if and only if the caller presents a valid
 * capability token bound to the order, or the order belongs to a customer and the caller
 * presents that customer's JWT. Order status is never an input to this decision — reads stay
 * available at any status (Requirement 2.8), and writes are separately bounded by
 * {@code OrderService.applyTransition}.
 * <p>
 * Callers report a refusal as "not found" rather than "forbidden", so the endpoint cannot be
 * used to work out which order IDs exist.
 */
@ApplicationScoped
public class OrderOwnershipGuard
{
    private static final Logger LOG = Logger.getLogger(OrderOwnershipGuard.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    @Inject
    OrderCapabilityService orderCapability;

    @ConfigProperty(name = "order.capability.enforce")
    boolean enforce;

    /**
     * The rule (design.md §1): a closed set of two things authorize, everything else is
     * refused. Release 1/2 share this exact body — the enforcement flag, the shadow-mode
     * fallback and its {@code WARN} log live entirely inside this method (§7.1), so every
     * calling surface keeps the same one-line {@code if (!guard.mayAct(order, token))}
     * whether or not enforcement is on.
     */
    public boolean mayAct(OrderEntity order, String presentedToken)
    {
        if (order == null) {
            return false;
        }
        if (orderCapability.isValidFor(presentedToken, order.getId()) || isOwningCustomer(order)) {
            return true;
        }
        if (enforce) {
            return false;
        }
        // Release 1 only: record what enforcement would refuse, then answer as today's rule.
        // The absence of this log line in production is what proves it is safe to switch on
        // (Requirement 6.1); its presence names a caller still not sending a token.
        LOG.warnf("order-capability shadow: order %s would be refused under enforcement", order.getId());
        return legacyMayAccess(order);
    }

    private boolean isOwningCustomer(OrderEntity order)
    {
        if (order.getCustomerEntity() == null) {
            return false; // a guest order is reachable by token alone
        }
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return false;
        }

        CustomerEntity customer = CustomerEntity.findByEmail(jwt.getSubject());
        return customer != null && order.getCustomerEntity().getId().equals(customer.getId());
    }

    /**
     * {@code mayAccess}'s original logic, renamed and made private (Requirement 1.4): a
     * permissive method still called {@code mayAccess} is one a future surface could call
     * directly, exactly the shape this spec exists to close. It survives release 1 only, as
     * {@link #mayAct}'s shadow-mode fallback, and is deleted — along with {@code enforce} and
     * the shadow branch above — once release 2 is established.
     */
    private boolean legacyMayAccess(OrderEntity order)
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
     * {@link #mayAct}, this is keyed on the order's owner, not on the
     * requester's authentication state: an order belonging to a customer is
     * replayable only to that customer, whether the requester is anonymous or
     * signed in as someone else. An order with no customer is replayable on
     * possession of the key alone — the same bearer model as its
     * {@code sessionId} (KNOWN-LIMITATIONS.md §4).
     * <p>
     * Not touched by guest-order-authorization: it guards idempotency replay, keyed on the
     * {@code Idempotency-Key} — a different credential the caller must already hold.
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
