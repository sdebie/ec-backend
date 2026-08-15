package org.ecommerce.backend.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

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
 */
@ApplicationScoped
public class StockRecoveryJob
{
    private static final Logger LOG = Logger.getLogger(StockRecoveryJob.class);

    @ConfigProperty(name = "order.abandoned.hold-minutes", defaultValue = "30")
    int holdMinutes;

    @Inject
    OrderService orderService;

    @Scheduled(every = "5m")
    @Transactional
    public void releaseAbandonedOrders()
    {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(holdMinutes);
        List<OrderEntity> abandoned = OrderEntity.list("status = ?1 and createdAt < ?2", OrderStatusEn.CREATED, cutoff);

        for (OrderEntity order : abandoned) {
            releaseOrder(order);
        }

        if (!abandoned.isEmpty()) {
            LOG.infof("Released %d abandoned order(s) older than %d minute(s)", abandoned.size(), holdMinutes);
        }
    }

    private void releaseOrder(OrderEntity order)
    {
        // Claim the order first: an atomic conditional UPDATE, exactly like the stock
        // decrement itself. The prior SELECT only filtered on status — by the time we
        // get here, an ITN or a staff action could have already moved it off CREATED.
        // Zero rows affected means we lost that race and must not touch its stock;
        // whoever did change it now owns what happens to the stock it was holding.
        long claimed = OrderEntity.update("status = ?1 where id = ?2 and status = ?3",
                OrderStatusEn.SYSTEM_CANCELED, order.getId(), OrderStatusEn.CREATED);
        if (claimed == 0) {
            LOG.debugf("Skipped releasing order %s: its status changed concurrently", order.getId());
            return;
        }
        order.setStatus(OrderStatusEn.SYSTEM_CANCELED);

        orderService.restoreStock(order);

        OrderStatusHistoryEntity.record(order, OrderStatusEn.SYSTEM_CANCELED,
                "Automatically cancelled: checkout was not completed within the stock hold window",
                OrderService.SYSTEM_ACTOR);

        LOG.debugf("Released abandoned order %s (created %s), stock recovered", order.getId(), order.getCreatedAt());
    }
}
