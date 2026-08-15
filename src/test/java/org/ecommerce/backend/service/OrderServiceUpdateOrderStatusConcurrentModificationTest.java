package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test proving {@link OrderService#updateOrderStatus} refuses to silently overwrite an order
 * that changed status between its read and its atomic claim — the same lost-update race the
 * PayFast ITN handler and the abandoned-order release job also guard against.
 * <p>
 * {@link OrderRepository} is mocked directly (rather than relying on {@code PanacheMock} alone)
 * because {@code updateOrderStatus} reads the order via
 * {@code orderRepository.findOrderInfoById(...)} — an injected repository instance
 * method, not a static {@code OrderEntity} finder. This differs from
 * {@code OrderServiceGetMyOrdersTest}, which can mock only {@code OrderEntity.find(...)} because
 * {@code getMyOrders} calls that static finder directly.
 * <p>
 * The happy path is covered separately in {@code OrderServiceUpdateOrderStatusTest}, which needs
 * the repository and the atomic claim both real — incompatible with this class mocking both.
 */
@QuarkusTest
class OrderServiceUpdateOrderStatusConcurrentModificationTest
{
    @Inject
    OrderService orderService;

    @InjectMock
    OrderRepository orderRepository;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
    }

    @Test
    @DisplayName("throws when the atomic claim affects 0 rows because the order's status changed concurrently")
    void updateOrderStatus_statusChangedConcurrently_throwsGraphQLException()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setSessionId(UUID.randomUUID());
        order.setStatus(OrderStatusEn.CREATED);

        when(orderRepository.findOrderInfoById(any(UUID.class))).thenReturn(order);
        // Simulates another writer (the PayFast ITN handler or the abandoned-order release job)
        // having already moved the order off CREATED between this read and the claim below.
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(0);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> orderService.updateOrderStatus(orderId, "CANCELLED", "Dana Staff"));

        assertEquals("Order status changed concurrently; please refresh and try again", ex.getMessage());
        assertEquals(OrderStatusEn.CREATED, order.getStatus(), "in-memory status must not flip when the claim is lost");
    }
}
