package org.ecommerce.backend.api.graphql;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.CurrentRequestClientIp;
import org.ecommerce.backend.utils.CurrentRequestOrderToken;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderResource#getOrderDetail(String)} ownership check.
 * <p>
 * Requirements: 3.1, 3.2, 3.3
 * <p>
 * {@code CurrentRequestOrderToken} and {@code CurrentRequestClientIp} are mocked, not
 * real — both depend on a live Vert.x {@code RoutingContext} (via
 * {@code CurrentVertxRequest.getCurrent()}), which does not exist when this test calls
 * the resolver directly as a Java method rather than through a real HTTP round-trip.
 * Discovered by actually running this test after wiring first the ownership guard, then
 * the rate limiter, onto {@code getOrderDetail}: an unmocked bean throws
 * {@code IllegalProductException} on every case, each time a new dependency reads the
 * request context. {@code OrderCapabilityService} is real (it needs no request context),
 * so a genuine, mintable token is available for the cases that need one.
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

    @InjectMock
    CurrentRequestOrderToken currentRequestOrderToken;

    @InjectMock
    CurrentRequestClientIp currentRequestClientIp;

    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    CustomerRepository customerRepository;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
        when(currentRequestClientIp.resolve()).thenReturn("192.0.2.99");
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
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
        when(customerRepository.findByEmail(email)).thenReturn(customer);

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
        when(customerRepository.findByEmail(email)).thenReturn(customer);

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
        when(customerRepository.findByEmail(email)).thenReturn(customer);

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
    @DisplayName("Staff JWT is neither a capability token nor the order owner's JWT — throws 'Order not found' (Requirement 1.6)")
    void staffJwt_noCapability_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();

        // Configure security identity as staff (not customer)
        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(securityIdentity.hasRole("staff")).thenReturn(true);

        // A real, non-null detail response — without this, an unstubbed mock returns
        // null and the resolver's PRE-EXISTING "detail == null" branch throws "Order
        // not found" for an unrelated reason, passing this test vacuously against both
        // the old and the new code (caught by actually running it: the first version
        // of this test passed against unchanged production code).
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // A real, customer-owned order — staff holds neither a valid token for it nor that customer's JWT.
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(new CustomerEntity());
        order.getCustomerEntity().setId(UUID.randomUUID());
        when(OrderEntity.findById(orderId)).thenReturn(order);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    @DisplayName("No roles and no capability token — throws 'Order not found' (Requirement 1.1/1.2: possession of the id alone is not enough)")
    void noRoles_noCapability_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();

        // Configure security identity with no roles (anonymous-like)
        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(securityIdentity.hasRole("staff")).thenReturn(false);

        // See the comment in staffJwt_noCapability_throwsOrderNotFound above: without
        // this stub the test passes vacuously off the resolver's pre-existing null check.
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // A real, customer-owned order — an anonymous caller with no token has no way to reach it.
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(new CustomerEntity());
        order.getCustomerEntity().setId(UUID.randomUUID());
        when(OrderEntity.findById(orderId)).thenReturn(order);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
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

        // A real, customer-owned order — without this, an unstubbed OrderEntity.findById
        // returns null and the resolver's order-not-found branch fires first, passing
        // this test for the wrong reason (order not found, not customer not found).
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(new CustomerEntity());
        order.getCustomerEntity().setId(UUID.randomUUID());
        when(OrderEntity.findById(orderId)).thenReturn(order);

        // Configure order service to return detail
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        // Configure customer lookup to return null (not found)
        when(customerRepository.findByEmail(email)).thenReturn(null);

        // Act & Assert
        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    @DisplayName("A valid capability token authorizes a guest order with no JWT at all (Requirement 1.1)")
    void validToken_guestOrder_returnsData() throws GraphQLException
    {
        UUID orderId = UUID.randomUUID();

        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(currentRequestOrderToken.resolve()).thenReturn(orderCapability.mint(orderId));

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(null);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        OrderDetailRespDto expectedDetail = new OrderDetailRespDto();
        expectedDetail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(expectedDetail);

        OrderDetailRespDto result = orderResource.getOrderDetail(orderId.toString());

        assertNotNull(result);
        assertEquals(orderId, result.getId());
    }

    @Test
    @DisplayName("A token minted for a different order does not authorize (Requirement 2.2)")
    void tokenForAnotherOrder_throwsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();
        UUID otherOrderId = UUID.randomUUID();

        when(securityIdentity.hasRole("customer")).thenReturn(false);
        when(currentRequestOrderToken.resolve()).thenReturn(orderCapability.mint(otherOrderId));

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCustomerEntity(null);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail(orderId.toString()));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    @DisplayName("A malformed order id throws 'Order not found' without touching the entity lookup")
    void malformedOrderId_throwsOrderNotFound()
    {
        GraphQLException ex = assertThrows(GraphQLException.class, () -> orderResource.getOrderDetail("not-a-uuid"));
        assertEquals("Order not found", ex.getMessage());
    }
}
