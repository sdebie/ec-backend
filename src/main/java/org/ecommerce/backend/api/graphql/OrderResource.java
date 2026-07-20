package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.backend.service.OrderService;
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

    @Mutation("createOrder")
    @Description("Create an order")
    public OrderResponseDto createOrder(@Name("order") OrderDto orderDto) throws GraphQLException
    {
        if (orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }

        LOG.debug("createOrder for sessionId=" + orderDto.getSessionId()
                + " with " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()) + " items");
        return orderService.createOrderFromDto(orderDto);
    }

    @Mutation("updateCustomerInformation")
    @Description("Update customer information for the latest order in a session. For now only email is supported.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public CustomerDto updateCustomerInformation(
            @Name("sessionId") String sessionId,
            @Name("customer") CustomerDto customerDto
    ) throws GraphQLException
    {
        LOG.debug("updateCustomerInformation for sessionId=" + sessionId);
        return orderService.updateCustomerInformation(sessionId, customerDto);
    }

    @Mutation("updateOrderStatus")
    @Description("Update the status of the latest order for a given sessionId")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public OrderResponseDto updateOrderStatus(
            @Name("sessionId") String sessionId,
            @Name("status") String status
    ) throws GraphQLException
    {
        LOG.debug("updateOrderStatus for sessionId=" + sessionId + ", status=" + status);
        return orderService.updateOrderStatus(sessionId, status);
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
    public List<OrderResponseDto> getAllOrders(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest
    )
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
            if (order == null || order.customerEntity == null || !order.customerEntity.id.equals(customer.id)) {
                LOG.warnf("getOrderDetail: customer %s attempted to access order %s they do not own", customer.id, orderId);
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

        return orderService.getMyOrders(customer.id);
    }

}
