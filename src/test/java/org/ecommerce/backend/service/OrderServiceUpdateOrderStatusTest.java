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

        OrderResponseDto result = orderService.updateOrderStatus(order.getSessionId().toString(), "PAID");

        assertEquals("PAID", result.getStatus());
        assertEquals(order.getId().toString(), result.getId());

        // Reload from a clean persistence context: proves the new status is durable (came from
        // the atomic UPDATE), not just that the in-memory entity field was mutated.
        em.flush();
        em.clear();

        OrderEntity reloaded = em.find(OrderEntity.class, order.getId());
        assertEquals(OrderStatusEn.PAID, reloaded.getStatus());

        List<OrderStatusHistoryEntity> history = em.createQuery(
                        "select h from OrderStatusHistoryEntity h where h.order.id = :orderId",
                        OrderStatusHistoryEntity.class)
                .setParameter("orderId", order.getId())
                .getResultList();
        assertEquals(1, history.size());
        assertEquals(OrderStatusEn.PAID, history.get(0).getStatus());
        assertEquals("Order Update", history.get(0).getComment());
    }
}
