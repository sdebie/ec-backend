package org.ecommerce.backend.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves one sweep releases at most {@code order.abandoned.batch-size} orders and that
 * the remainder genuinely drains on later ticks rather than stalling at the boundary.
 * <p>
 * A separate class because the batch size has to come from a {@link TestProfile}.
 * Assigning {@code job.batchSize} from a test does nothing: {@code StockRecoveryJob} is
 * {@code @ApplicationScoped}, so the injected reference is a CDI client proxy — method
 * calls are delegated to the underlying bean, plain field writes are not. Such a test
 * passes while sweeping with the production batch size, proving the opposite of what it
 * claims. Config overrides are per test class, hence this split.
 * <p>
 * Fixtures are backdated to 2001 for the same reason as {@link StockRecoveryJobTest}:
 * the job commits each release, so only far-past rows may ever be candidates. Here it
 * matters twice over — the sweep takes the oldest first, so 2001 fixtures also sort
 * ahead of any real order and make the batch boundary deterministic.
 */
@QuarkusTest
@TestProfile(StockRecoveryJobBatchLimitTest.SmallBatchProfile.class)
class StockRecoveryJobBatchLimitTest
{
    private static final LocalDateTime FAR_PAST = LocalDateTime.of(2001, 1, 1, 0, 0);

    public static class SmallBatchProfile implements QuarkusTestProfile
    {
        @Override
        public Map<String, String> getConfigOverrides()
        {
            return Map.of("order.abandoned.batch-size", "2");
        }
    }

    @Inject
    StockRecoveryJob job;

    @Inject
    EntityManager em;

    private final List<UUID> orderIds = new ArrayList<>();
    private UUID variantId;
    private UUID productId;

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : orderIds) {
            em.createQuery("delete from OrderStatusHistoryEntity h where h.order.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        orderIds.clear();

        if (variantId != null) {
            em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
            variantId = null;
        }
        if (productId != null) {
            em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
            productId = null;
        }
    }

    @Transactional
    void seedVariant(String marker)
    {
        ProductEntity product = new ProductEntity();
        product.setName(marker + "-product");
        product.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();
        productId = product.getId();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(0);
        variant.persist();
        variantId = variant.getId();
    }

    /** createdAt is a {@code @CreationTimestamp} the entity cannot set; JPQL backdates it. */
    @Transactional
    UUID seedAbandonedOrder(LocalDateTime placedAt)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.persist();
        orderIds.add(order.getId());

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(em.getReference(ProductVariantEntity.class, variantId));
        item.setQuantity(1);
        item.setUnitPrice(BigDecimal.ZERO);
        item.persist();

        em.flush();
        em.createQuery("update OrderEntity o set o.createdAt = :placedAt where o.id = :id")
                .setParameter("placedAt", placedAt)
                .setParameter("id", order.getId())
                .executeUpdate();

        return order.getId();
    }

    private OrderStatusEn statusOf(UUID orderId)
    {
        em.clear();
        return em.find(OrderEntity.class, orderId).getStatus();
    }

    private int stock()
    {
        em.clear();
        return em.find(ProductVariantEntity.class, variantId).getStockQuantity();
    }

    @Test
    @DisplayName("a sweep releases at most one batch, oldest first, and the backlog drains on later ticks")
    void batchLimit_boundsOneSweepThenDrains()
    {
        seedVariant("ZZSRJB-" + UUID.randomUUID().toString().substring(0, 8));

        UUID oldest = seedAbandonedOrder(FAR_PAST);
        UUID middle = seedAbandonedOrder(FAR_PAST.plusMinutes(1));
        UUID newest = seedAbandonedOrder(FAR_PAST.plusMinutes(2));

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(oldest));
        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(middle));
        assertEquals(OrderStatusEn.CREATED, statusOf(newest), "the third order must wait for the next tick");
        assertEquals(2, stock(), "only the two released orders returned their stock");

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(newest), "the backlog must drain, not stall at the boundary");
        assertEquals(3, stock());
    }
}
