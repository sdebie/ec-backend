package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed tests for the stock side of a refund.
 * <p>
 * A refund is the only transition where either stock outcome is legitimate: it is
 * reachable from PAID and IN_STORE_PAYMENT (goods never dispatched) and from DELIVERED
 * (goods gone). The server therefore takes the answer rather than inferring it — status
 * lags physical reality, and an order shipped without being moved to IN_TRANSIT still
 * reads PAID, so inference would return stock for goods that had already left.
 * <p>
 * Every case asserts a stock <b>figure</b>. The status change is correct in both
 * directions, so asserting on status alone would pass against either behaviour.
 */
@QuarkusTest
class OrderServiceRefundStockTest
{
    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    private ProductVariantEntity newVariant(String marker, int stock)
    {
        ProductEntity product = new ProductEntity();
        product.setName(marker + "-product");
        product.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(stock);
        variant.persist();
        return variant;
    }

    /** orders.session_id is NOT NULL at the DB level even though the entity has no such annotation. */
    private OrderEntity newOrder(OrderStatusEn status, ProductVariantEntity variant, int quantity)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.persist();

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(variant);
        item.setQuantity(quantity);
        item.setUnitPrice(BigDecimal.ZERO);
        item.persist();
        order.getItems().add(item);

        return order;
    }

    /** Reloads from a clean persistence context, so a stale in-memory field cannot fake a pass. */
    private int stockOf(UUID variantId)
    {
        em.flush();
        em.clear();
        return em.find(ProductVariantEntity.class, variantId).getStockQuantity();
    }

    private List<OrderStatusHistoryEntity> historyFor(UUID orderId)
    {
        return em.createQuery(
                        "select h from OrderStatusHistoryEntity h where h.order.id = :id", OrderStatusHistoryEntity.class)
                .setParameter("id", orderId)
                .getResultList();
    }

    @Test
    @TestTransaction
    @DisplayName("refunding an undispatched PAID order with restock returns its stock")
    void refundPaidWithRestock_returnsStock() throws GraphQLException
    {
        String marker = "ZZRFND-PAID-Y-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", true);

        assertEquals(8, stockOf(variant.getId()), "5 in stock + 3 returned by the refund");
    }

    @Test
    @TestTransaction
    @DisplayName("refunding a PAID order without restock leaves stock alone — the goods may already have shipped")
    void refundPaidWithoutRestock_leavesStock() throws GraphQLException
    {
        String marker = "ZZRFND-PAID-N-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", false);

        assertEquals(5, stockOf(variant.getId()));
    }

    @Test
    @TestTransaction
    @DisplayName("refunding an IN_STORE_PAYMENT order with restock returns its stock")
    void refundInStorePaymentWithRestock_returnsStock() throws GraphQLException
    {
        String marker = "ZZRFND-ISP-Y-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.IN_STORE_PAYMENT, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", true);

        assertEquals(8, stockOf(variant.getId()));
    }

    @Test
    @TestTransaction
    @DisplayName("refunding an IN_STORE_PAYMENT order without restock leaves stock alone")
    void refundInStorePaymentWithoutRestock_leavesStock() throws GraphQLException
    {
        String marker = "ZZRFND-ISP-N-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.IN_STORE_PAYMENT, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", false);

        assertEquals(5, stockOf(variant.getId()));
    }

    @Test
    @TestTransaction
    @DisplayName("refunding a DELIVERED order without restock leaves stock alone — the goods are gone")
    void refundDeliveredWithoutRestock_leavesStock() throws GraphQLException
    {
        String marker = "ZZRFND-DLV-N-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.DELIVERED, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", false);

        assertEquals(5, stockOf(variant.getId()));
    }

    @Test
    @TestTransaction
    @DisplayName("a DELIVERED order whose goods came back may still restock — the staff member decides, not the status")
    void refundDeliveredWithRestock_returnsStock() throws GraphQLException
    {
        String marker = "ZZRFND-DLV-Y-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.DELIVERED, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", true);

        assertEquals(8, stockOf(variant.getId()),
                "the source status only pre-selects the answer; it must not override the one given");
    }

    @Test
    @TestTransaction
    @DisplayName("a refund with no restock decision is rejected, changing nothing")
    void refundWithoutDecision_isRejected()
    {
        String marker = "ZZRFND-NULL-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff", null));
        assertEquals("A refund must state whether its items return to stock", ex.getMessage());

        assertEquals(5, stockOf(variant.getId()));
        assertEquals(OrderStatusEn.PAID, em.find(OrderEntity.class, order.getId()).getStatus());
        assertEquals(0, historyFor(order.getId()).size());
    }

    @Test
    @TestTransaction
    @DisplayName("a restock decision on a non-refund transition is rejected rather than ignored")
    void restockDecisionOnNonRefund_isRejected()
    {
        String marker = "ZZRFND-WRONG-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> orderService.updateOrderStatus(order.getId(), "IN_TRANSIT", "Dana Staff", true));
        assertEquals("Only a refund may carry a restock decision", ex.getMessage());

        assertEquals(5, stockOf(variant.getId()));
        assertEquals(OrderStatusEn.PAID, em.find(OrderEntity.class, order.getId()).getStatus());
        assertEquals(0, historyFor(order.getId()).size());
    }

    @Test
    @TestTransaction
    @DisplayName("the timeline says what happened to the stock, so silence is never ambiguous")
    void history_recordsTheStockOutcome() throws GraphQLException
    {
        String marker = "ZZRFND-HIST-" + UUID.randomUUID().toString().substring(0, 8);

        ProductVariantEntity restocked = newVariant(marker + "-y", 5);
        OrderEntity restockedOrder = newOrder(OrderStatusEn.PAID, restocked, 3);
        ProductVariantEntity kept = newVariant(marker + "-n", 5);
        OrderEntity keptOrder = newOrder(OrderStatusEn.DELIVERED, kept, 3);
        em.flush();

        orderService.updateOrderStatus(restockedOrder.getId(), "REFUNDED", "Dana Staff", true);
        orderService.updateOrderStatus(keptOrder.getId(), "REFUNDED", "Dana Staff", false);
        em.flush();

        String restockedComment = historyFor(restockedOrder.getId()).get(0).getComment();
        assertTrue(restockedComment.contains("PAID → REFUNDED"), restockedComment);
        assertTrue(restockedComment.contains("stock returned"), restockedComment);

        String keptComment = historyFor(keptOrder.getId()).get(0).getComment();
        assertTrue(keptComment.contains("DELIVERED → REFUNDED"), keptComment);
        assertTrue(keptComment.contains("stock not returned"), keptComment);
    }
}
