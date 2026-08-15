package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Genuine two-thread concurrency test for {@code AbandonedOrderReleaseService.releaseOrder}'s
 * atomic conditional UPDATE. Cannot use {@link io.quarkus.test.TestTransaction} — same reason as
 * {@code OrderServiceCreateOrderFromCartConcurrencyIT}: that wraps the whole test in one
 * transaction, the opposite of what a race needs — so it follows this codebase's tracked-ID +
 * manual {@code @AfterEach} cleanup pattern instead.
 * <p>
 * {@code releaseOrder} is {@code private}, and {@link AbandonedOrderReleaseServiceTest}'s
 * existing negative tests (IN_STORE_PAYMENT/PAID) only prove the SELECT-time status filter, which
 * never calls {@code releaseOrder} at all for those orders — the {@code claimed == 0} branch
 * inside {@code releaseOrder} itself has no coverage otherwise. Two threads both sweeping
 * {@link AbandonedOrderReleaseService#releaseAbandonedOrders()} against the SAME seeded CREATED
 * order reach it directly: with {@code %test.order.abandoned.hold-minutes=0} both threads' SELECT
 * can see the order as CREATED, both attempt the atomic claim, and Postgres's row lock guarantees
 * exactly one UPDATE wins — the loser genuinely observes 0 rows affected, not a simulated one.
 */
@QuarkusTest
class AbandonedOrderReleaseServiceConcurrencyIT
{
    @Inject
    AbandonedOrderReleaseService service;

    @Inject
    EntityManager em;

    private UUID productId;
    private UUID variantId;
    private UUID orderId;

    @BeforeEach
    @Transactional
    void seedOneAbandonedOrder()
    {
        String marker = "ZZAOR-RACE-" + UUID.randomUUID().toString().substring(0, 8);

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
        variant.setStockQuantity(5);
        variant.persist();
        variantId = variant.getId();

        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.persist();
        orderId = order.getId();

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(variant);
        item.setQuantity(3);
        item.setUnitPrice(BigDecimal.ZERO);
        item.persist();
        order.getItems().add(item);
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        // Children before parents: OrderStatusHistoryEntity and OrderItemEntity both hold a
        // NOT NULL FK to orders.id.
        em.createQuery("delete from OrderStatusHistoryEntity h where h.order.id = :id").setParameter("id", orderId).executeUpdate();
        em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
        em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
    }

    @Test
    @Timeout(30)
    @DisplayName("two concurrent release sweeps racing for the same abandoned order: exactly one wins, stock restored exactly once")
    void concurrentReleaseSweeps_exactlyOneWinsTheRace() throws Exception
    {
        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Void> sweep = () -> {
            ready.countDown();
            go.await();
            service.releaseAbandonedOrders();
            return null;
        };

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(sweep));
        }

        // Release both threads together once both have reached the starting line, to maximize
        // (not guarantee — the atomic UPDATE is what actually guarantees correctness, not this
        // synchronization) the chance both SELECTs see the order as still CREATED.
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        for (Future<Void> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        OrderEntity finalOrder = QuarkusTransaction.requiringNew().call(() -> OrderEntity.findById(orderId));
        assertEquals(OrderStatusEn.SYSTEM_CANCELED, finalOrder.getStatus());

        Integer finalStock = QuarkusTransaction.requiringNew().call(() -> {
            ProductVariantEntity reloaded = ProductVariantEntity.findById(variantId);
            return reloaded.getStockQuantity();
        });
        assertEquals(8, finalStock, "5 in stock + 3 restored exactly once, not twice");

        Long historyCount = QuarkusTransaction.requiringNew().call(() -> OrderStatusHistoryEntity.count("order.id = ?1", orderId));
        assertEquals(1L, historyCount, "exactly one history row, not one per racing thread");
    }
}
