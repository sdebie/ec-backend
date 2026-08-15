package org.ecommerce.backend.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Releases stock held by checkouts that were started but never paid.
 * {@link OrderService#createOrderFromCart} decrements stock the moment an
 * order reaches CREATED, before payment — so an abandoned checkout would
 * otherwise hold that stock forever. This sweep cancels CREATED orders older
 * than the configured hold window and restores their stock.
 * <p>
 * Only CREATED is touched. IN_STORE_PAYMENT, PAID and every later status are
 * real commitments, not abandoned carts, and must never be auto-cancelled.
 */
@ApplicationScoped
public class AbandonedOrderReleaseService
{
    private static final Logger LOG = Logger.getLogger(AbandonedOrderReleaseService.class);

    @ConfigProperty(name = "order.abandoned.hold-minutes", defaultValue = "30")
    int holdMinutes;

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

        for (OrderItemEntity item : order.getItems()) {
            if (item.getVariant() == null || item.getQuantity() == null) {
                continue;
            }
            ProductVariantEntity.update("stockQuantity = stockQuantity + ?1 where id = ?2",
                    item.getQuantity(), item.getVariant().getId());
        }

        OrderStatusHistoryEntity.record(order, OrderStatusEn.SYSTEM_CANCELED,
                "Automatically cancelled: checkout was not completed within the stock hold window",
                OrderService.SYSTEM_ACTOR);

        LOG.debugf("Released abandoned order %s (created %s), stock restored", order.getId(), order.getCreatedAt());
    }
}
