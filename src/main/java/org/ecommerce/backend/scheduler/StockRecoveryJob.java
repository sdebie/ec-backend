package org.ecommerce.backend.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.DistributedLockService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.backend.service.TransitionOutcome;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Recovers stock held by checkouts that were started but never paid.
 * {@link OrderService#createOrderFromCart} decrements stock the moment an
 * order reaches CREATED, before payment — so an abandoned checkout would
 * otherwise hold that stock forever. This sweep cancels CREATED orders older
 * than the configured hold window and returns their stock to sale.
 * <p>
 * Which statuses it touches is {@link OrderStatusEn#isReclaimableByStockRecovery()},
 * not a literal here — every unpaid status that keeps its reservation, and no other.
 * That covers a checkout abandoned before payment was started, one abandoned at the
 * gateway, and one whose card was declined and never retried; the last of those holds
 * its stock on purpose so a retry has something to buy, which is exactly why something
 * has to reclaim it if the retry never comes.
 * <p>
 * PAID and every later status are real commitments and must never be auto-cancelled.
 * IN_STORE_PAYMENT is excluded too, although unpaid: a shopper coming to the shop to
 * pay has not abandoned anything, so their order is cancelled by hand or not at all.
 * <p>
 * That narrow filter is also why this sweep is not the whole story: a staff member
 * cancelling an order by hand recovers its stock through the same
 * {@link OrderService#applyTransition} call this uses, since such an order never
 * becomes visible to this query.
 *
 * <h2>Why each order gets its own transaction</h2>
 * A sweep-wide transaction makes this job destroy itself under exactly the
 * conditions it exists to handle. Three properties depend on the per-order
 * boundary, and all three are lost the moment someone reintroduces
 * {@code @Transactional} on the sweep:
 * <ol>
 *   <li><b>Progress survives failure.</b> An order that cannot be released
 *       rolls back alone. Sweep-wide, one bad row discards every release
 *       already done in that tick, and the same row is re-read next tick — so
 *       the job never gets past it.</li>
 *   <li><b>Checkout keeps running.</b> Recovering stock takes a row lock on
 *       {@code product_variants}, held until commit. Per order that is
 *       milliseconds; sweep-wide it is the whole sweep, and checkout's own
 *       conditional decrement blocks behind it on exactly the popular variants
 *       most likely to appear in abandoned orders.</li>
 *   <li><b>No transaction outlives its timeout.</b> A sweep-wide transaction
 *       grows with the backlog until it exceeds the JTA timeout, rolls back,
 *       releases nothing, and meets a larger backlog next tick — stock
 *       recovery then stops permanently, and silently.</li>
 * </ol>
 * The batch limit bounds the same risks by work rather than by time: a backlog
 * drains over successive ticks instead of in one oversized pass.
 * <p>
 * Correctness under all of this rests on the atomic conditional claim in
 * {@link #releaseOrder}, not on transaction scope or on the lock below — which is
 * also why {@code SKIP} and the lock are both about cost, not safety.
 *
 * <h2>Cross-instance coordination</h2>
 * {@code SKIP} only stops a second run on the same JVM. {@link #lockService} adds a
 * cluster-wide lock so only one replica runs a given tick; every other replica skips
 * it and tries again next time.
 */
@ApplicationScoped
public class StockRecoveryJob
{
    private static final Logger LOG = Logger.getLogger(StockRecoveryJob.class);

    /** Package-visible so the test can simulate another instance already holding it. */
    static final String LOCK_NAME = "stock-recovery-sweep";

    @ConfigProperty(name = "order.abandoned.hold-minutes", defaultValue = "30")
    int holdMinutes;

    @ConfigProperty(name = "order.abandoned.batch-size", defaultValue = "200")
    int batchSize;

    @ConfigProperty(name = "order.abandoned.lock-lease-seconds", defaultValue = "240")
    long lockLeaseSeconds;

    @Inject
    OrderService orderService;

    @Inject
    DistributedLockService lockService;

    @Inject
    EntityManager em;

    /**
     * SKIP rather than PROCEED: overlap is never a correctness problem (the atomic
     * claim admits one winner), so this exists purely to stop the job competing
     * with itself. The lock below is the same idea across replicas.
     */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void releaseAbandonedOrders()
    {
        Optional<String> lockToken = lockService.tryAcquire(LOCK_NAME, Duration.ofSeconds(lockLeaseSeconds));
        if (lockToken.isEmpty()) {
            LOG.debugf("Stock recovery sweep skipped: another instance already holds the lock");
            return;
        }

        try {
            sweep();
        } finally {
            lockService.release(LOCK_NAME, lockToken.get());
        }
    }

    private void sweep()
    {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(holdMinutes);
        List<UUID> candidateIds = QuarkusTransaction.requiringNew().call(() -> findAbandonedIds(cutoff));

        if (candidateIds.isEmpty()) {
            return;
        }

        int released = 0;
        int failed = 0;

        for (UUID orderId : candidateIds) {
            try {
                if (Boolean.TRUE.equals(QuarkusTransaction.requiringNew().call(() -> releaseOrder(orderId)))) {
                    released++;
                }
            } catch (RuntimeException e) {
                // This order's own transaction has rolled back, so it is untouched and
                // still CREATED — the next tick retries it. Everything already released
                // in this batch stays released.
                failed++;
                LOG.errorf(e, "Could not release abandoned order %s; continuing with the rest of the batch", orderId);
            }
        }

        LOG.infof("Stock recovery: released %d of %d candidate order(s) older than %d minute(s), %d failed",
                released, candidateIds.size(), holdMinutes, failed);

        // A full batch means the backlog was larger than one pass. Say so: a silently
        // truncated sweep reads exactly like a completed one in the logs.
        if (candidateIds.size() == batchSize) {
            LOG.infof("Stock recovery batch was full at %d; more abandoned orders remain for the next tick", batchSize);
        }
    }

    /**
     * The statuses this sweep reclaims, asked of the enum rather than listed here.
     * <p>
     * A hardcoded status is how this job silently stops working. An order that takes
     * one more step before payment — reaching the gateway, or having a card declined —
     * would stop matching, every abandoned checkout would hold its stock forever, and
     * the logs would still report a clean sweep every five minutes. Deriving the set
     * means a new unpaid status is covered the moment
     * {@link OrderStatusEn#isReclaimableByStockRecovery()} says it should be.
     */
    private static final List<OrderStatusEn> RECLAIMABLE = Arrays.stream(OrderStatusEn.values())
            .filter(OrderStatusEn::isReclaimableByStockRecovery)
            .toList();

    /**
     * Oldest first, so a backlog drains in the order it accumulated and a run
     * cannot starve the orders that have been waiting longest.
     */
    private List<UUID> findAbandonedIds(LocalDateTime cutoff)
    {
        return em.createQuery(
                        "select o.id from OrderEntity o where o.status in :statuses and o.createdAt < :cutoff "
                                + "order by o.createdAt",
                        UUID.class)
                .setParameter("statuses", RECLAIMABLE)
                .setParameter("cutoff", cutoff)
                .setMaxResults(batchSize)
                .getResultList();
    }

    /** @return whether this call is the one that released the order */
    private boolean releaseOrder(UUID orderId)
    {
        OrderEntity order = loadWithLines(orderId);
        if (order == null) {
            LOG.warnf("Order %s vanished between being listed and being released", orderId);
            return false;
        }

        // The candidate query ran in an earlier transaction, so a payment callback or a
        // staff action may have moved this order since it was listed. Naming the status
        // just read as the expected one is what makes that a reported race rather than
        // an error: whoever did move it now owns the stock it was holding, and returning
        // it here would hand the same units out twice.
        OrderStatusEn claimedFrom = order.getStatus();
        if (claimedFrom == null || !claimedFrom.isReclaimableByStockRecovery()) {
            LOG.debugf("Skipped releasing order %s: it is now %s, which the sweep does not reclaim",
                    orderId, claimedFrom);
            return false;
        }

        TransitionOutcome outcome = orderService.applyTransition(order,
                StatusTransition.system(claimedFrom, OrderStatusEn.SYSTEM_CANCELED,
                        "Automatically cancelled: checkout was not completed within the stock hold window"));

        if (!outcome.claimed()) {
            LOG.debugf("Skipped releasing order %s: its status changed concurrently", orderId);
            return false;
        }

        LOG.debugf("Released abandoned order %s (created %s), stock recovered", orderId, order.getCreatedAt());
        return true;
    }

    /**
     * Both {@code items} and its {@code variant} are LAZY, and the variant ids are
     * exactly what the stock update needs — so fetch them with the order rather than
     * paying a query per line. Safe to fetch a collection here because this loads a
     * single order; the paging happens in {@link #findAbandonedIds}, over ids only.
     */
    private OrderEntity loadWithLines(UUID orderId)
    {
        List<OrderEntity> found = em.createQuery(
                        "select distinct o from OrderEntity o "
                                + "left join fetch o.items i "
                                + "left join fetch i.variant "
                                + "where o.id = :id", OrderEntity.class)
                .setParameter("id", orderId)
                .getResultList();

        return found.isEmpty() ? null : found.get(0);
    }
}
