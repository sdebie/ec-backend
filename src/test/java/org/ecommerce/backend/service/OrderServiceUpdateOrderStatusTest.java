package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-backed test for the happy path of {@link OrderService#updateOrderStatus}, which previously
 * had no behavioral coverage at all — only {@code AuthorizationIT}'s role-gate check existed, and
 * that rejects before the method body ever runs. Proves the atomic conditional claim, the history
 * write, and the response mapping all work together when nothing else touches the order
 * concurrently.
 * <p>
 * The concurrent-modification branch (the atomic claim losing) is covered separately in
 * {@code OrderServiceUpdateOrderStatusConcurrentModificationTest}, which needs
 * {@code OrderEntity.update(...)} mocked to force a loss — incompatible with this class's
 * real-DB, unmocked style.
 */
@QuarkusTest
class OrderServiceUpdateOrderStatusTest
{
    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    /** orders.session_id is NOT NULL at the DB level even though the entity has no such annotation. */
    private OrderEntity newOrder(OrderStatusEn status)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.persist();
        return order;
    }

    @Test
    @TestTransaction
    @DisplayName("claims the status change, writes a history row, and returns the new status when nothing raced")
    void updateOrderStatus_noConcurrentChange_updatesStatusAndWritesHistory() throws GraphQLException
    {
        OrderEntity order = newOrder(OrderStatusEn.CREATED);
        em.flush();

        OrderResponseDto result = orderService.updateOrderStatus(order.getId(), "ADMIN_CANCELED", "Dana Staff");

        assertEquals("ADMIN_CANCELED", result.getStatus());
        assertEquals(order.getId().toString(), result.getId());

        // Reload from a clean persistence context: proves the new status is durable (came from
        // the atomic UPDATE), not just that the in-memory entity field was mutated.
        em.flush();
        em.clear();

        OrderEntity reloaded = em.find(OrderEntity.class, order.getId());
        assertEquals(OrderStatusEn.ADMIN_CANCELED, reloaded.getStatus());

        List<OrderStatusHistoryEntity> history = em.createQuery(
                        "select h from OrderStatusHistoryEntity h where h.order.id = :orderId",
                        OrderStatusHistoryEntity.class)
                .setParameter("orderId", order.getId())
                .getResultList();
        assertEquals(1, history.size());
        assertEquals(OrderStatusEn.ADMIN_CANCELED, history.get(0).getStatus());
        assertEquals("CREATED → ADMIN_CANCELED", history.get(0).getComment());
        assertEquals("Dana Staff", history.get(0).getChangedBy(),
                "the timeline must name the staff member, not just the status");
    }

    @Test
    @TestTransaction
    @DisplayName("refuses a transition the status does not allow, leaving the order and its timeline untouched")
    void updateOrderStatus_disallowedTransition_throwsAndChangesNothing()
    {
        OrderEntity order = newOrder(OrderStatusEn.DELIVERED);
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.updateOrderStatus(order.getId(), "IN_TRANSIT", "Dana Staff"));
        assertEquals("Cannot move an order from DELIVERED to IN_TRANSIT", ex.getMessage());

        em.flush();
        em.clear();

        assertEquals(OrderStatusEn.DELIVERED, em.find(OrderEntity.class, order.getId()).getStatus());
        assertEquals(0L, em.createQuery(
                        "select count(h) from OrderStatusHistoryEntity h where h.order.id = :orderId", Long.class)
                .setParameter("orderId", order.getId())
                .getSingleResult());
    }

    @Test
    @TestTransaction
    @DisplayName("rejects a status name that is not an OrderStatusEn value")
    void updateOrderStatus_unknownStatus_throws()
    {
        OrderEntity order = newOrder(OrderStatusEn.PAID);
        em.flush();

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> orderService.updateOrderStatus(order.getId(), "SHIPPED", "Dana Staff"));
        assertEquals("Invalid status: SHIPPED", ex.getMessage());
    }
}
