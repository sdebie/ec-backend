package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.exception.IdempotencyConflictException;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.exception.QuoteOnlyItemsException;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.OrderManagementService;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.StockEffect;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    @Inject
    OrderService orderService;

    @Inject
    OrderManagementService orderManagement;

    @Inject
    OrderNotificationService orderNotificationService;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    CustomerAuthService customerAuthService;

    @POST
    public Response createOrder(
            OrderCreationRequestDto request,
            @HeaderParam("Idempotency-Key") String idempotencyKeyHeader
    ) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Idempotency-Key header is required").build();
        }
        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Idempotency-Key must be a well-formed UUID").build();
        }

        if (request == null || request.getItems() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Request body is required").build();
        }

        String fingerprint = OrderService.fingerprint(request.getItems());

        OrderEntity existing = orderService.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }

        CustomerTypeEn customerTier = resolveCustomerTier();
        CustomerEntity customer = resolveCustomer();

        try {
            OrderCheckoutResponseDto response = orderService.createOrderFromCart(
                    request, customerTier, customer, idempotencyKey, fingerprint);
            return Response.status(201).entity(response).build();
        } catch (IdempotencyConflictException e) {
            OrderEntity winner = orderService.findByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                LOG.errorf("Idempotency claim on %s was lost but no winning order is visible", idempotencyKey);
                return Response.status(500).entity(Map.of("error", "Unexpected error")).build();
            }
            return resolveExisting(winner, fingerprint);
        } catch (UnavailableVariantsException e) {
            OrderEntity winner = orderService.findByIdempotencyKey(idempotencyKey);
            if (winner != null) {
                return resolveExisting(winner, fingerprint);
            }
            return Response.status(422)
                    .entity(Map.of("unavailableVariantIds", e.getUnavailableVariantIds()))
                    .build();
        } catch (QuoteOnlyItemsException e) {
            return Response.status(422)
                    .entity(Map.of("quoteOnlyVariantIds", e.getQuoteOnlyVariantIds()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    private Response resolveExisting(OrderEntity order, String fingerprint) {
        if (order == null) {
            LOG.error("resolveExisting called with no order");
            return Response.status(500).entity(Map.of("error", "Unexpected error")).build();
        }

        if (!ownershipGuard.mayReplay(order)) {
            return idempotencyConflict("This order does not belong to you",
                    OrderService.CODE_IDEMPOTENCY_WRONG_OWNER);
        }
        if (order.getStatus() != null && order.getStatus().stockEffect() == StockEffect.RESTORE) {
            return idempotencyConflict("This order is no longer valid",
                    OrderService.CODE_IDEMPOTENCY_ORDER_VOIDED);
        }
        if (!orderService.isWithinReplayWindow(order)) {
            return idempotencyConflict("This idempotency key has expired",
                    OrderService.CODE_IDEMPOTENCY_KEY_EXPIRED);
        }
        if (!Objects.equals(order.getCartFingerprint(), fingerprint)) {
            return idempotencyConflict("This idempotency key was already used with a different cart",
                    OrderService.CODE_IDEMPOTENCY_CART_MISMATCH);
        }

        OrderCheckoutResponseDto response = orderService.replayOrder(order);
        return Response.status(201).entity(response).header("Idempotent-Replayed", "true").build();
    }

    private Response idempotencyConflict(String message, String code) {
        return Response.status(Response.Status.CONFLICT).entity(Map.of("error", message, "code", code)).build();
    }

    @POST
    @Path("/{orderId}/in-store-payment")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response confirmInStorePayment(@PathParam("orderId") UUID orderId,
                                           @HeaderParam("X-Order-Token") String orderToken) {
        OrderEntity order = orderService.findByIdWithCustomerAndItems(orderId);
        if (order == null || !ownershipGuard.mayAct(order, orderToken)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        if (order.getStatus() == OrderStatusEn.IN_STORE_PAYMENT) {
            return Response.ok(Map.of("orderId", order.getId().toString(),
                    "status", order.getStatus().name())).build();
        }

        ShippingMethodEntity method = order.getShippingMethod();
        if (method == null || method.isRequiresAddress()) {
            LOG.debugf("Rejected in-store payment for order %s: %s is not a collection method",
                    orderId, method != null ? method.getName() : "no delivery method");
            return Response.status(422)
                    .entity(Map.of("error", "Paying in store is only available when collecting your order"))
                    .build();
        }

        boolean changed = orderManagement.changeOrderStatus(order,
                StatusTransition.system(OrderStatusEn.CREATED, OrderStatusEn.IN_STORE_PAYMENT,
                        "Shopper chose to pay at collection"));

        if (!changed) {
            LOG.warnf("Could not confirm in-store payment for order %s: it is %s, not CREATED",
                    orderId, order.getStatus());
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Order can no longer be modified"))
                    .build();
        }

        return Response.ok(Map.of("orderId", order.getId().toString(),
                "status", order.getStatus().name())).build();
    }

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

    private CustomerEntity resolveCustomer() {
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return null;
        }
        String email = jwt.getSubject();
        CustomerEntity customer = customerAuthService.findCustomerByEmail(email);
        if (customer == null) {
            LOG.warnf("createOrder: customer role present but no matching CustomerEntity for email: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Unauthorized"))
                            .build());
        }
        return customer;
    }
}
