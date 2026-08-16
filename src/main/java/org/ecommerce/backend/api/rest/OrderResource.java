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
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.backend.service.TransitionOutcome;
import org.ecommerce.backend.utils.ClientIpUtils;
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
    OrderNotificationService orderNotificationService;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Maximum checkouts one caller may start per window.
     * <p>
     * Deliberately generous. Carrier-grade NAT puts many genuine shoppers behind a
     * single address, and this is the money path, so a tight limit costs real sales
     * — while any finite limit is enough to stop a caller reserving a store's stock
     * faster than {@link org.ecommerce.backend.scheduler.StockRecoveryJob} can
     * reclaim it. Overridable without a rebuild via {@code ratelimit.checkout.max}.
     */
    private static final int CHECKOUT_MAX_PER_WINDOW = 20;
    private static final long CHECKOUT_WINDOW_SECONDS = 3600;

    /**
     * Not {@code @Transactional} (design §3.3, checkout-idempotency
     * Requirement 1.4). The lookup/claim/replay dance below needs to see a
     * failed create attempt's rollback before building its response — with
     * this method transactional, {@code createOrderFromCart} would run
     * inside it, and a constraint violation would mark it rollback-only
     * before this method could return a {@code 201} replay, turning every
     * lost race into a {@code 500}. {@code createOrderFromCart} carries its
     * own {@code @Transactional} and commits or rolls back on its own.
     */
    @POST
    public Response createOrder(
            OrderCreationRequestDto request,
            @HeaderParam("Idempotency-Key") String idempotencyKeyHeader,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        // Requirement 2.3: the header is validated before any other work —
        // before the rate-limit check, before cart validation, before any
        // database write. Taken as a String and parsed here (not
        // @HeaderParam UUID) so a missing header and a malformed one can each
        // report which they are, rather than both surfacing as JAX-RS's own
        // bodyless 400.
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

        // The endpoint's pre-existing null-body guard. It has to sit after
        // header validation (Requirement 2.3) and before the fingerprint
        // call below, which is not total over a wholly-absent items list —
        // only over a null field within one line.
        if (request == null || request.getItems() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Request body is required").build();
        }

        // Checked before any work: an accepted order reserves stock immediately, and
        // nothing returns it until the abandoned-order sweep runs. A replay counts
        // against the same bucket as the original submission, deliberately — see
        // design §3.3.
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        RateLimitDecision decision = rateLimiterService.check(
                "checkout", clientIp, CHECKOUT_MAX_PER_WINDOW, CHECKOUT_WINDOW_SECONDS);
        if (!decision.allowed()) {
            return Response.status(429).header("Retry-After", decision.retryAfterSeconds()).build();
        }

        String fingerprint = OrderService.fingerprint(request.getItems());

        // The fast path (design §3.1): resolved against the key BEFORE any cart
        // validation. Not load-bearing for correctness — createOrderFromCart's
        // claim (§3.2) rolls back its whole transaction on a lost race, including
        // any stock it decremented, and the 422 re-check below (§3.5) re-resolves
        // against the winner either way. This exists to avoid the wasted work of
        // that revalidate → reserve → roll-back cycle on the common case (a
        // sequential retry), not to make it correct.
        OrderEntity existing = OrderEntity.findByIdempotencyKey(idempotencyKey);
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
            // Lost the claim (design §3.2): another delivery of this same intent
            // won. Our transaction has rolled back, releasing the stock it
            // reserved; the winner's has committed and is visible to this fresh
            // read.
            OrderEntity winner = OrderEntity.findByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                LOG.errorf("Idempotency claim on %s was lost but no winning order is visible", idempotencyKey);
                return Response.status(500).entity(Map.of("error", "Unexpected error")).build();
            }
            return resolveExisting(winner, fingerprint);
        } catch (UnavailableVariantsException e) {
            // Design §3.5: the cart may be unavailable because our own earlier
            // delivery of this same key already took the stock — the concurrent
            // last-unit race that neither the lookup above nor the claim inside
            // createOrderFromCart can catch, because this exit is reached before
            // persist() is ever attempted.
            OrderEntity winner = OrderEntity.findByIdempotencyKey(idempotencyKey);
            if (winner != null) {
                return resolveExisting(winner, fingerprint);
            }
            return Response.status(422)
                    .entity(Map.of("unavailableVariantIds", e.getUnavailableVariantIds()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * The single place all four outcomes for a matched order are decided
     * (design §6), called from all three sites that can locate an existing
     * order by key: the fast-path lookup above, the lost-race catch, and the
     * {@code 422} re-check. Evaluated in this order because the refusals are
     * not mutually exclusive (Requirement 1.6):
     * <ol>
     *   <li>ownership — the only refusal the client must not retry past;</li>
     *   <li>voided — not itself time-bound, so it must be checked
     *       independently of the window rather than relying on the window
     *       to eventually catch it;</li>
     *   <li>window;</li>
     *   <li>fingerprint;</li>
     *   <li>otherwise, replay.</li>
     * </ol>
     */
    private Response resolveExisting(OrderEntity order, String fingerprint) {
        if (order == null) {
            // The re-read after a lost claim cannot come back empty (design
            // §3.3) — Postgres blocks the second inserter until the winner's
            // transaction resolves. A null here is a server fault, never the
            // caller's — mayReplay(null) is false, and answering that as a
            // 409 would tell an impossible caller "this is not your order".
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

    /**
     * The {@code 409} body shape every idempotency-key refusal shares:
     * {@code error} is a human message a caller must never branch on;
     * {@code code} is the only field the storefront (or a test) may key
     * behaviour off (design §6).
     */
    private Response idempotencyConflict(String message, String code) {
        return Response.status(Response.Status.CONFLICT).entity(Map.of("error", message, "code", code)).build();
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

        // The shopper's "reserved for collection" email is sent by applyTransition, from
        // the status itself — nothing to do here.
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
     * Guest checkout (no "customer" role at all) deliberately resolves to null.
     * A "customer" role with no matching row is not the same thing and must not
     * collapse into it — mirrors getOrderDetail/myOrders, which both throw
     * rather than silently falling back to guest.
     */
    private CustomerEntity resolveCustomer() {
        if (securityIdentity == null || !securityIdentity.hasRole("customer")) {
            return null;
        }
        String email = jwt.getSubject();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
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
