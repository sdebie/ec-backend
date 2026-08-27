package org.ecommerce.backend.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.service.DistributedLockService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * DB-backed tests for {@link StockRecoveryJob#releaseAbandonedOrders()}.
 *
 * <h2>Why these commit instead of using {@code @TestTransaction}</h2>
 * The job releases each order in its own {@code QuarkusTransaction.requiringNew()}.
 * A {@code @TestTransaction} wrapper would be worse than merely unhelpful here: the
 * job's transactions could not see the test's uncommitted fixtures at all, so every
 * sweep would find nothing and the tests would pass by vacuum. These follow the
 * codebase's tracked-id + {@code @AfterEach} cleanup pattern instead.
 *
 * <h2>Why every fixture is backdated to 2001</h2>
 * Committing changes what an invoked sweep can damage. With the former
 * {@code hold-minutes=0}, a sweep would have been eligible to cancel real CREATED
 * orders in the shared database and return their stock — permanently, since no
 * rollback covers it any more. {@code %test.order.abandoned.hold-minutes} is a full
 * year, so only these far-past fixtures are ever candidates. That also makes the
 * batch test deterministic: ordering is oldest-first, so 2001 fixtures always sort
 * ahead of anything real.
 */
@QuarkusTest
class StockRecoveryJobTest
{
    /** Comfortably older than the one-year test hold window, and older than any real row. */
    private static final LocalDateTime FAR_PAST = LocalDateTime.of(2001, 1, 1, 0, 0);

    @Inject
    StockRecoveryJob job;

    @Inject
    EntityManager em;

    /**
     * Real behaviour by default; one test stubs a single call to prove a failing
     * order cannot take the rest of the batch down with it.
     */
    @InjectSpy
    OrderService orderService;

    @Inject
    DistributedLockService lockService;

    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> variantIds = new ArrayList<>();
    private final List<UUID> productIds = new ArrayList<>();

    @AfterEach
    @Transactional
    void cleanup()
    {
        // Children before parents: OrderStatusHistoryEntity and OrderItemEntity both
        // hold a NOT NULL FK to orders.id.
        for (UUID orderId : orderIds) {
            em.createQuery("delete from OrderStatusHistoryEntity h where h.order.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        for (UUID variantId : variantIds) {
            em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        }
        for (UUID productId : productIds) {
            em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
        }
        orderIds.clear();
        variantIds.clear();
        productIds.clear();
    }

    @Transactional
    UUID newVariant(String marker, int stock)
    {
        ProductEntity product = new ProductEntity();
        product.setName(marker + "-product");
        product.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();
        productIds.add(product.getId());

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(stock);
        variant.persist();
        variantIds.add(variant.getId());

        return variant.getId();
    }

    /**
     * orders.session_id is NOT NULL at the DB level even though the entity has no such
     * annotation. createdAt is a {@code @CreationTimestamp} the entity cannot set, so it
     * is backdated with a JPQL update once the row exists.
     */
    @Transactional
    UUID newOrder(OrderStatusEn status, UUID variantId, int quantity, LocalDateTime placedAt)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.persist();
        orderIds.add(order.getId());

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(em.getReference(ProductVariantEntity.class, variantId));
        item.setQuantity(quantity);
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

    private int stockOf(UUID variantId)
    {
        em.clear();
        return em.find(ProductVariantEntity.class, variantId).getStockQuantity();
    }

    private long historyCountFor(UUID orderId)
    {
        return em.createQuery("select count(h) from OrderStatusHistoryEntity h where h.order.id = :id", Long.class)
                .setParameter("id", orderId)
                .getSingleResult();
    }

    @Test
    @DisplayName("a CREATED order is cancelled, its stock recovered, and a SYSTEM history row written")
    void createdOrder_isReleasedAndStockRecovered()
    {
        String marker = "ZZSRJ-CREATED-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.CREATED, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(orderId));
        assertEquals(8, stockOf(variantId), "5 in stock + 3 recovered from the cancelled order");

        List<OrderStatusHistoryEntity> history = em.createQuery(
                        "select h from OrderStatusHistoryEntity h where h.order.id = :id", OrderStatusHistoryEntity.class)
                .setParameter("id", orderId)
                .getResultList();
        assertEquals(1, history.size());
        assertEquals(OrderStatusEn.SYSTEM_CANCELED, history.get(0).getStatus());
        assertEquals("SYSTEM", history.get(0).getChangedBy());
    }

    /**
     * The order sits here for the whole time the shopper is at the gateway, which is
     * exactly when a checkout gets abandoned. A sweep that only matched CREATED would
     * find nothing, report a clean run every five minutes, and let every abandoned
     * online checkout hold its stock forever.
     */
    @Test
    @DisplayName("a PENDING_PAYMENT order is reclaimed — this is where an abandoned online checkout sits")
    void pendingPaymentOrder_isReleasedAndStockRecovered()
    {
        String marker = "ZZSRJ-PP-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.PENDING_PAYMENT, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(orderId));
        assertEquals(8, stockOf(variantId), "5 in stock + 3 recovered from the abandoned checkout");
    }

    /**
     * A declined payment deliberately keeps its reservation so the shopper can retry.
     * This is the other half of that bargain: if the retry never comes, the sweep is
     * the only thing that ever gets those goods back.
     */
    @Test
    @DisplayName("a PAYMENT_FAILED order is reclaimed once the retry window has passed")
    void paymentFailedOrder_isReleasedAndStockRecovered()
    {
        String marker = "ZZSRJ-PF-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.PAYMENT_FAILED, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(orderId));
        assertEquals(8, stockOf(variantId), "a retry that never came must not hold stock forever");
    }

    /**
     * The guard against the whole class of bug above: whatever the enum says is
     * reclaimable must actually be reclaimed. Adding an unpaid status and forgetting
     * the sweep fails here rather than silently leaking stock in production.
     */
    @ParameterizedTest
    @EnumSource(value = OrderStatusEn.class, names = {"CREATED", "PENDING_PAYMENT", "PAYMENT_FAILED"})
    @DisplayName("every status the enum calls reclaimable is actually swept")
    void everyReclaimableStatus_isSwept(OrderStatusEn status)
    {
        assertTrue(status.isReclaimableByStockRecovery(),
                status + " is no longer reclaimable; update this test's names alongside the enum");

        String marker = "ZZSRJ-RC-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(status, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(orderId),
                status + " is marked reclaimable but the sweep left it alone");
        assertEquals(8, stockOf(variantId), status + " was swept without its stock coming back");
    }

    /** The complement: nothing the enum excludes may be touched, whatever its age. */
    @ParameterizedTest
    @EnumSource(value = OrderStatusEn.class,
            names = {"IN_STORE_PAYMENT", "PAID", "PROCESSING", "READY_TO_SHIP", "READY_FOR_COLLECTION"})
    @DisplayName("a status the enum excludes is never swept, however old the order")
    void nonReclaimableStatus_isUntouched(OrderStatusEn status)
    {
        assertFalse(status.isReclaimableByStockRecovery(),
                status + " became reclaimable; move it to the other test");

        String marker = "ZZSRJ-NR-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(status, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(status, statusOf(orderId), status + " is a commitment and must not be auto-cancelled");
        assertEquals(5, stockOf(variantId), status + " lost its reservation to the sweep");
        assertEquals(0L, historyCountFor(orderId));
    }

    @Test
    @DisplayName("an IN_STORE_PAYMENT order is a real commitment and must never be auto-cancelled")
    void inStorePaymentOrder_isUntouched()
    {
        String marker = "ZZSRJ-ISP-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.IN_STORE_PAYMENT, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.IN_STORE_PAYMENT, statusOf(orderId));
        assertEquals(5, stockOf(variantId));
        assertEquals(0L, historyCountFor(orderId));
    }

    @Test
    @DisplayName("a PAID order is a real commitment and must never be auto-cancelled")
    void paidOrder_isUntouched()
    {
        String marker = "ZZSRJ-PAID-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.PAID, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.PAID, statusOf(orderId));
        assertEquals(5, stockOf(variantId));
        assertEquals(0L, historyCountFor(orderId));
    }

    @Test
    @DisplayName("an order newer than the hold window keeps its stock — the shopper may still be paying")
    void orderInsideHoldWindow_isUntouched()
    {
        String marker = "ZZSRJ-FRESH-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.CREATED, variantId, 3, LocalDateTime.now());

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.CREATED, statusOf(orderId));
        assertEquals(5, stockOf(variantId));
    }

    @Test
    @DisplayName("an order that cannot be released does not cost the rest of the batch their release")
    void failingOrder_doesNotRollBackTheWholeBatch()
    {
        String marker = "ZZSRJ-POISON-" + UUID.randomUUID().toString().substring(0, 8);
        UUID poisonVariant = newVariant(marker + "-poison", 0);
        UUID healthyVariant = newVariant(marker + "-healthy", 0);

        UUID poisonOrder = newOrder(OrderStatusEn.CREATED, poisonVariant, 4, FAR_PAST);
        UUID healthyOrder = newOrder(OrderStatusEn.CREATED, healthyVariant, 7, FAR_PAST.plusMinutes(1));

        // Fails only the first order's release. Sweep-wide, this would roll the
        // healthy order's release back too and neither would ever be released.
        doThrow(new IllegalStateException("simulated stock recovery failure"))
                .when(orderService).applyTransition(
                        argThat(order -> order != null && poisonOrder.equals(order.getId())), any());
        doCallRealMethod()
                .when(orderService).applyTransition(
                        argThat(order -> order != null && !poisonOrder.equals(order.getId())), any());

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(healthyOrder), "the healthy order must still be released");
        assertEquals(7, stockOf(healthyVariant), "and its stock must still come back");

        assertEquals(OrderStatusEn.CREATED, statusOf(poisonOrder),
                "the failed order rolls back whole — status included — so the next tick retries it");
        assertEquals(0, stockOf(poisonVariant));
        assertEquals(0L, historyCountFor(poisonOrder), "a rolled-back release must leave no timeline entry");
    }

    // ── Cross-instance coordination ─────────────────────────────────────────

    @Test
    @DisplayName("the sweep is skipped entirely while another instance holds the lock")
    void sweepIsSkipped_whileAnotherInstanceHoldsTheLock() throws InterruptedException
    {
        String marker = "ZZSRJ-LOCKED-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.CREATED, variantId, 3, FAR_PAST);

        // Simulates a different backend replica already running this tick's sweep.
        Optional<String> otherInstanceToken =
                lockService.tryAcquire(StockRecoveryJob.LOCK_NAME, Duration.ofSeconds(30));
        assertTrue(otherInstanceToken.isPresent(), "test setup: the lock must start free");

        try {
            job.releaseAbandonedOrders();

            assertEquals(OrderStatusEn.CREATED, statusOf(orderId),
                    "a genuinely eligible order must be left untouched when the lock is held elsewhere");
            assertEquals(5, stockOf(variantId));
            assertEquals(0L, historyCountFor(orderId), "a skipped sweep must not even attempt the order");
        } finally {
            lockService.release(StockRecoveryJob.LOCK_NAME, otherInstanceToken.get());
        }
    }

    @Test
    @DisplayName("the sweep proceeds normally once the lock is free again")
    void sweepProceeds_onceTheLockIsFreeAgain()
    {
        String marker = "ZZSRJ-UNLOCKED-" + UUID.randomUUID().toString().substring(0, 8);
        UUID variantId = newVariant(marker, 5);
        UUID orderId = newOrder(OrderStatusEn.CREATED, variantId, 3, FAR_PAST);

        job.releaseAbandonedOrders();

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, statusOf(orderId));
        assertEquals(8, stockOf(variantId));

        // The lock is free again — proves the job releases on its own.
        Optional<String> nextTickToken =
                lockService.tryAcquire(StockRecoveryJob.LOCK_NAME, Duration.ofSeconds(30));
        assertTrue(nextTickToken.isPresent(), "the job must release its own lock when the sweep completes");
        lockService.release(StockRecoveryJob.LOCK_NAME, nextTickToken.get());
    }
}
