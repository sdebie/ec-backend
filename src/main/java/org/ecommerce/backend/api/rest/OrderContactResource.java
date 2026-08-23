package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.OrderTotals;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.common.dto.OrderContactRequestDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/orders/{orderId}/contact")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderContactResource
{
    private static final Logger LOG = Logger.getLogger(OrderContactResource.class);

    /**
     * guest-order-authorization Requirement 7.1 — this endpoint had no ceiling of any
     * kind before this spec. Generous for the same reason {@code CHECKOUT_MAX_PER_WINDOW}
     * is: carrier-grade NAT puts many genuine shoppers behind one address, and a
     * shopper may legitimately PATCH contact/address/shipping several times while
     * refining checkout.
     */
    private static final int ORDER_CONTACT_MAX_PER_WINDOW = 30;
    private static final long ORDER_CONTACT_WINDOW_SECONDS = 3600;

    @Inject
    OrderService orderService;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    RateLimiterService rateLimiterService;

    // Explicit, not @Valid — matching ContactEnquiryResource's documented reason:
    // @Valid's default violation response would be 400, inconsistent with every
    // other content check in this method, which reports 422 (well-formed body,
    // invalid content).
    @Inject
    Validator validator;

    @PATCH
    @Transactional
    public Response updateContact(
            @PathParam("orderId") UUID orderId,
            @HeaderParam("X-Order-Token") String orderToken,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp,
            OrderContactRequestDto request
    )
    {
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        Response limited = rateLimiterService.enforce(
                "order-contact", clientIp, ORDER_CONTACT_MAX_PER_WINDOW, ORDER_CONTACT_WINDOW_SECONDS);
        if (limited != null) {
            return limited;
        }

        // 1. Find order by ID → 404 if missing
        OrderEntity order = OrderEntity.findOrderInfoById(orderId);
        if (order == null) {
            LOG.debugf("Order not found: %s", orderId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        // 1a. Ownership gate: a valid capability token for this order, or the order's
        // own customer's JWT — nothing else (guest-order-authorization Requirement 1).
        if (!ownershipGuard.mayAct(order, orderToken)) {
            LOG.warnf("Rejected contact update for order %s: caller does not own it", orderId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        // 1b. Contact/address/shipping are only mutable while the order is still
        // being assembled at checkout. Once it has left CREATED (paid, fulfilled,
        // cancelled, ...) this must not be able to rewrite delivery details or
        // force a reprice on a total the shopper already paid.
        if (order.getStatus() != OrderStatusEn.CREATED) {
            LOG.warnf("Rejected contact update for order %s in status %s", orderId, order.getStatus());
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Order can no longer be modified"))
                    .build();
        }

        // 1c. Reject a missing or malformed body before any content-specific
        // check below — mirrors OrderResource.createOrder's own null-body guard.
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Request body is required"))
                    .build();
        }

        // 1d. Bean-validate the shape of whatever the caller DID send (email
        // format, name length caps). Presence is never required here — that is
        // the null-guarded persist below's job, not this check's.
        Set<ConstraintViolation<OrderContactRequestDto>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; "));
            return Response.status(422).entity(Map.of("error", message)).build();
        }

        // 2. Validate shippingMethodId if present → 422 if invalid/inactive
        ShippingMethodEntity shippingMethod = null;
        if (request.getShippingMethodId() != null && !request.getShippingMethodId().isBlank()) {
            UUID shippingMethodUuid;
            try {
                shippingMethodUuid = UUID.fromString(request.getShippingMethodId());
            } catch (IllegalArgumentException e) {
                return Response.status(422)
                        .entity(Map.of("error", "Invalid shipping method ID format"))
                        .build();
            }

            shippingMethod = ShippingMethodEntity.findById(shippingMethodUuid);
            if (shippingMethod == null || !shippingMethod.isActive()) {
                LOG.debugf("Invalid or inactive shipping method: %s", request.getShippingMethodId());
                return Response.status(422)
                        .entity(Map.of("error", "Shipping method not found or inactive"))
                        .build();
            }
        }

        // 2b. A delivery method must arrive with somewhere to deliver to. Checked
        // against the values this request would leave on the order — the address may
        // have been supplied by an earlier call — and before anything is written, so a
        // rejected request changes nothing. Collection methods are exempt by
        // definition; the storefront omits the address entirely for them.
        ShippingMethodEntity effectiveMethod = shippingMethod != null ? shippingMethod : order.getShippingMethod();
        if (effectiveMethod != null && effectiveMethod.isRequiresAddress()) {
            String street = firstNonBlank(request.getStreetAddress(), order.getStreetAddress());
            String city = firstNonBlank(request.getCity(), order.getCity());
            String province = firstNonBlank(request.getProvince(), order.getProvince());
            String postalCode = firstNonBlank(request.getPostalCode(), order.getPostalCode());

            if (street == null || city == null || province == null || postalCode == null) {
                LOG.debugf("Rejected contact update for order %s: %s requires a delivery address",
                        orderId, effectiveMethod.getName());
                return Response.status(422)
                        .entity(Map.of("error", "A delivery address is required for " + effectiveMethod.getName()))
                        .build();
            }
        }

        // 3. Persist contact + address fields. Null-guarded exactly like the
        // address fields below: a caller that PATCHes only a subset (e.g. just
        // shippingMethodId) must not wipe contact details an earlier call in the
        // same checkout already saved.
        if (request.getEmail() != null) {
            order.setContactEmail(request.getEmail());
        }
        if (request.getFirstName() != null) {
            order.setContactFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            order.setContactLastName(request.getLastName());
        }

        if (shippingMethod != null) {
            order.setShippingMethod(shippingMethod);
        }

        if (request.getStreetAddress() != null) {
            order.setStreetAddress(request.getStreetAddress());
        }
        if (request.getCity() != null) {
            order.setCity(request.getCity());
        }
        if (request.getProvince() != null) {
            order.setProvince(request.getProvince());
        }
        if (request.getPostalCode() != null) {
            order.setPostalCode(request.getPostalCode());
        }

        // No explicit persist needed — entity is managed within @Transactional
        // Hibernate dirty-checking will flush changes at commit

        // 4. Reprice. The order was created before a delivery method existed, so
        // its total carried the DEFAULT estimate. Now that one is selected the
        // total has to follow it — otherwise the checkout summary shows, and the
        // payment gateway charges, a figure that excludes the chosen delivery.
        OrderTotals totals = orderService.repriceOrder(order);

        // 5. Return 200 with the updated order summary, including the breakdown
        // the checkout page needs to show a total that matches what will be charged.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", order.getId().toString());
        summary.put("contactEmail", order.getContactEmail());
        summary.put("contactFirstName", order.getContactFirstName());
        summary.put("contactLastName", order.getContactLastName());
        summary.put("subtotal", totals.subtotal());
        summary.put("vatAmount", totals.vatAmount());
        summary.put("shippingEstimate", totals.shippingEstimate());
        summary.put("grandTotal", totals.grandTotal());
        summary.put("totalAmount", order.getTotalAmount());
        summary.put("status", order.getStatus().name());

        if (order.getShippingMethod() != null) {
            summary.put("shippingMethodId", order.getShippingMethod().getId().toString());
            summary.put("shippingMethodName", order.getShippingMethod().getName());
        }

        if (order.getStreetAddress() != null) {
            summary.put("streetAddress", order.getStreetAddress());
        }
        if (order.getCity() != null) {
            summary.put("city", order.getCity());
        }
        if (order.getProvince() != null) {
            summary.put("province", order.getProvince());
        }
        if (order.getPostalCode() != null) {
            summary.put("postalCode", order.getPostalCode());
        }

        // The email itself is deliberately not logged (Requirement 5.4) — a guest's
        // address in the logs, on the ordinary success path, at INFO.
        LOG.infof("Updated contact for order %s", orderId);

        return Response.ok(summary).build();
    }

    /**
     * The value an address field would hold after this request: what it sends, else what
     * the order already carries. Blank counts as absent, so whitespace cannot satisfy a
     * required address. Returns null when neither has anything usable.
     */
    private static String firstNonBlank(String incoming, String existing)
    {
        if (incoming != null && !incoming.isBlank()) {
            return incoming;
        }
        return existing != null && !existing.isBlank() ? existing : null;
    }
}
