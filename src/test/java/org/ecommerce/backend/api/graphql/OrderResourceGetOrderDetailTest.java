package org.ecommerce.backend.api.graphql;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderResource#getOrderDetail(String)} ownership check.
 * <p>
 * Requirements: 3.1, 3.2, 3.3
 */
@QuarkusTest
class OrderResourceGetOrderDetailTest
{
    @Inject
    OrderResource orderResource;

    @InjectMock
    OrderService orderService;

    @InjectMock
    JsonWebToken jwt;

    @InjectMock
    SecurityIdentity securityIdentity;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(OrderEntity.class);
    }

    @Test
    @DisplayName("Customer JWT with matching order returns data")
    void customerJwt_matchingOrder_returnsData() throws GraphQLException
    {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String email = "customer@example.com";

        // Configure security identity as customer
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);

        // Configure order service to return detail
        OrderDetailRespDto expectedDetail = new OrderDetailRespDto();
        expectedDetail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(expectedDetail);

        // Configure customer lookup via PanacheMock
        // findByEmail calls findById-style query internally
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);

        // Configure order entity lookup with matching customer
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(new CustomerEntity());
        order.getCustomerEntity().setId(customerId);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        // Act
        OrderDetailRespDto result = orderResource.getOrderDetail(orderId.toString());

        // Assert
        assertNotNull(result);
        assertEquals(orderId, result.getId());
    }

    @Test
    @DisplayName("Customer JWT with non-matching order throws 'Order not found'")
    void customerJwt_nonMatchingOrder_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID differentCustomerId = UUID.randomUUID();
        String email = "customer@example.com";

        // Configure security identity as customer
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);

        // Configure order service to return detail
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // Configure customer lookup
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);

        // Configure order entity lookup with DIFFERENT customer
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(new CustomerEntity());
        order.getCustomerEntity().setId(differentCustomerId);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        // Act & Assert
        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    @DisplayName("Customer JWT with null customerEntity on order (guest order) throws 'Order not found'")
    void customerJwt_nullCustomerEntity_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String email = "customer@example.com";

        // Configure security identity as customer
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);

        // Configure order service to return detail
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // Configure customer lookup
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);

        // Configure order entity with null customerEntity (guest order)
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(null);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        // Act & Assert
        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    @DisplayName("Staff JWT bypasses ownership check and returns data")
    void staffJwt_bypassesCheck_returnsData() throws GraphQLException
    {
        UUID orderId = UUID.randomUUID();

        // Configure security identity as staff (not customer)
        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(securityIdentity.hasRole("staff")).thenReturn(true);

        // Configure order service to return detail
        OrderDetailRespDto expectedDetail = new OrderDetailRespDto();
        expectedDetail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(expectedDetail);

        // Act
        OrderDetailRespDto result = orderResource.getOrderDetail(orderId.toString());

        // Assert
        assertNotNull(result);
        assertEquals(orderId, result.getId());
    }

    @Test
    @DisplayName("No JWT (securityIdentity has no roles) bypasses ownership check and returns data")
    void noRoles_bypassesCheck_returnsData() throws GraphQLException
    {
        UUID orderId = UUID.randomUUID();

        // Configure security identity with no roles (anonymous-like)
        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(securityIdentity.hasRole("staff")).thenReturn(false);

        // Configure order service to return detail
        OrderDetailRespDto expectedDetail = new OrderDetailRespDto();
        expectedDetail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(expectedDetail);

        // Act
        OrderDetailRespDto result = orderResource.getOrderDetail(orderId.toString());

        // Assert
        assertNotNull(result);
        assertEquals(orderId, result.getId());
    }

    @Test
    @DisplayName("Customer JWT but customer not found by email throws 'Order not found'")
    void customerJwt_customerNotFound_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();
        String email = "unknown@example.com";

        // Configure security identity as customer
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);

        // Configure order service to return detail
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // Configure customer lookup to return null (not found)
        when(CustomerEntity.findByEmail(email)).thenReturn(null);

        // Act & Assert
        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }
}
