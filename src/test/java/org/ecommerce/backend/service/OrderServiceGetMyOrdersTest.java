package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderService#getMyOrders(UUID)}.
 * <p>
 * Validates: Requirements 2.3, 2.4, 2.5
 */
@QuarkusTest
class OrderServiceGetMyOrdersTest
{
    @Inject
    OrderService orderService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
    }

    @Test
    void getMyOrders_emptyOrderList_returnsEmptyList()
    {
        UUID customerId = UUID.randomUUID();
        mockOrderEntityFind(List.of());

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getMyOrders_singleOrderWithMultipleItems_mapsCorrectly()
    {
        UUID customerId = UUID.randomUUID();
        LocalDateTime orderDate = LocalDateTime.of(2024, 3, 15, 10, 30, 0);

        OrderEntity order = createOrder(UUID.randomUUID(), customerId, orderDate, OrderStatusEn.PAID, new BigDecimal("250.50"), List.of(2, 3, 1) // quantities — itemCount should be 6
        );

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        OrderSummaryDto dto = result.get(0);
        assertEquals(order.getId().toString(), dto.getId());
        assertEquals(orderDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), dto.getOrderDate());
        assertEquals("PAID", dto.getStatus());
        assertEquals(6, dto.getItemCount());
        assertEquals(250.50, dto.getTotalAmount(), 0.001);
    }

    @Test
    void getMyOrders_multipleOrders_allFieldsMappedCorrectly()
    {
        UUID customerId = UUID.randomUUID();
        LocalDateTime date1 = LocalDateTime.of(2024, 6, 20, 14, 0, 0);
        LocalDateTime date2 = LocalDateTime.of(2024, 5, 10, 9, 15, 0);

        OrderEntity order1 = createOrder(UUID.randomUUID(), customerId, date1, OrderStatusEn.DELIVERED, new BigDecimal("1500.00"), List.of(5, 2));
        OrderEntity order2 = createOrder(UUID.randomUUID(), customerId, date2, OrderStatusEn.CREATED, new BigDecimal("99.99"), List.of(1));

        // Mock returns orders already in DESC createdAt order (as the DB would)
        mockOrderEntityFind(List.of(order1, order2));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(2, result.size());

        // First order (newest)
        OrderSummaryDto dto1 = result.get(0);
        assertEquals(order1.getId().toString(), dto1.getId());
        assertEquals(date1.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), dto1.getOrderDate());
        assertEquals("DELIVERED", dto1.getStatus());
        assertEquals(7, dto1.getItemCount()); // 5 + 2
        assertEquals(1500.00, dto1.getTotalAmount(), 0.001);

        // Second order (older)
        OrderSummaryDto dto2 = result.get(1);
        assertEquals(order2.getId().toString(), dto2.getId());
        assertEquals(date2.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), dto2.getOrderDate());
        assertEquals("CREATED", dto2.getStatus());
        assertEquals(1, dto2.getItemCount());
        assertEquals(99.99, dto2.getTotalAmount(), 0.001);
    }

    @Test
    void getMyOrders_preservesDescendingOrderFromDatabase()
    {
        UUID customerId = UUID.randomUUID();
        LocalDateTime newest = LocalDateTime.of(2024, 12, 1, 12, 0, 0);
        LocalDateTime middle = LocalDateTime.of(2024, 6, 15, 8, 0, 0);
        LocalDateTime oldest = LocalDateTime.of(2024, 1, 5, 18, 30, 0);

        OrderEntity orderNewest = createOrder(UUID.randomUUID(), customerId, newest, OrderStatusEn.PAID, new BigDecimal("100.00"), List.of(1));
        OrderEntity orderMiddle = createOrder(UUID.randomUUID(), customerId, middle, OrderStatusEn.IN_TRANSIT, new BigDecimal("200.00"), List.of(2));
        OrderEntity orderOldest = createOrder(UUID.randomUUID(), customerId, oldest, OrderStatusEn.DELIVERED, new BigDecimal("300.00"), List.of(3));

        // DB returns in DESC order
        mockOrderEntityFind(List.of(orderNewest, orderMiddle, orderOldest));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(3, result.size());
        assertEquals(newest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), result.get(0).getOrderDate());
        assertEquals(middle.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), result.get(1).getOrderDate());
        assertEquals(oldest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), result.get(2).getOrderDate());
    }

    @Test
    void getMyOrders_orderWithNullCreatedAt_mapsOrderDateAsNull()
    {
        UUID customerId = UUID.randomUUID();

        OrderEntity order = createOrder(UUID.randomUUID(), customerId, null, OrderStatusEn.CREATED, new BigDecimal("50.00"), List.of(1));

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        assertNull(result.get(0).getOrderDate());
    }

    @Test
    void getMyOrders_orderWithNullStatus_mapsStatusAsNull()
    {
        UUID customerId = UUID.randomUUID();

        OrderEntity order = createOrder(UUID.randomUUID(), customerId, LocalDateTime.now(), null, new BigDecimal("75.00"), List.of(2));

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        assertNull(result.get(0).getStatus());
    }

    @Test
    void getMyOrders_orderWithNullTotalAmount_mapsTotalAmountAsZero()
    {
        UUID customerId = UUID.randomUUID();

        OrderEntity order = createOrder(UUID.randomUUID(), customerId, LocalDateTime.now(), OrderStatusEn.PENDING, null, List.of(3));

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        assertEquals(0.0, result.get(0).getTotalAmount(), 0.001);
    }

    @Test
    void getMyOrders_orderWithNullItems_mapsItemCountAsZero()
    {
        UUID customerId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatusEn.CREATED);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setItems(null);

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        order.setCustomerEntity(customer);

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getItemCount());
    }

    @Test
    void getMyOrders_orderItemWithNullQuantity_treatsAsZero()
    {
        UUID customerId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatusEn.PAID);
        order.setTotalAmount(new BigDecimal("500.00"));

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        order.setCustomerEntity(customer);

        OrderItemEntity item1 = new OrderItemEntity();
        item1.setQuantity(4);
        item1.setOrderEntity(order);

        OrderItemEntity item2 = new OrderItemEntity();
        item2.setQuantity(null); // null quantity
        item2.setOrderEntity(order);

        order.setItems(new ArrayList<>(List.of(item1, item2)));

        mockOrderEntityFind(List.of(order));

        List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

        assertEquals(1, result.size());
        assertEquals(4, result.get(0).getItemCount()); // 4 + 0 (null treated as 0)
    }

    // --- Helper methods ---

    private OrderEntity createOrder(UUID orderId, UUID customerId, LocalDateTime createdAt, OrderStatusEn status, BigDecimal totalAmount, List<Integer> quantities)
    {
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setCreatedAt(createdAt);
        order.setStatus(status);
        order.setTotalAmount(totalAmount);

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        order.setCustomerEntity(customer);

        order.setItems(new ArrayList<>());
        for (Integer qty : quantities) {
            OrderItemEntity item = new OrderItemEntity();
            item.setId(UUID.randomUUID());
            item.setQuantity(qty);
            item.setUnitPrice(new BigDecimal("10.00"));
            item.setOrderEntity(order);
            order.getItems().add(item);
        }

        return order;
    }

    @SuppressWarnings("unchecked")
    private void mockOrderEntityFind(List<OrderEntity> orders)
    {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn((List) orders);
        when(OrderEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }
}
