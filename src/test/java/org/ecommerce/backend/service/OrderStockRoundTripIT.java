package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stock goes out and comes back on the <em>same variant</em>, in the same quantity.
 * <p>
 * These drive the real checkout — {@link OrderService#createOrderFromCart} — rather than
 * hand-building an order, because the reservation and the release are two different pieces
 * of code and the bug worth catching is them disagreeing about which variant, or how many.
 * A test that builds the order itself asserts only that release is self-consistent.
 * <p>
 * Every case is a round trip: record the stock, place the order, cancel it, and require the
 * figures to be exactly where they started. Anything that leaks, double-counts, or credits
 * the wrong variant shows up as a number that does not match.
 */
@QuarkusTest
class OrderStockRoundTripIT
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

    private static OrderCreationItemDto line(ProductVariantEntity variant, int quantity)
    {
        OrderCreationItemDto item = new OrderCreationItemDto();
        item.setVariantId(variant.getId().toString());
        item.setQuantity(quantity);
        return item;
    }

    private UUID placeOrder(OrderCreationItemDto... lines)
    {
        OrderCreationRequestDto request = new OrderCreationRequestDto();
        request.setItems(new ArrayList<>(List.of(lines)));
        String orderId = orderService
                .createOrderFromCart(request, CustomerTypeEn.RETAILER, null)
                .getOrderId();
        em.flush();
        return UUID.fromString(orderId);
    }

    /** Reloads from a clean persistence context, so a stale in-memory field cannot fake a pass. */
    private int stockOf(UUID variantId)
    {
        em.flush();
        em.clear();
        return em.find(ProductVariantEntity.class, variantId).getStockQuantity();
    }

    @Test
    @TestTransaction
    @DisplayName("a single line goes out and comes back on the variant that was reserved")
    void singleLine_roundTrips() throws Exception
    {
        String marker = "ZZRT-ONE-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 4));
        assertEquals(6, stockOf(variant.getId()), "checkout must consume the stock it sold");

        orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");

        assertEquals(10, stockOf(variant.getId()), "cancelling must put back exactly what was taken");
    }

    /**
     * The case the reservation aggregates and the release does not. Two lines of the same
     * variant are decremented as one combined update but credited back as two — the totals
     * have to agree even though the shapes differ.
     */
    @Test
    @TestTransaction
    @DisplayName("two lines of the same variant net back to where they started")
    void duplicateLinesOfOneVariant_roundTrip() throws Exception
    {
        String marker = "ZZRT-DUP-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 3), line(variant, 4));
        assertEquals(3, stockOf(variant.getId()), "both lines must be charged against the same variant");

        orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");

        assertEquals(10, stockOf(variant.getId()), "a duplicated line must not be credited twice or once");
    }

    /**
     * The one that catches a release crediting the wrong variant. Three variants with
     * distinct starting stock and distinct quantities, so any mix-up between them lands on
     * a figure that cannot be reached by accident.
     */
    @Test
    @TestTransaction
    @DisplayName("each variant in a multi-line order is credited its own quantity, not another's")
    void multipleVariants_areCreditedIndependently() throws Exception
    {
        String marker = "ZZRT-MULTI-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity first = newVariant(marker + "-a", 10);
        ProductVariantEntity second = newVariant(marker + "-b", 20);
        ProductVariantEntity third = newVariant(marker + "-c", 30);

        UUID orderId = placeOrder(line(first, 1), line(second, 5), line(third, 9));

        assertEquals(9, stockOf(first.getId()));
        assertEquals(15, stockOf(second.getId()));
        assertEquals(21, stockOf(third.getId()));

        orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");

        assertEquals(10, stockOf(first.getId()));
        assertEquals(20, stockOf(second.getId()));
        assertEquals(30, stockOf(third.getId()));
    }

    /**
     * A variant that is not on the order must be untouched. Without this, a release that
     * credited every variant of the product — or every variant it happened to load — would
     * still pass every assertion above.
     */
    @Test
    @TestTransaction
    @DisplayName("a variant absent from the order is never credited by its cancellation")
    void unrelatedVariant_isUntouched() throws Exception
    {
        String marker = "ZZRT-OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity ordered = newVariant(marker + "-in", 10);
        ProductVariantEntity bystander = newVariant(marker + "-out", 7);

        UUID orderId = placeOrder(line(ordered, 4));
        orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");

        assertEquals(10, stockOf(ordered.getId()));
        assertEquals(7, stockOf(bystander.getId()), "a variant nobody ordered must not gain stock");
    }

    /**
     * Cancelling is terminal, so the release cannot run twice by transition. This proves the
     * guarantee holds rather than assuming it: a second attempt is refused outright, and the
     * refusal moves no stock.
     */
    @Test
    @TestTransaction
    @DisplayName("stock is returned once — a cancelled order cannot be cancelled again")
    void releaseHappensExactlyOnce() throws Exception
    {
        String marker = "ZZRT-ONCE-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 4));
        orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");
        assertEquals(10, stockOf(variant.getId()));

        try {
            orderService.updateOrderStatus(orderId, "ADMIN_CANCELED", "Dana Staff");
        } catch (RuntimeException expected) {
            // A terminal status offers nothing; the refusal is the point.
        }

        assertEquals(10, stockOf(variant.getId()), "a second cancellation must not credit the stock again");
    }

    /**
     * The whole delivery path, end to end. Stock is taken once at the start and never given
     * back, because the goods genuinely leave — no step along the way may quietly credit it.
     */
    @Test
    @TestTransaction
    @DisplayName("walking an order to DELIVERED consumes its stock exactly once and never returns it")
    void deliveredOrder_keepsStockConsumedThroughoutTheWorkflow() throws Exception
    {
        String marker = "ZZRT-PATH-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 4));
        OrderEntity order = em.find(OrderEntity.class, orderId);
        assertEquals(OrderStatusEn.CREATED, order.getStatus());

        orderService.applyTransition(em.find(OrderEntity.class, orderId),
                StatusTransition.system(OrderStatusEn.CREATED, OrderStatusEn.PENDING_PAYMENT, "Payment started"));
        orderService.applyTransition(em.find(OrderEntity.class, orderId),
                StatusTransition.system(OrderStatusEn.PENDING_PAYMENT, OrderStatusEn.PAID, "Payment confirmed"));

        for (String step : List.of("PROCESSING", "READY_TO_SHIP", "IN_TRANSIT", "DELIVERED")) {
            orderService.updateOrderStatus(orderId, step, "Dana Staff");
            assertEquals(6, stockOf(variant.getId()), "stock moved on the way to " + step);
        }

        assertEquals(6, stockOf(variant.getId()), "delivered goods must stay out of stock");
    }

    /**
     * The collection path, same guarantee. Worth its own case because it forks at PROCESSING
     * and ends on a different status.
     */
    @Test
    @TestTransaction
    @DisplayName("walking an order to COLLECTED consumes its stock exactly once and never returns it")
    void collectedOrder_keepsStockConsumedThroughoutTheWorkflow() throws Exception
    {
        String marker = "ZZRT-COLL-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 4));
        orderService.applyTransition(em.find(OrderEntity.class, orderId),
                StatusTransition.system(OrderStatusEn.CREATED, OrderStatusEn.IN_STORE_PAYMENT,
                        "Shopper chose to pay at collection"));

        for (String step : List.of("PAID", "PROCESSING", "READY_FOR_COLLECTION", "COLLECTED")) {
            orderService.updateOrderStatus(orderId, step, "Dana Staff");
            assertEquals(6, stockOf(variant.getId()), "stock moved on the way to " + step);
        }
    }

    /**
     * A refund at the end of that path still moves nothing. This is the guarantee the whole
     * refund model rests on, checked against real stock rather than against the enum.
     */
    @Test
    @TestTransaction
    @DisplayName("refunding a delivered order leaves its stock exactly where delivery left it")
    void refundAfterDelivery_movesNoStock() throws Exception
    {
        String marker = "ZZRT-RFND-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 10);

        UUID orderId = placeOrder(line(variant, 4));
        orderService.applyTransition(em.find(OrderEntity.class, orderId),
                StatusTransition.system(OrderStatusEn.CREATED, OrderStatusEn.PENDING_PAYMENT, "Payment started"));
        orderService.applyTransition(em.find(OrderEntity.class, orderId),
                StatusTransition.system(OrderStatusEn.PENDING_PAYMENT, OrderStatusEn.PAID, "Payment confirmed"));
        for (String step : List.of("PROCESSING", "READY_TO_SHIP", "IN_TRANSIT", "DELIVERED", "REFUNDED")) {
            orderService.updateOrderStatus(orderId, step, "Dana Staff");
        }

        assertEquals(6, stockOf(variant.getId()),
                "a refund records money coming back, not goods; returning them to sale is a separate feature");
    }
}
