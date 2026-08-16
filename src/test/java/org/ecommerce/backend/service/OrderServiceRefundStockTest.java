package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A refund never moves stock.
 * <p>
 * Reversing a payment is bookkeeping. Whether the goods came back, and whether they are
 * fit to sell again, is a physical fact the server does not have — so it does not guess,
 * and it does not offer anyone a way to tell it either. Returning goods to sale is the
 * returns feature; until then a refund records money and nothing else.
 * <p>
 * Every case asserts a stock <b>figure</b>: the status change is correct either way, so
 * asserting on status alone would pass against the opposite behaviour.
 */
@QuarkusTest
class OrderServiceRefundStockTest
{
    @Inject
    OrderService orderService;

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

    /** Reloads from a clean persistence context, so a stale in-memory field cannot fake a pass. */
    private int stockOf(UUID variantId)
    {
        em.flush();
        em.clear();
        return em.find(ProductVariantEntity.class, variantId).getStockQuantity();
    }

    /**
     * Both refund kinds, from every status either is reachable from. RETURNED_TO_ORIGIN is
     * the case worth watching: the goods really are physically back at the shop, and it is
     * still not this transition's job to put them back on sale.
     */
    @ParameterizedTest(name = "{0} → {1} leaves stock alone")
    @CsvSource({
            "DELIVERED,          REFUNDED",
            "DELIVERED,          PARTIALLY_REFUNDED",
            "COLLECTED,          REFUNDED",
            "COLLECTED,          PARTIALLY_REFUNDED",
            "RETURNED_TO_ORIGIN, REFUNDED",
            "RETURNED_TO_ORIGIN, PARTIALLY_REFUNDED",
            "PARTIALLY_REFUNDED, REFUNDED",
    })
    @TestTransaction
    @DisplayName("no refund transition returns stock, from any status it is reachable from")
    void refund_neverMovesStock(String from, String to) throws GraphQLException
    {
        String marker = "ZZRFND-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.valueOf(from), variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), to, "Dana Staff");

        assertEquals(5, stockOf(variant.getId()),
                from + " → " + to + " moved stock; a refund records money, not goods");
    }

    /**
     * An order still owing money has nothing to refund. Cancelling is the correct exit,
     * and that one does return the stock.
     */
    @ParameterizedTest(name = "{0} cannot be refunded")
    @CsvSource({"CREATED", "PENDING_PAYMENT", "IN_STORE_PAYMENT", "PAYMENT_FAILED", "PAID", "PROCESSING"})
    @TestTransaction
    @DisplayName("an unpaid or undelivered order cannot be refunded at all")
    void refundBeforeDelivery_isRejected(String from)
    {
        String marker = "ZZRFND-NO-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.valueOf(from), variant, 3);
        em.flush();

        assertThrows(IllegalArgumentException.class,
                () -> orderService.updateOrderStatus(order.getId(), "REFUNDED", "Dana Staff"));

        assertEquals(5, stockOf(variant.getId()), "a refused transition must move no stock");
    }
}
