package org.ecommerce.backend.api.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.api.rest.OrderOwnershipGuard;
import org.ecommerce.backend.exception.RateLimitExceededException;
import org.ecommerce.backend.mapper.OrderMapper;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.OrderTracking;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.CurrentRequestClientIp;
import org.ecommerce.backend.utils.CurrentRequestOrderToken;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderStatusRespDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class OrderResource
{
    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    /**
     * Guest-order-authorization Requirement 7.2a — sized off the real polling rate,
     * not off the write-surface limiters above: {@code usePollOrderStatus} fires every
     * 3s for up to 120s, so one ordinary guest checkout is already ~40 requests from
     * one IP before counting {@code getOrderDetail} account-page traffic or a second
     * concurrent checkout attempt. This is the one limit in the spec a legitimate
     * shopper can plausibly reach, so it is sized generously above that baseline.
     */
    private static final int ORDER_READ_MAX_PER_WINDOW = 150;
    private static final long ORDER_READ_WINDOW_SECONDS = 3600;

    @Inject
    OrderService orderService;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    CurrentRequestOrderToken currentRequestOrderToken;

    @Inject
    CurrentRequestClientIp currentRequestClientIp;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    OrderMapper orderMapper;

    /**
     * Shared by {@link #orderStatus} and {@link #getOrderDetail} — GraphQL resolvers
     * have no {@code @HeaderParam}, so the IP is resolved the same way
     * {@link org.ecommerce.backend.utils.ClientIpUtils}-based REST endpoints do, via
     * the {@link CurrentRequestClientIp} bean (design.md §3.2's established pattern),
     * never a second mechanism.
     * <p>
     * Requirement 7.2a. GraphQL has no REST-style raw status code to hand back per
     * query, so a denial throws a distinct GraphQL error — the shape every other
     * refusal on this resolver already takes — rather than a literal HTTP 429, which
     * would mean forcing a non-standard transport-level response through a framework
     * built around the 200-plus-errors envelope everywhere else in this class.
     */
    private void enforceOrderReadRateLimit()
    {
        RateLimitDecision decision = rateLimiterService.check(
                "order-read", currentRequestClientIp.resolve(), ORDER_READ_MAX_PER_WINDOW, ORDER_READ_WINDOW_SECONDS);
        if (!decision.allowed()) {
            throw new RateLimitExceededException();
        }
    }

    // NOTE: there is deliberately no createOrder mutation here.
    // Order creation is REST-only (`POST /api/orders` → OrderService.createOrderFromCart),
    // where the request carries {variantId, quantity} and the server prices every
    // line from the signature-verified shopperType claim. The mutation that used
    // to live here accepted client-supplied unit prices and totals, so any caller
    // could persist an order at a price of their choosing. Guest checkout does not
    // need it — the REST endpoint is deliberately unauthenticated and resolves an
    // absent token to the GUEST tier. Do not reintroduce a price-carrying mutation;
    // OrderResourceContractTest guards its absence.

    /**
     * The mutation carries no stock instruction. What happens to the goods follows from
     * the destination status alone, so no caller can move stock by asking — a refund
     * records money coming back and nothing else.
     */
    @Mutation("updateOrderStatus")
    @Description("Move one order to a new status. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public OrderResponseDto updateOrderStatus(@Name("orderId") String orderId, @Name("status") String status,
                                              @Name("trackingNumber") String trackingNumber,
                                              @Name("trackingCarrier") String trackingCarrier)
            throws GraphQLException
    {
        LOG.debug("updateOrderStatus for orderId=" + orderId + ", status=" + status);
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }
        return orderService.updateOrderStatus(id, status, staffDisplayName(),
                new OrderTracking(trackingNumber, trackingCarrier));
    }

    /**
     * Who to credit on the status timeline. The staff JWT carries a full_name
     * claim; the subject (their email) identifies them if it is ever absent.
     */
    private String staffDisplayName()
    {
        if (jwt == null) {
            return null;
        }
        String fullName = jwt.getClaim("full_name");
        return fullName != null && !fullName.isBlank() ? fullName : jwt.getSubject();
    }

    /**
     * S2′ (guest-order-authorization Requirement 4) — replaces {@code orderBySessionId}
     * rather than guarding it: that query was keyed on {@code sessionId}, a second
     * bearer credential this spec withdraws, and it returned the FULL order (customer
     * email, line items) for what the success page only ever polls status/total/time
     * from. Authorized by the same {@link OrderOwnershipGuard#mayAct} as every other
     * order-scoped surface, and — like {@code getOrderDetail} — status is never an
     * input to that decision (Requirement 2.8): a token still authorizes reading a
     * cancelled order's status, which is exactly the case the success page most needs
     * to show correctly.
     */
    @Query("orderStatus")
    @Description("Poll one order's status by id — the guest checkout success page")
    public OrderStatusRespDto orderStatus(@Name("orderId") String orderId) throws GraphQLException
    {
        enforceOrderReadRateLimit();

        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }

        OrderEntity order = OrderEntity.findById(id);
        if (order == null) {
            LOG.debugf("orderStatus: order not found: %s", id);
            throw new GraphQLException("Order not found");
        }

        if (!ownershipGuard.mayAct(order, currentRequestOrderToken.resolve())) {
            LOG.warnf("orderStatus: refused for order %s", id);
            throw new GraphQLException("Order not found");
        }

        return orderMapper.toStatusRespDto(order);
    }

    @Query("allOrders")
    @Description("Get all orders with paging, newest created orders first by default")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public List<OrderResponseDto> getAllOrders(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        return orderService.getAllOrders(pageRequest, filterRequest);
    }

    /**
     * Shopper-facing only (Requirement 1.6) — a staff JWT authorizes nothing here.
     * Staff read order detail through {@code adminOrder}, which is
     * {@code @RolesAllowed}-gated and richer.
     * <p>
     * Guards before fetching (Requirement 1, design.md task 3.2): the order entity is
     * loaded and {@link OrderOwnershipGuard#mayAct} decides before
     * {@code orderService.getOrderDetail} assembles the full response — the previous
     * shape built the whole PII payload first and decided whether the caller could have
     * it afterward.
     */
    @Query("getOrderDetail")
    @Description("Get order detail by order id")
    public OrderDetailRespDto getOrderDetail(@Name("id") String orderId) throws GraphQLException
    {
        enforceOrderReadRateLimit();

        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Nothing to log yet — orderId is not a valid identifier, parsed or otherwise,
            // and Requirement 5.6 forbids logging the raw string.
            throw new GraphQLException("Order not found");
        }

        OrderEntity order = OrderEntity.findById(id);
        if (order == null) {
            LOG.debugf("getOrderDetail: order not found: %s", id);
            throw new GraphQLException("Order not found");
        }

        if (!ownershipGuard.mayAct(order, currentRequestOrderToken.resolve())) {
            LOG.warnf("getOrderDetail: refused for order %s", id);
            throw new GraphQLException("Order not found");
        }

        return orderService.getOrderDetail(id);
    }

    @Query("myOrders")
    @Description("Get authenticated customer's order history")
    public List<OrderSummaryDto> myOrders() throws GraphQLException
    {
        if (jwt == null || jwt.getSubject() == null) {
            LOG.warn("myOrders called without valid customer JWT");
            throw new GraphQLException("Unauthorized");
        }

        String email = jwt.getSubject();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            LOG.warnf("myOrders: customer not found for email: %s", email);
            throw new GraphQLException("Unauthorized");
        }

        return orderService.getMyOrders(customer.getId());
    }

}
