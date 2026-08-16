package org.ecommerce.backend.api.rest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Genuine two-thread races for {@code POST /api/orders} carrying the same
 * {@code Idempotency-Key} — the case design §3.2/§3.3/§3.5 exist for.
 * <p>
 * Drives the real REST endpoint via RestAssured rather than injecting
 * {@code OrderService} directly (the pattern {@code
 * OrderServiceCreateOrderFromCartConcurrencyIT} uses for {@code reserveStock}
 * alone): the claim, the transaction-boundary fix and the resolveExisting
 * dispatch all live in {@code OrderResource.createOrder}, so a test that
 * bypasses it cannot observe a {@code 500} from a poisoned transaction, a
 * {@code 201} replay, or the {@code Idempotent-Replayed} header — it can only
 * ever see what {@code createOrderFromCart} itself returns or throws.
 * <p>
 * <b>Shared-database discipline (KNOWN-LIMITATIONS §5):</b> HTTP requests run on
 * their own threads in their own transactions, so {@code @TestTransaction}
 * cannot roll them back. Fixtures are tracked by id and cleaned up explicitly.
 */
@QuarkusTest
class OrderResourceIdempotencyConcurrencyIT
{
    @Inject
    EntityManager em;

    private UUID productId;
    private UUID variantId;
    private final List<UUID> createdOrderIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : createdOrderIds) {
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        if (variantId != null) {
            em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        }
        if (productId != null) {
            em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
        }
    }

    @Transactional
    void seedVariant(int stock)
    {
        String marker = "IDEMP-RACE-" + UUID.randomUUID().toString().substring(0, 8);

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
        variant.setStockQuantity(stock);
        variant.persist();
        variantId = variant.getId();
    }

    private String orderBody(int quantity)
    {
        return """
                {"items":[{"variantId":"%s","quantity":%d}]}
                """.formatted(variantId, quantity);
    }

    /** Fires two identical, same-keyed POSTs at the same instant; returns both raw responses. */
    private List<io.restassured.response.Response> raceTwoIdenticalRequests(UUID key, int quantity) throws Exception
    {
        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        Callable<io.restassured.response.Response> post = () -> {
            ready.countDown();
            go.await();
            return given()
                    .contentType("application/json")
                    .header("Idempotency-Key", key.toString())
                    .body(orderBody(quantity))
            .when()
                    .post("/api/orders");
        };

        List<Future<io.restassured.response.Response>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(post));
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        List<io.restassured.response.Response> responses = new ArrayList<>();
        for (Future<io.restassured.response.Response> f : futures) {
            responses.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return responses;
    }

    @Test
    @Timeout(30)
    @DisplayName("4.1: two threads, same key, ample stock — exactly one order, the other replays it, stock decremented once")
    void sameKeyRace_ampleStock_oneOrderOneDecrement() throws Exception
    {
        seedVariant(100);
        UUID key = UUID.randomUUID();

        List<io.restassured.response.Response> responses = raceTwoIdenticalRequests(key, 3);

        // Neither request may surface a database constraint error to the caller
        // (Requirement 1.4) — both must be 201s, whichever is the winner and
        // whichever is the replay.
        for (io.restassured.response.Response r : responses) {
            assertEquals(201, r.statusCode(), "both requests must succeed — one create, one replay: " + r.asString());
        }

        String orderIdA = responses.get(0).jsonPath().getString("orderId");
        String orderIdB = responses.get(1).jsonPath().getString("orderId");
        // Track both ids regardless of the outcome: while the mechanism is unbuilt,
        // a race can still create two distinct orders, and cleanup must reach both
        // or a leftover row's foreign key blocks the variant delete.
        createdOrderIds.add(UUID.fromString(orderIdA));
        createdOrderIds.add(UUID.fromString(orderIdB));
        assertEquals(orderIdA, orderIdB, "both responses must name the same order");

        // Exactly one of the two responses is the replay, carrying the header;
        // the other is the original creation and must not carry it.
        long replayedCount = responses.stream()
                .filter(r -> "true".equals(r.getHeader("Idempotent-Replayed")))
                .count();
        assertEquals(1, replayedCount, "exactly one of the two responses must be the replay");

        assertOnlyOneOrderExistsForKey(key);

        Integer finalStock = QuarkusTransaction.requiringNew().call(() -> {
            ProductVariantEntity reloaded = ProductVariantEntity.findById(variantId);
            return reloaded.getStockQuantity();
        });
        // Assert the stock FIGURE, not the row count — the count would pass
        // against a bug that reserved twice for one order.
        assertEquals(97, finalStock, "stock must be decremented exactly once (100 - 3), not twice");
    }

    @Test
    @Timeout(30)
    @DisplayName("4.1b: two threads, same key, exactly one unit in stock — the loser replays 201, never 422")
    void sameKeyRace_lastUnit_loserReplaysInsteadOfUnavailable() throws Exception
    {
        seedVariant(1);
        UUID key = UUID.randomUUID();

        List<io.restassured.response.Response> responses = raceTwoIdenticalRequests(key, 1);

        // Without the design §3.5 re-check, the loser here dies inside
        // reserveStock with a 422 — before the unique index is ever consulted,
        // because persist() is never reached. That is this feature's headline
        // failure mode: the shopper who DID place an order is told their item
        // is unavailable.
        for (io.restassured.response.Response r : responses) {
            assertEquals(201, r.statusCode(),
                    "both requests must succeed with the last unit — a 422 here means the concurrent "
                            + "stock-exhaustion race is unprotected: " + r.asString());
        }

        String orderIdA = responses.get(0).jsonPath().getString("orderId");
        String orderIdB = responses.get(1).jsonPath().getString("orderId");
        assertEquals(orderIdA, orderIdB);
        createdOrderIds.add(UUID.fromString(orderIdA));

        assertOnlyOneOrderExistsForKey(key);

        Integer finalStock = QuarkusTransaction.requiringNew().call(() -> {
            ProductVariantEntity reloaded = ProductVariantEntity.findById(variantId);
            return reloaded.getStockQuantity();
        });
        assertEquals(0, finalStock, "the single unit must be decremented exactly once, not twice and not zero times");
    }

    private void assertOnlyOneOrderExistsForKey(UUID key)
    {
        Long count = QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(o) from OrderEntity o where o.idempotencyKey = :key", Long.class)
                        .setParameter("key", key)
                        .getSingleResult());
        assertEquals(1L, count, "exactly one order row may exist for a given idempotency key");
    }
}
