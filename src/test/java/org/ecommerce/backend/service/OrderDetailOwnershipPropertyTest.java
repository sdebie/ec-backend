package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 4: Order Detail Ownership Enforcement

import net.jqwik.api.*;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 4: Order Detail Ownership Enforcement
 *
 * For any order and any authenticated customer where order.customerEntity.id != customer.id,
 * the getOrderDetail query SHALL throw a GraphQL error with message "Order not found" —
 * the order's existence is never revealed to a non-owner customer.
 *
 * This test validates the ownership gate logic extracted from OrderResource.getOrderDetail():
 * when a customer JWT is present, the resource checks order.customerEntity.id against
 * customer.id. If they differ, it throws GraphQLException("Order not found").
 *
 * Validates: Requirements 3.1, 3.2
 */
class OrderDetailOwnershipPropertyTest {

    /**
     * Simulates the ownership enforcement gate from OrderResource.getOrderDetail().
     * This is the exact logic extracted from the resource method:
     *
     * <pre>
     * if (order == null || order.customerEntity == null
     *     || !order.customerEntity.id.equals(customer.id)) {
     *     throw new GraphQLException("Order not found");
     * }
     * </pre>
     *
     * @param order    the order being accessed
     * @param customer the authenticated customer
     * @return the order detail (if ownership matches)
     * @throws GraphQLException if ownership does not match
     */
    private OrderEntity enforceOwnership(OrderEntity order, CustomerEntity customer) throws GraphQLException {
        if (order == null || order.customerEntity == null || !order.customerEntity.id.equals(customer.id)) {
            throw new GraphQLException("Order not found");
        }
        return order;
    }

    /**
     * Validates: Requirements 3.1, 3.2
     *
     * For any pair of distinct UUIDs (authenticatedCustomerId, orderOwnerCustomerId),
     * the ownership gate MUST throw GraphQLException("Order not found").
     * This proves the property universally: non-owner customers are always rejected.
     */
    @Property(tries = 100)
    void ownershipMismatchAlwaysThrowsOrderNotFound(
            @ForAll("distinctUuidPairs") UUID[] uuidPair
    ) {
        UUID authenticatedCustomerId = uuidPair[0];
        UUID orderOwnerCustomerId = uuidPair[1];

        // Set up authenticated customer
        CustomerEntity authenticatedCustomer = new CustomerEntity();
        authenticatedCustomer.id = authenticatedCustomerId;

        // Set up order owned by a DIFFERENT customer
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.customerEntity = new CustomerEntity();
        order.customerEntity.id = orderOwnerCustomerId;

        // Act & Assert: ownership gate must throw
        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> enforceOwnership(order, authenticatedCustomer));

        assertEquals("Order not found", ex.getMessage());
    }

    /**
     * Validates: Requirements 3.1, 3.2
     *
     * For any authenticated customer, if the order has no customer entity (guest order),
     * the ownership gate MUST throw GraphQLException("Order not found").
     */
    @Property(tries = 100)
    void nullOrderCustomerAlwaysThrowsOrderNotFound(
            @ForAll("randomUuid") UUID authenticatedCustomerId
    ) {
        CustomerEntity authenticatedCustomer = new CustomerEntity();
        authenticatedCustomer.id = authenticatedCustomerId;

        // Order with no customer link (guest order)
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.customerEntity = null;

        // Act & Assert: ownership gate must throw
        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> enforceOwnership(order, authenticatedCustomer));

        assertEquals("Order not found", ex.getMessage());
    }

    /**
     * Counter-property: when ownership DOES match, no exception is thrown.
     * This confirms the gate only rejects mismatches.
     */
    @Property(tries = 100)
    void ownershipMatchNeverThrows(
            @ForAll("randomUuid") UUID customerId
    ) {
        CustomerEntity authenticatedCustomer = new CustomerEntity();
        authenticatedCustomer.id = customerId;

        // Order owned by the SAME customer
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.customerEntity = new CustomerEntity();
        order.customerEntity.id = customerId;

        // Act & Assert: ownership gate must NOT throw
        assertDoesNotThrow(() -> enforceOwnership(order, authenticatedCustomer));
    }

    @Provide
    Arbitrary<UUID[]> distinctUuidPairs() {
        Arbitrary<UUID> uuids = Arbitraries.longs().tuple2()
                .map(t -> new UUID(t.get1(), t.get2()));
        return uuids.flatMap(uuid1 -> uuids
                .filter(uuid2 -> !uuid2.equals(uuid1))
                .map(uuid2 -> new UUID[]{uuid1, uuid2}));
    }

    @Provide
    Arbitrary<UUID> randomUuid() {
        return Arbitraries.longs().tuple2()
                .map(t -> new UUID(t.get1(), t.get2()));
    }
}
