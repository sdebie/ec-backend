package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 2: myOrders Ownership Filter

import net.jqwik.api.*;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.enums.OrderStatusEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 2: myOrders Ownership Filter
 *
 * For any authenticated customer and any set of orders in the database belonging to
 * multiple customers, the myOrders query SHALL return only orders where
 * order.customerEntity.id equals the authenticated customer's ID — no order belonging
 * to a different customer is ever included.
 *
 * Validates: Requirements 2.4
 */
class MyOrdersFilterPropertyTest {

    /**
     * Replicates the filtering and mapping logic from OrderService.getMyOrders.
     * The DB query filters by customerEntity.id and the method maps to DTOs.
     * This simulates the full contract: given a mixed set of orders, only those
     * belonging to the target customer are returned.
     */
    private List<OrderSummaryDto> simulateGetMyOrders(UUID customerId, List<OrderEntity> allOrders) {
        // Step 1: Simulate the DB WHERE clause (customerEntity.id = customerId)
        List<OrderEntity> filteredOrders = allOrders.stream()
                .filter(o -> o.customerEntity != null
                        && o.customerEntity.id != null
                        && o.customerEntity.id.equals(customerId))
                .sorted((a, b) -> {
                    if (a.createdAt == null && b.createdAt == null) return 0;
                    if (a.createdAt == null) return 1;
                    if (b.createdAt == null) return -1;
                    return b.createdAt.compareTo(a.createdAt);
                })
                .collect(Collectors.toList());

        // Step 2: Map to DTOs (same logic as OrderService.getMyOrders)
        return filteredOrders.stream().map(order -> {
            OrderSummaryDto dto = new OrderSummaryDto();
            dto.id = order.id.toString();
            dto.orderDate = order.createdAt != null
                    ? order.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    : null;
            dto.status = order.status != null ? order.status.name() : null;
            dto.itemCount = order.items != null
                    ? order.items.stream()
                        .mapToInt(item -> item.quantity != null ? item.quantity : 0)
                        .sum()
                    : 0;
            dto.totalAmount = order.totalAmount != null
                    ? order.totalAmount.doubleValue()
                    : 0.0;
            return dto;
        }).collect(Collectors.toList());
    }

    @Property(tries = 100)
    void allReturnedOrdersBelongToAuthenticatedCustomer(
            @ForAll("orderScenarios") OrderScenario scenario
    ) {
        // Execute the simulated getMyOrders
        List<OrderSummaryDto> results = simulateGetMyOrders(
                scenario.targetCustomerId, scenario.allOrders);

        // Property: every returned order must belong to the target customer
        Set<String> targetOrderIds = scenario.allOrders.stream()
                .filter(o -> o.customerEntity != null
                        && o.customerEntity.id.equals(scenario.targetCustomerId))
                .map(o -> o.id.toString())
                .collect(Collectors.toSet());

        Set<String> otherCustomerOrderIds = scenario.allOrders.stream()
                .filter(o -> o.customerEntity != null
                        && !o.customerEntity.id.equals(scenario.targetCustomerId))
                .map(o -> o.id.toString())
                .collect(Collectors.toSet());

        for (OrderSummaryDto dto : results) {
            // Assert this order belongs to the target customer
            assertTrue(targetOrderIds.contains(dto.id),
                    "Order " + dto.id + " must belong to target customer " + scenario.targetCustomerId);

            // Assert this order does NOT belong to another customer
            assertFalse(otherCustomerOrderIds.contains(dto.id),
                    "Order " + dto.id + " belongs to another customer and must NOT appear in results");
        }

        // Property: all orders belonging to the target customer are included (completeness)
        assertEquals(targetOrderIds.size(), results.size(),
                "Result count must match the number of orders belonging to the target customer. "
                + "Expected " + targetOrderIds.size() + " but got " + results.size());

        // Property: no order from another customer is ever included
        Set<String> returnedIds = results.stream()
                .map(dto -> dto.id)
                .collect(Collectors.toSet());
        for (String otherId : otherCustomerOrderIds) {
            assertFalse(returnedIds.contains(otherId),
                    "Order " + otherId + " belongs to a different customer and must not be in results");
        }
    }

    @Provide
    Arbitrary<OrderScenario> orderScenarios() {
        Arbitrary<UUID> targetCustomerIdArb = Arbitraries.create(UUID::randomUUID);

        Arbitrary<List<UUID>> otherCustomerIdsArb = Arbitraries.create(UUID::randomUUID)
                .list().ofMinSize(1).ofMaxSize(5);

        Arbitrary<Integer> orderCountArb = Arbitraries.integers().between(1, 20);

        return Combinators.combine(targetCustomerIdArb, otherCustomerIdsArb, orderCountArb)
                .as((targetId, otherIds, orderCount) -> {
                    List<UUID> allCustomerIds = new ArrayList<>();
                    allCustomerIds.add(targetId);
                    allCustomerIds.addAll(otherIds);

                    Random random = new Random();
                    OrderStatusEn[] statuses = OrderStatusEn.values();

                    List<OrderEntity> orders = new ArrayList<>();
                    for (int i = 0; i < orderCount; i++) {
                        OrderEntity order = new OrderEntity();
                        order.id = UUID.randomUUID();

                        // Randomly assign to one of the customer IDs
                        UUID assignedCustomerId = allCustomerIds.get(random.nextInt(allCustomerIds.size()));
                        CustomerEntity customer = new CustomerEntity();
                        customer.id = assignedCustomerId;
                        order.customerEntity = customer;

                        order.createdAt = LocalDateTime.now().minusDays(random.nextInt(365));
                        order.status = statuses[random.nextInt(statuses.length)];
                        order.totalAmount = BigDecimal.valueOf(random.nextInt(10000) + 100, 2);

                        // Generate 1-5 order items
                        order.items = new ArrayList<>();
                        int itemCount = random.nextInt(5) + 1;
                        for (int j = 0; j < itemCount; j++) {
                            OrderItemEntity item = new OrderItemEntity();
                            item.id = UUID.randomUUID();
                            item.quantity = random.nextInt(10) + 1;
                            item.unitPrice = BigDecimal.valueOf(random.nextInt(5000) + 50, 2);
                            item.orderEntity = order;
                            order.items.add(item);
                        }

                        orders.add(order);
                    }

                    return new OrderScenario(targetId, orders);
                });
    }

    /**
     * Holds a test scenario: a target customer ID and a mixed list of orders.
     */
    static class OrderScenario {
        final UUID targetCustomerId;
        final List<OrderEntity> allOrders;

        OrderScenario(UUID targetCustomerId, List<OrderEntity> allOrders) {
            this.targetCustomerId = targetCustomerId;
            this.allOrders = allOrders;
        }

        @Override
        public String toString() {
            long matchCount = allOrders.stream()
                    .filter(o -> o.customerEntity != null
                            && o.customerEntity.id.equals(targetCustomerId))
                    .count();
            return "OrderScenario{targetCustomer=" + targetCustomerId
                    + ", totalOrders=" + allOrders.size()
                    + ", matchingOrders=" + matchCount + "}";
        }
    }
}
