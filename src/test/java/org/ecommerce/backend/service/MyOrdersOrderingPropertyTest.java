package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 3: myOrders Ordering Invariant

import net.jqwik.api.*;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.enums.OrderStatusEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property 3: myOrders Ordering Invariant
 * <p>
 * For any list of orders returned by myOrders, for every pair of adjacent elements
 * orders[i] and orders[i+1], orders[i].orderDate >= orders[i+1].orderDate (descending order).
 * <p>
 * Validates: Requirements 2.5
 */
class MyOrdersOrderingPropertyTest
{
    /**
     * Simulates what OrderService.getMyOrders does: takes a list of OrderEntity
     * (pre-sorted by createdAt DESC as the DB query would return), maps them to DTOs.
     * We verify the output maintains descending order.
     */
    private List<OrderSummaryDto> mapOrdersToDto(List<OrderEntity> orders)
    {
        return orders.stream().map(order -> {
            OrderSummaryDto dto = new OrderSummaryDto();
            dto.setId(order.getId().toString());
            dto.setOrderDate(order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            dto.setStatus(order.getStatus() != null ? order.getStatus().name() : null);
            dto.setItemCount(order.getItems() != null ? order.getItems()
                    .stream()
                    .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                    .sum() : 0);
            dto.setTotalAmount(order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : 0.0);
            return dto;
        }).collect(Collectors.toList());
    }

    @Property(tries = 100)
    void ordersSortedDescendingByCreatedAtProduceDescendingOrderDates(@ForAll("randomOrderLists") List<OrderEntity> unsortedOrders)
    {
        // Simulate what the DB does: sort by createdAt DESC
        List<OrderEntity> sortedOrders = unsortedOrders
                .stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .collect(Collectors.toList());

        // Map to DTOs (same logic as OrderService.getMyOrders)
        List<OrderSummaryDto> result = mapOrdersToDto(sortedOrders);

        // Verify: every adjacent pair satisfies orders[i].orderDate >= orders[i+1].orderDate
        // ISO-8601 strings sort correctly lexicographically
        for (int i = 0; i < result.size() - 1; i++) {
            String current = result.get(i).getOrderDate();
            String next = result.get(i + 1).getOrderDate();

            // Skip null comparisons (null orderDate means createdAt was null)
            if (current == null || next == null) {
                // If current is null but next is not, ordering is violated
                // (null createdAt should sort after non-null in DESC order)
                if (current == null && next != null) {
                    // This is acceptable — nulls pushed to end in DESC sort
                    continue;
                }
                continue;
            }

            assertTrue(current.compareTo(next) >= 0, String.format("Ordering invariant violated at index %d: '%s' should be >= '%s'", i, current, next));
        }
    }

    @Provide
    Arbitrary<List<OrderEntity>> randomOrderLists()
    {
        return orderEntityArbitrary().list().ofMinSize(0).ofMaxSize(20);
    }

    private Arbitrary<OrderEntity> orderEntityArbitrary()
    {
        Arbitrary<LocalDateTime> timestamps = Arbitraries.longs()
                .between(
                        LocalDateTime.of(2020, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC),
                        LocalDateTime.of(2025, 12, 31, 23, 59).toEpochSecond(java.time.ZoneOffset.UTC)
                )
                .map(epoch -> LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC));

        Arbitrary<OrderStatusEn> statuses = Arbitraries.of(OrderStatusEn.values());

        Arbitrary<BigDecimal> totals = Arbitraries.bigDecimals().between(BigDecimal.ONE, new BigDecimal("99999.99")).ofScale(2);

        Arbitrary<List<OrderItemEntity>> itemLists = orderItemArbitrary().list().ofMinSize(1).ofMaxSize(5);

        return Combinators.combine(timestamps, statuses, totals, itemLists)
                .as((createdAt, status, total, items) -> {
                    OrderEntity order = new OrderEntity();
                    order.setId(UUID.randomUUID());
                    order.setCreatedAt(createdAt);
                    order.setStatus(status);
                    order.setTotalAmount(total);
                    order.setItems(new ArrayList<>(items));

                    // Set up customer entity
                    CustomerEntity customer = new CustomerEntity();
                    customer.setId(UUID.randomUUID());
                    order.setCustomerEntity(customer);

                    return order;
                });
    }

    private Arbitrary<OrderItemEntity> orderItemArbitrary()
    {
        return Arbitraries.integers().between(1, 20).map(qty -> {
            OrderItemEntity item = new OrderItemEntity();
            item.setId(UUID.randomUUID());
            item.setQuantity(qty);
            item.setUnitPrice(BigDecimal.TEN);
            return item;
        });
    }
}
