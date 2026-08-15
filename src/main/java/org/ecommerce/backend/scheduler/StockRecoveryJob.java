package org.ecommerce.backend.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Recovers stock held by checkouts that were started but never paid.
 * {@link OrderService#createOrderFromCart} decrements stock the moment an
 * order reaches CREATED, before payment — so an abandoned checkout would
 * otherwise hold that stock forever. This sweep cancels CREATED orders older
 * than the configured hold window and returns their stock to sale.
 * <p>
 * Only CREATED is touched. IN_STORE_PAYMENT, PAID and every later status are
 * real commitments, not abandoned carts, and must never be auto-cancelled.
 * That narrow filter is why this sweep is not the whole story: a staff member
 * cancelling an order by hand recovers its stock through the same
 * {@link OrderService#restoreStock} call, since such an order never becomes
 * visible to this query.
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
 * {@link #releaseOrder}, not on transaction scope — which is also why
 * {@code SKIP} below is about cost, not safety.
 */
@ApplicationScoped
public class StockRecoveryJob
{
    private static final Logger LOG = Logger.getLogger(StockRecoveryJob.class);

    @ConfigProperty(name = "order.abandoned.hold-minutes", defaultValue = "30")
    int holdMinutes;

    @ConfigProperty(name = "order.abandoned.batch-size", defaultValue = "200")
    int batchSize;

    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    /**
     * SKIP rather than the default PROCEED: a sweep that outruns its interval
     * would otherwise have a second sweep started alongside it, and each extra
     * runner makes the next one likelier. Overlap is never a correctness
     * problem — the atomic claim admits one winner — so this exists purely to
     * stop the job competing with itself for the database.
     */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void releaseAbandonedOrders()
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
     * Oldest first, so a backlog drains in the order it accumulated and a run
     * cannot starve the orders that have been waiting longest.
     */
    private List<UUID> findAbandonedIds(LocalDateTime cutoff)
    {
        return em.createQuery(
                        "select o.id from OrderEntity o where o.status = :status and o.createdAt < :cutoff order by o.createdAt",
                        UUID.class)
                .setParameter("status", OrderStatusEn.CREATED)
                .setParameter("cutoff", cutoff)
                .setMaxResults(batchSize)
                .getResultList();
    }

    /** @return whether this call is the one that released the order */
    private boolean releaseOrder(UUID orderId)
    {
        // Claim before loading anything: an atomic conditional UPDATE, exactly like the
        // stock decrement itself. The candidate query ran in an earlier transaction, so
        // an ITN or a staff action may have moved this order off CREATED since it was
        // listed. Zero rows affected means we lost that race and must not touch its
        // stock; whoever did change it now owns the stock it was holding.
        long claimed = OrderEntity.update("status = ?1 where id = ?2 and status = ?3",
                OrderStatusEn.SYSTEM_CANCELED, orderId, OrderStatusEn.CREATED);
        if (claimed == 0) {
            LOG.debugf("Skipped releasing order %s: its status changed concurrently", orderId);
            return false;
        }

        OrderEntity order = loadWithLines(orderId);
        if (order == null) {
            LOG.warnf("Order %s vanished after its status was claimed; no stock recovered for it", orderId);
            return false;
        }

        orderService.restoreStock(order);

        OrderStatusHistoryEntity.record(order, OrderStatusEn.SYSTEM_CANCELED,
                "Automatically cancelled: checkout was not completed within the stock hold window",
                OrderService.SYSTEM_ACTOR);

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
