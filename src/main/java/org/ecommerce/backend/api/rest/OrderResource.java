package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.backend.service.TransitionOutcome;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    @Inject
    OrderService orderService;

    @Inject
    OrderNotificationService orderNotificationService;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @POST
    @Transactional
    public Response createOrder(OrderCreationRequestDto request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Request body is required").build();
        }

        CustomerTypeEn customerTier = resolveCustomerTier();
        CustomerEntity customer = resolveCustomer();

        try {
            OrderCheckoutResponseDto response = orderService.createOrderFromCart(request, customerTier, customer);
            return Response.status(201).entity(response).build();
        } catch (UnavailableVariantsException e) {
            return Response.status(422)
                    .entity(Map.of("unavailableVariantIds", e.getUnavailableVariantIds()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * Confirms an order the shopper will pay for when they collect it — the
     * in-store counterpart of handing off to the payment gateway.
     * <p>
     * Checkout has to say so explicitly, because nothing else can. An order that
     * merely sits at CREATED is indistinguishable from an abandoned cart, and
     * {@code StockRecoveryJob} reclaims those: without this call the shopper's
     * order is cancelled out from under them once the hold window passes, and
     * staff never see it waiting. Moving it to IN_STORE_PAYMENT is what marks it
     * a real commitment.
     * <p>
     * Payment is not being taken here. The status means the money is still owed;
     * staff move the order to PAID at the counter, then COLLECTED when the goods
     * are handed over.
     */
    // Overrides the class-level @Consumes: everything this needs is in the path, so
    // demanding a JSON content type would reject a bodyless POST with 415 — which is
    // exactly what the storefront sends.
    @POST
    @Path("/{orderId}/in-store-payment")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response confirmInStorePayment(@PathParam("orderId") UUID orderId) {
        OrderEntity order = OrderEntity.findOrderInfoById(orderId);
        if (order == null || !ownershipGuard.mayAccess(order)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        // A resubmitted checkout must not read as a failure to the shopper whose
        // order was in fact placed.
        if (order.getStatus() == OrderStatusEn.IN_STORE_PAYMENT) {
            return Response.ok(Map.of("orderId", order.getId().toString(),
                    "status", order.getStatus().name())).build();
        }

        // Paying at collection only makes sense if the shopper is collecting.
        // Fails closed on an order with no method chosen yet, matching the column's
        // own DEFAULT TRUE: an unclassified method is treated as a delivery.
        ShippingMethodEntity method = order.getShippingMethod();
        if (method == null || method.isRequiresAddress()) {
            LOG.debugf("Rejected in-store payment for order %s: %s is not a collection method",
                    orderId, method != null ? method.getName() : "no delivery method");
            return Response.status(422)
                    .entity(Map.of("error", "Paying in store is only available when collecting your order"))
                    .build();
        }

        TransitionOutcome outcome = orderService.applyTransition(order,
                StatusTransition.system(OrderStatusEn.CREATED, OrderStatusEn.IN_STORE_PAYMENT,
                        "Shopper chose to pay at collection"));

        if (!outcome.claimed()) {
            LOG.warnf("Could not confirm in-store payment for order %s: it is %s, not CREATED",
                    orderId, order.getStatus());
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Order can no longer be modified"))
                    .build();
        }

        orderNotificationService.sendConfirmationEmail(order);

        return Response.ok(Map.of("orderId", order.getId().toString(),
                "status", order.getStatus().name())).build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the customer tier from the signature-verified JWT.
     * Quarkus rejects requests carrying an invalid or forged Bearer token before
     * the endpoint runs, so the claim can be trusted; no token means guest checkout.
     */
    private CustomerTypeEn resolveCustomerTier() {
        if (jwt == null || jwt.getRawToken() == null) {
            return CustomerTypeEn.GUEST;
        }

        Object claim = jwt.getClaim("shopperType");
        if (claim == null) {
            return CustomerTypeEn.GUEST;
        }

        String shopperType = claim instanceof JsonString js ? js.getString() : claim.toString();
        return "WHOLESALER".equals(shopperType) ? CustomerTypeEn.WHOLESALER : CustomerTypeEn.RETAILER;
    }

    /**
     * Resolves the signed-in customer so the order can be linked to their account.
     * Mirrors the ownership pattern in OrderContactResource/getOrderDetail; guest
     * checkout (no "customer" role) deliberately resolves to null.
     */
    private CustomerEntity resolveCustomer() {
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return null;
        }
        return CustomerEntity.findByEmail(jwt.getSubject());
    }
}
