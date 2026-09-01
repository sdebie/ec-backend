package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * DB-backed proof that {@link OrderRepository}'s batch-load hydration of {@code OrderEntity
 * .items} and {@code ProductVariantEntity.images} — both mapped {@code cascade = ALL,
 * orphanRemoval = true} — never issues an accidental delete, including the exact scenario
 * that broke a naive implementation during development: the same managed order being
 * re-fetched more than once within one persistence context (e.g. a caller that persists an
 * order and then calls a service method which re-loads it before the transaction commits).
 * <p>
 * A first attempt at this hydration assigned a brand new {@code List} to the collection
 * field on every call ({@code order.setItems(freshList)}). That is unsafe specifically
 * because {@code items} and {@code images} carry {@code orphanRemoval = true}: Hibernate
 * tracks such a collection by instance identity, and replacing the field's value orphans
 * the previously-tracked instance, which Hibernate refuses to flush ("A collection with
 * orphan deletion was no longer referenced by the owning entity instance") — a real failure
 * this test suite caught in {@code OrderServiceCancellationStockTest} et al. The fix mutates
 * each collection in place ({@code clear()} + {@code addAll()}); these tests pin that fix.
 */
@QuarkusTest
class OrderRepositoryCollectionMutationSafetyTest
{
    @Inject
    OrderRepository orderRepository;

    @Inject
    EntityManager em;

    private ProductVariantEntity newVariantWithImages(String marker, int imageCount)
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
        variant.setStockQuantity(10);
        variant.persist();

        for (int i = 0; i < imageCount; i++) {
            ProductImageEntity image = new ProductImageEntity();
            image.setProductVariant(variant);
            image.setImageUrl(marker + "-image-" + i + ".jpg");
            image.setSortOrder(i);
            image.setIsFeatured(i == 0);
            image.persist();
        }

        return variant;
    }

    private OrderEntity newOrderWithItems(ProductVariantEntity variant, int itemCount)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.persist();

        for (int i = 0; i < itemCount; i++) {
            OrderItemEntity item = new OrderItemEntity();
            item.setOrderEntity(order);
            item.setVariant(variant);
            item.setQuantity(1);
            item.setUnitPrice(BigDecimal.ONE);
            item.persist();
            order.getItems().add(item);
        }

        return order;
    }

    private long countOrderItems(UUID orderId)
    {
        return em.createQuery("select count(i) from OrderItemEntity i where i.orderEntity.id = :orderId", Long.class)
                .setParameter("orderId", orderId)
                .getSingleResult();
    }

    private long countVariantImages(UUID variantId)
    {
        return em.createQuery("select count(img) from ProductImageEntity img where img.productVariant.id = :variantId", Long.class)
                .setParameter("variantId", variantId)
                .getSingleResult();
    }

    @Test
    @TestTransaction
    void reFetchingTheSameOrderTwiceInOneTransactionDoesNotThrowOrDeleteItems()
    {
        String marker = "ZZORSAFE-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariantWithImages(marker, 1);
        OrderEntity order = newOrderWithItems(variant, 2);
        em.flush();

        // The exact shape that broke a naive setItems(...) implementation: the same
        // managed order fetched more than once before the transaction commits.
        assertDoesNotThrow(() -> orderRepository.findOrderInfoById(order.getId()));
        OrderEntity secondFetch = assertDoesNotThrow(() -> orderRepository.findOrderInfoById(order.getId()));

        assertEquals(2, secondFetch.getItems().size(), "both items must still be attached after two fetches");

        em.flush();
        assertEquals(2, countOrderItems(order.getId()), "no order_items row may be deleted by re-fetching the order");
    }

    @Test
    @TestTransaction
    void reFetchingTheSameOrderTwiceInOneTransactionDoesNotDeleteVariantImages()
    {
        String marker = "ZZORSAFE-IMG-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariantWithImages(marker, 3);
        OrderEntity order = newOrderWithItems(variant, 1);
        em.flush();

        orderRepository.findOrderInfoById(order.getId());
        OrderEntity secondFetch = assertDoesNotThrow(() -> orderRepository.findOrderInfoById(order.getId()));

        ProductVariantEntity hydratedVariant = secondFetch.getItems().get(0).getVariant();
        assertEquals(3, hydratedVariant.getImages().size(), "all three images must still be attached after two fetches");

        em.flush();
        assertEquals(3, countVariantImages(variant.getId()), "no product_images row may be deleted by re-fetching the order");
    }

    @Test
    @TestTransaction
    void findForAdminBatchHydrationAcrossMultipleOrdersDoesNotDeleteItems()
    {
        String marker = "ZZORSAFE-BULK-" + UUID.randomUUID().toString().substring(0, 8);
        ProductVariantEntity variant = newVariantWithImages(marker, 1);
        OrderEntity orderA = newOrderWithItems(variant, 2);
        OrderEntity orderB = newOrderWithItems(variant, 1);
        em.flush();

        List<OrderEntity> page = orderRepository.findForAdmin(
                List.of(OrderStatusEn.CREATED), null, null, null, null);
        List<OrderEntity> pageAgain = assertDoesNotThrow(() -> orderRepository.findForAdmin(
                List.of(OrderStatusEn.CREATED), null, null, null, null));

        assertEquals(page.size(), pageAgain.size(), "a second admin-page fetch must return the same number of orders");

        em.flush();
        assertEquals(2, countOrderItems(orderA.getId()), "orderA's items must be untouched by batch hydration");
        assertEquals(1, countOrderItems(orderB.getId()), "orderB's items must be untouched by batch hydration");
    }
}
