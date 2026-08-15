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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-backed tests for the stock side of {@link OrderService#updateOrderStatus}.
 * <p>
 * Stock is consumed at CREATED, before payment, and the abandoned-order sweep — the only
 * other restorer — is hard-filtered to {@code status = CREATED}, so an order a staff member
 * cancels is structurally invisible to it forever. These tests pin that a staff cancellation
 * gives the stock back itself, from every status CANCELLED is reachable from, and that no
 * other transition does.
 * <p>
 * Every case asserts a stock figure, not just a status: the defect these cover was a status
 * change that was itself entirely correct.
 */
@QuarkusTest
class OrderServiceCancellationStockTest
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

    @Test
    @TestTransaction
    @DisplayName("cancelling a CREATED order restores its stock — the sweep never sees such an order")
    void cancelCreatedOrder_restoresStock() throws GraphQLException
    {
        String marker = "ZZCSTK-CREATED-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.CREATED, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "CANCELLED", "Dana Staff", null);

        assertEquals(8, stockOf(variant.getId()), "5 in stock + 3 returned by the cancellation");
    }

    @Test
    @TestTransaction
    @DisplayName("cancelling a PAID order restores its stock — the goods were never dispatched")
    void cancelPaidOrder_restoresStock() throws GraphQLException
    {
        String marker = "ZZCSTK-PAID-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.PAID, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "CANCELLED", "Dana Staff", null);

        assertEquals(8, stockOf(variant.getId()), "5 in stock + 3 returned by the cancellation");
    }

    @Test
    @TestTransaction
    @DisplayName("cancelling an IN_STORE_PAYMENT order restores its stock")
    void cancelInStorePaymentOrder_restoresStock() throws GraphQLException
    {
        String marker = "ZZCSTK-ISP-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.IN_STORE_PAYMENT, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "CANCELLED", "Dana Staff", null);

        assertEquals(8, stockOf(variant.getId()), "5 in stock + 3 returned by the cancellation");
    }

    @Test
    @TestTransaction
    @DisplayName("every line is returned when an order holds more than one variant")
    void cancelMultiLineOrder_restoresEveryLine() throws GraphQLException
    {
        String marker = "ZZCSTK-MULTI-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity first = newVariant(marker + "-a", 5);
        ProductVariantEntity second = newVariant(marker + "-b", 10);

        OrderEntity order = newOrder(OrderStatusEn.CREATED, first, 3);
        OrderItemEntity secondLine = new OrderItemEntity();
        secondLine.setOrderEntity(order);
        secondLine.setVariant(second);
        secondLine.setQuantity(4);
        secondLine.setUnitPrice(BigDecimal.ZERO);
        secondLine.persist();
        order.getItems().add(secondLine);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "CANCELLED", "Dana Staff", null);

        em.flush();
        em.clear();
        assertEquals(8, em.find(ProductVariantEntity.class, first.getId()).getStockQuantity());
        assertEquals(14, em.find(ProductVariantEntity.class, second.getId()).getStockQuantity());
    }

    @Test
    @TestTransaction
    @DisplayName("marking a CREATED order payable in store keeps its stock consumed — the order still ships")
    void inStorePaymentTransition_leavesStockConsumed() throws GraphQLException
    {
        String marker = "ZZCSTK-TOISP-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.CREATED, variant, 3);
        em.flush();

        orderService.updateOrderStatus(order.getId(), "IN_STORE_PAYMENT", "Dana Staff", null);

        assertEquals(5, stockOf(variant.getId()), "a live order must keep holding its stock");
    }

    @Test
    @TestTransaction
    @DisplayName("a rejected transition restores nothing — stock only moves when the claim is won")
    void disallowedTransition_leavesStockUntouched()
    {
        String marker = "ZZCSTK-REJECT-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariant(marker, 5);
        OrderEntity order = newOrder(OrderStatusEn.DELIVERED, variant, 3);
        em.flush();

        assertThrows(GraphQLException.class,
                () -> orderService.updateOrderStatus(order.getId(), "CANCELLED", "Dana Staff", null));

        assertEquals(5, stockOf(variant.getId()));
    }
}
