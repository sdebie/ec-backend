package org.ecommerce.backend.api.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.api.rest.OrderOwnershipGuard;
import org.ecommerce.backend.exception.OrderNotFoundException;
import org.ecommerce.backend.mapper.OrderMapper;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.OrderManagementService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.OrderTracking;
import org.ecommerce.backend.utils.CurrentRequestOrderToken;
import org.ecommerce.common.dto.OrderDetailDto;
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
    OrderManagementService orderManagement;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    OrderOwnershipGuard ownershipGuard;

    @Inject
    CurrentRequestOrderToken currentRequestOrderToken;

    @Inject
    OrderMapper orderMapper;

    @Inject
    CustomerAuthService customerAuthService;

    @Mutation("updateOrderStatus")
    @Description("Move one order to a new status. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public OrderDetailDto updateOrderStatus(@Name("orderId") String orderId, @Name("status") String status, @Name("trackingNumber") String trackingNumber, @Name("trackingCarrier") String trackingCarrier)
    {
        LOG.debug("updateOrderStatus for orderId=" + orderId + ", status=" + status);
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new OrderNotFoundException(null);
        }
        return orderManagement.updateOrderStatus(id, status, staffDisplayName(), new OrderTracking(trackingNumber, trackingCarrier));
    }

    private String staffDisplayName()
    {
        if (jwt == null) {
            return null;
        }
        String fullName = jwt.getClaim("full_name");
        return fullName != null && !fullName.isBlank() ? fullName : jwt.getSubject();
    }

    @Query("orderStatus")
    @Description("Poll one order's status by id — the guest checkout success page")
    public OrderDetailDto orderStatus(@Name("orderId") String orderId) throws GraphQLException
    {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }

        OrderEntity order = orderService.findById(id);
        if (order == null) {
            LOG.debugf("orderStatus: order not found: %s", id);
            throw new GraphQLException("Order not found");
        }

        if (!ownershipGuard.mayAct(order, currentRequestOrderToken.resolve())) {
            LOG.warnf("orderStatus: refused for order %s", id);
            throw new GraphQLException("Order not found");
        }

        return orderMapper.toStatusDto(order);
    }

    @Query("allOrders")
    @Description("Get all orders with paging, newest created orders first by default")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public List<OrderDetailDto> getAllOrders(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        return orderService.getAllOrders(pageRequest, filterRequest);
    }

    @Query("getOrderDetail")
    @Description("Get order detail by order id")
    public OrderDetailDto getOrderDetail(@Name("id") String orderId) throws GraphQLException
    {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }

        OrderEntity order = orderService.findById(id);
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
        CustomerEntity customer = customerAuthService.findCustomerByEmail(email);
        if (customer == null) {
            LOG.warnf("myOrders: customer not found for email: %s", email);
            throw new GraphQLException("Unauthorized");
        }

        return orderService.getMyOrders(customer.getId());
    }

}
