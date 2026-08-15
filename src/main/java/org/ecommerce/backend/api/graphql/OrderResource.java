package org.ecommerce.backend.api.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderResponseDto;
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

    @Inject
    OrderService orderService;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    // NOTE: there is deliberately no createOrder mutation here.
    // Order creation is REST-only (`POST /api/orders` → OrderService.createOrderFromCart),
    // where the request carries {variantId, quantity} and the server prices every
    // line from the signature-verified shopperType claim. The mutation that used
    // to live here accepted client-supplied unit prices and totals, so any caller
    // could persist an order at a price of their choosing. Guest checkout does not
    // need it — the REST endpoint is deliberately unauthenticated and resolves an
    // absent token to the GUEST tier. Do not reintroduce a price-carrying mutation;
    // OrderResourceContractTest guards its absence.

    @Mutation("updateOrderStatus")
    @Description("Move one order to a new status. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public OrderResponseDto updateOrderStatus(@Name("orderId") String orderId, @Name("status") String status) throws GraphQLException
    {
        LOG.debug("updateOrderStatus for orderId=" + orderId + ", status=" + status);
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }
        return orderService.updateOrderStatus(id, status, staffDisplayName());
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

    @Query("orderById")
    @Description("Update an order and return")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public OrderResponseDto getOrderById(@Name("id") String id) throws GraphQLException
    {
        LOG.debug("getOrderById");
        try {
            return orderService.getOrderById(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw new GraphQLException("Invalid id format: " + id);
        }
    }

    @Query("orderBySessionId")
    @Description("Get the latest order for a given sessionId")
    public OrderResponseDto getOrderBySessionId(@Name("sessionId") String sessionId) throws GraphQLException
    {
        LOG.debug("getOrderBySessionId for sessionId=" + sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("Invalid Order Session info");
        }
        return orderService.getLatestOrderBySessionId(sessionId);
    }

    @Query("allOrders")
    @Description("Get all orders with paging, newest created orders first by default")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public List<OrderResponseDto> getAllOrders(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        return orderService.getAllOrders(pageRequest, filterRequest);
    }

    @Query("getOrderDetail")
    @Description("Get order detail by order id")
    public OrderDetailRespDto getOrderDetail(@Name("id") String orderId) throws GraphQLException
    {
        if (orderId == null || orderId.isBlank()) {
            throw new GraphQLException("Order not found");
        }

        OrderDetailRespDto detail;
        try {
            detail = orderService.getOrderDetail(UUID.fromString(orderId));
        } catch (IllegalArgumentException e) {
            throw new GraphQLException("Order not found");
        }

        if (detail == null) {
            throw new GraphQLException("Order not found");
        }

        // Ownership gate: if caller is a customer, verify they own this order
        if (securityIdentity != null && securityIdentity.hasRole("customer")) {
            String email = jwt.getSubject();
            CustomerEntity customer = CustomerEntity.findByEmail(email);
            if (customer == null) {
                LOG.warnf("getOrderDetail: customer not found for email: %s", email);
                throw new GraphQLException("Order not found");
            }

            // Load the order entity to check ownership
            OrderEntity order = OrderEntity.findById(UUID.fromString(orderId));
            if (order == null || order.getCustomerEntity() == null || !order.getCustomerEntity().getId().equals(customer.getId())) {
                LOG.warnf("getOrderDetail: customer %s attempted to access order %s they do not own", customer.getId(), orderId);
                throw new GraphQLException("Order not found");
            }
        }

        return detail;
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
