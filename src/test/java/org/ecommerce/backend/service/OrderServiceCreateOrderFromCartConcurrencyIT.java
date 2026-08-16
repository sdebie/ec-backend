package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Genuine two-thread concurrency test for {@code reserveStock()}'s atomic conditional
 * UPDATE. This cannot use {@link io.quarkus.test.TestTransaction} — that wraps the whole
 * test in one transaction, which is the opposite of what a race needs — so it follows
 * this codebase's tracked-ID + manual {@code @AfterEach} cleanup pattern instead (the
 * same one used by {@code GuestCheckoutPricingIT}).
 * <p>
 * {@link OrderServiceCreateOrderFromCartIT} tests 3-5 already give strong sequential
 * evidence that the atomic UPDATE is correctly wired (aggregation, real decrement,
 * durability across calls). This test adds the one thing sequential calls cannot prove:
 * that it is genuinely safe when two checkouts hit the same variant at the same time.
 */
@QuarkusTest
class OrderServiceCreateOrderFromCartConcurrencyIT
{
    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    private UUID productId;
    private UUID variantId;
    private final List<UUID> createdOrderIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @Transactional
    void seedVariantWithExactlyOneUnitInStock()
    {
        String marker = "ZZOFC-RACE-" + UUID.randomUUID().toString().substring(0, 8);

        ProductEntity product = new ProductEntity();
        product.setName(marker + "-product");
        product.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();
        productId = product.getId();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(1); // exactly one unit: at most one of two racing buyers can win
        variant.persist();
        variantId = variant.getId();
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : createdOrderIds) {
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
    }

    @Test
    @Timeout(30)
    @DisplayName("two real threads racing for the last unit: exactly one succeeds, the other is refused")
    void concurrentCheckouts_exactlyOneWinsTheLastUnit() throws Exception
    {
        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Boolean> buyOneUnit = () -> {
            ready.countDown();
            go.await();

            OrderCreationItemDto item = new OrderCreationItemDto();
            item.setVariantId(variantId.toString());
            item.setQuantity(1);
            OrderCreationRequestDto request = new OrderCreationRequestDto();
            request.setItems(List.of(item));

            try {
                // Each racing buyer is a genuinely different checkout intent, so
                // each gets its own key — this test is about reserveStock's atomic
                // UPDATE, not about idempotency, and a shared key would collide on
                // the unique index instead of racing for stock.
                OrderCheckoutResponseDto response = orderService.createOrderFromCart(
                        request, CustomerTypeEn.RETAILER, null, UUID.randomUUID(), null);
                createdOrderIds.add(UUID.fromString(response.getOrderId()));
                return true;
            } catch (UnavailableVariantsException e) {
                return false;
            }
        };

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(buyOneUnit));
        }

        // Release both threads together once both have reached the starting line,
        // to maximize (not guarantee — the atomic UPDATE is what actually guarantees
        // correctness, not this synchronization) the chance of a genuine race.
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        int succeeded = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(15, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertEquals(1, succeeded, "exactly one of two concurrent buyers must win the last unit");

        Integer finalStock = QuarkusTransaction.requiringNew().call(() -> {
            ProductVariantEntity reloaded = ProductVariantEntity.findById(variantId);
            return reloaded.getStockQuantity();
        });
        assertEquals(0, finalStock);
    }
}
