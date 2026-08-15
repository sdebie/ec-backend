package org.ecommerce.backend.scheduler;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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

/**
 * DB-backed integration tests for {@link StockRecoveryJob#releaseAbandonedOrders()}.
 * <p>
 * {@code %test.order.abandoned.hold-minutes=0} (see {@code application.properties}) makes
 * every freshly-persisted CREATED order look "abandoned" the instant it is persisted, since
 * the cutoff is computed a few milliseconds after {@code createdAt} is stamped. That makes
 * age useless as a discriminator in these tests — the thing actually under test is that
 * {@code status} is the discriminator: CREATED is released, every other status is left alone,
 * regardless of age. {@code %test.quarkus.scheduler.enabled=false} keeps the real 5-minute
 * timer from firing mid-suite; {@code releaseAbandonedOrders()} is invoked directly here.
 */
@QuarkusTest
class StockRecoveryJobTest
{
    @Inject
    StockRecoveryJob job;

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

    private List<OrderStatusHistoryEntity> statusHistoryFor(UUID orderId)
    {
        return em.createQuery(
                        "select h from OrderStatusHistoryEntity h where h.order.id = :orderId",
                        OrderStatusHistoryEntity.class)
                .setParameter("orderId", orderId)
                .getResultList();
    }

    @Test
    @TestTransaction
    @DisplayName("a CREATED order is cancelled, its stock restored, and a SYSTEM history row is written")
    void createdOrder_isReleasedAndStockRestored()
    {
        String marker = "ZZAOR-CREATED-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.CREATED, variant, 3);
        em.flush();

        job.releaseAbandonedOrders();
        em.flush();
        em.clear();

        OrderEntity reloadedOrder = em.find(OrderEntity.class, order.getId());
        assertEquals(OrderStatusEn.SYSTEM_CANCELED, reloadedOrder.getStatus());

        ProductVariantEntity reloadedVariant = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(8, reloadedVariant.getStockQuantity(), "5 in stock + 3 restored from the cancelled order");

        List<OrderStatusHistoryEntity> history = statusHistoryFor(order.getId());
        assertEquals(1, history.size());
        assertEquals(OrderStatusEn.SYSTEM_CANCELED, history.get(0).getStatus());
        assertEquals("SYSTEM", history.get(0).getChangedBy());
    }

    @Test
    @TestTransaction
    @DisplayName("an IN_STORE_PAYMENT order is a real commitment and must never be auto-cancelled")
    void inStorePaymentOrder_isUntouched()
    {
        String marker = "ZZAOR-ISP-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.IN_STORE_PAYMENT, variant, 3);
        em.flush();

        job.releaseAbandonedOrders();
        em.flush();
        em.clear();

        OrderEntity reloadedOrder = em.find(OrderEntity.class, order.getId());
        assertEquals(OrderStatusEn.IN_STORE_PAYMENT, reloadedOrder.getStatus());

        ProductVariantEntity reloadedVariant = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(5, reloadedVariant.getStockQuantity());

        assertEquals(0, statusHistoryFor(order.getId()).size());
    }

    @Test
    @TestTransaction
    @DisplayName("a PAID order is a real commitment and must never be auto-cancelled")
    void paidOrder_isUntouched()
    {
        String marker = "ZZAOR-PAID-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        job.releaseAbandonedOrders();
        em.flush();
        em.clear();

        OrderEntity reloadedOrder = em.find(OrderEntity.class, order.getId());
        assertEquals(OrderStatusEn.PAID, reloadedOrder.getStatus());

        ProductVariantEntity reloadedVariant = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(5, reloadedVariant.getStockQuantity());

        assertEquals(0, statusHistoryFor(order.getId()).size());
    }
}
