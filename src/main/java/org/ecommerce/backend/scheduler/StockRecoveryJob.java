package org.ecommerce.backend.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.backend.service.TransitionOutcome;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.repository.OrderRepository;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Recovers stock held by checkouts that were started but never paid.
 * {@link OrderService#createOrderFromCart} decrements stock the moment an
 * order reaches CREATED, before payment — so an abandoned checkout would
 * otherwise hold that stock forever. This sweep cancels CREATED orders older
 * than the configured hold window and returns their stock to sale.
 * <p>
 * Reclaimable statuses come from {@link OrderStatusEn#isReclaimableByStockRecovery()},
 * not a literal here. PAID and later are real commitments and are never touched;
 * IN_STORE_PAYMENT is excluded too — a shopper who came to the shop hasn't abandoned
 * anything. PAYMENT_FAILED stays reclaimable on purpose, so a retry has something to
 * buy if the retry never comes. Staff cancellation recovers stock the same way, via
 * {@link OrderService#applyTransition}, so a hand-cancelled order never needs this sweep.
 * <p>
 * Each order commits in its own transaction rather than one sweep-wide transaction,
 * which would let one bad row roll back every release already done that tick, hold a
 * {@code product_variants} row lock for the whole sweep instead of milliseconds, and
 * eventually exceed the JTA timeout as the backlog grows — stopping stock recovery
 * permanently and silently. The batch limit bounds the same risk by work instead of
 * time. Correctness rests on the atomic conditional claim in {@link #releaseOrder},
 * not on transaction scope — {@code SKIP} (stopping a second run on this JVM) is
 * about cost, not safety. Nothing stops two replicas' sweeps overlapping; the same
 * atomic claim makes that a wasted query on the loser, not a double release.
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
    OrderRepository orderRepository;

    /**
     * SKIP rather than PROCEED: overlap is never a correctness problem (the atomic
     * claim admits one winner), so this exists purely to stop the job competing
     * with itself.
     */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void releaseAbandonedOrders()
    {
        sweep();
    }

    private void sweep()
    {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(holdMinutes);
        List<UUID> candidateIds = QuarkusTransaction.requiringNew()
                .call(() -> orderRepository.findAbandonedIds(RECLAIMABLE, cutoff, batchSize));

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

    /** @return whether this call is the one that released the order */
    private boolean releaseOrder(UUID orderId)
    {
        OrderEntity order = orderRepository.findWithItemsAndVariant(orderId);
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
}
