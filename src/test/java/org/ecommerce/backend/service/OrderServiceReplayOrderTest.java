package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct test of {@link OrderService#replayOrder(OrderEntity)}, called from
 * <em>inside</em> an active transaction — the opposite of how it actually runs in
 * production (design §3.3: replay happens outside a transaction).
 * <p>
 * Sabotage 5.2 (swap {@code computeTotals} for {@code repriceOrder}) stays green when
 * verified only through the REST endpoint, because with no transaction open nothing
 * flushes regardless of which one {@code replayOrder} calls — the boundary hides the
 * bug, it does not fix it (see design.md §4's own warning). Calling the method inside
 * an explicit transaction here removes that ambiguity: if it ever calls
 * {@code repriceOrder}, the write becomes observable.
 */
@QuarkusTest
class OrderServiceReplayOrderTest
{
    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    @DisplayName("replayOrder never writes the order's persisted totalAmount, even called inside an active transaction")
    void replayOrder_neverWritesPersistedTotal_evenInsideATransaction()
    {
        String marker = "ZZREPLAY-" + UUID.randomUUID().toString().substring(0, 8);

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
        variant.setStockQuantity(10);
        variant.persist();

        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setStatus(OrderStatusEn.CREATED);
        order.setIdempotencyKey(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("100.00"));

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(variant);
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("50.00"));
        order.getItems().add(item);
        order.persist();
        em.flush();

        BigDecimal totalBefore = order.getTotalAmount();

        OrderCheckoutResponseDto response = orderService.replayOrder(order);
        em.flush();
        em.clear();

        OrderEntity reloaded = OrderEntity.findById(order.getId());
        assertEquals(0, totalBefore.compareTo(reloaded.getTotalAmount()),
                "replayOrder must never write the persisted totalAmount, whatever transaction it runs inside");

        // The response itself must still be correct — a read that returns the wrong
        // number is just as broken as a read that writes.
        assertEquals(0, new BigDecimal("100.00").compareTo(response.getSubtotal()));
    }
}
