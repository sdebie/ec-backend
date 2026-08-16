package org.ecommerce.backend.api.rest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sequential (non-concurrent) idempotent-replay tests against the real REST endpoint.
 * <p>
 * <b>Shared-database discipline (KNOWN-LIMITATIONS §5):</b> HTTP requests run on their
 * own thread in their own transaction, so {@code @TestTransaction} cannot roll them
 * back. Fixtures are tracked by id and cleaned up explicitly.
 */
@QuarkusTest
class OrderResourceIdempotencyReplayIT
{
    @Inject
    EntityManager em;

    private final List<UUID> createdProductIds = new ArrayList<>();
    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdOrderIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : createdOrderIds) {
            em.createQuery("delete from OrderStatusHistoryEntity h where h.order.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        for (UUID variantId : createdVariantIds) {
            em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        }
        for (UUID productId : createdProductIds) {
            em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
        }
    }

    @Transactional
    UUID seedVariant(int stock, ProductStatusEn status)
    {
        String marker = "IDEMP-REPLAY-" + UUID.randomUUID().toString().substring(0, 8);

        ProductEntity product = new ProductEntity();
        product.setName(marker + "-product");
        product.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();
        createdProductIds.add(product.getId());

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(status);
        variant.setStockQuantity(stock);
        variant.persist();
        createdVariantIds.add(variant.getId());
        return variant.getId();
    }

    private String orderBody(UUID variantId, int quantity)
    {
        return """
                {"items":[{"variantId":"%s","quantity":%d}]}
                """.formatted(variantId, quantity);
    }

    private io.restassured.response.Response checkout(UUID key, String body)
    {
        io.restassured.response.Response response = given()
                .contentType("application/json")
                .header("Idempotency-Key", key.toString())
                .body(body)
        .when()
                .post("/api/orders");

        // Track every order this call creates, not just the ones each test expects
        // to matter. While the mechanism is unbuilt, a "retry" is indistinguishable
        // from a fresh checkout and creates a real order every time; once replay
        // works, a repeat call returns the SAME orderId, so this stays correct (and
        // a no-op re-delete) in both states.
        if (response.statusCode() == 201) {
            createdOrderIds.add(UUID.fromString(response.jsonPath().getString("orderId")));
        }
        return response;
    }

    @Test
    @DisplayName("4.1a: same key resubmitted after the last unit was already sold to it — replays 201, never 422")
    void sequentialRetry_afterOwnPurchaseExhaustedStock_replaysInsteadOfUnavailable()
    {
        UUID variantId = seedVariant(1, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        io.restassured.response.Response first = checkout(key, body);
        first.then().statusCode(201);
        String orderId = first.jsonPath().getString("orderId");

        // Same key, same cart, resubmitted after the variant is already at 0 stock.
        // Without the fast-path lookup (§3.1), this re-validates against the
        // now-exhausted stock and dies as a 422 — the shopper who DID place an
        // order is told their item is unavailable.
        io.restassured.response.Response retry = checkout(key, body);
        retry.then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId));
    }

    @Test
    @DisplayName("4.1a sibling: same key resubmitted after the variant was deactivated — still replays")
    void sequentialRetry_afterVariantDeactivated_stillReplays()
    {
        UUID variantId = seedVariant(10, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        io.restassured.response.Response first = checkout(key, body);
        first.then().statusCode(201);
        String orderId = first.jsonPath().getString("orderId");

        deactivateVariant(variantId);

        io.restassured.response.Response retry = checkout(key, body);
        retry.then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId));
    }

    @Transactional
    void deactivateVariant(UUID variantId)
    {
        ProductVariantEntity variant = ProductVariantEntity.findById(variantId);
        variant.setStatus(ProductStatusEn.DISABLED);
    }

    @Test
    @DisplayName("5.1: a replay returns the same orderId/sessionId/priced line fields/subtotal as the original")
    void replay_returnsSameResponseAsOriginal()
    {
        UUID variantId = seedVariant(10, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 2);

        io.restassured.response.Response first = checkout(key, body);
        first.then().statusCode(201);
        String orderId = first.jsonPath().getString("orderId");

        io.restassured.response.Response replay = checkout(key, body);
        replay.then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId))
                .body("sessionId", equalTo(first.jsonPath().getString("sessionId")))
                .body("subtotal", equalTo(first.jsonPath().getFloat("subtotal")))
                .body("lines[0].variantId", equalTo(first.jsonPath().getString("lines[0].variantId")))
                .body("lines[0].quantity", equalTo(first.jsonPath().getInt("lines[0].quantity")))
                .body("lines[0].unitPrice", equalTo(first.jsonPath().getFloat("lines[0].unitPrice")))
                .body("lines[0].lineTotal", equalTo(first.jsonPath().getFloat("lines[0].lineTotal")));
    }

    @Test
    @DisplayName("5.1: a replay does not decrement stock a second time")
    void replay_doesNotDecrementStockAgain()
    {
        UUID variantId = seedVariant(10, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 2);

        checkout(key, body).then().statusCode(201);
        Integer stockAfterFirst = currentStock(variantId);

        checkout(key, body).then().statusCode(201).header("Idempotent-Replayed", "true");
        Integer stockAfterReplay = currentStock(variantId);

        assertEquals(stockAfterFirst, stockAfterReplay, "a replay must not touch stock");
        assertEquals(8, stockAfterReplay, "exactly one decrement of 2 from 10");
    }

    @Test
    @DisplayName("5.1: a replay adds no status-history row")
    void replay_addsNoStatusHistoryRow()
    {
        UUID variantId = seedVariant(10, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body).jsonPath().getString("orderId");
        long historyRowsAfterCreate = statusHistoryRowCount(orderId);

        checkout(key, body).then().statusCode(201).header("Idempotent-Replayed", "true");
        long historyRowsAfterReplay = statusHistoryRowCount(orderId);

        assertEquals(historyRowsAfterCreate, historyRowsAfterReplay, "a replay must not write a status-history row");
    }

    @Test
    @DisplayName("5.2: a replay does not write the order's persisted totalAmount")
    void replay_doesNotWritePersistedTotal()
    {
        UUID variantId = seedVariant(10, ProductStatusEn.ACTIVE);
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body).jsonPath().getString("orderId");
        BigDecimal totalBefore = persistedTotalAmount(orderId);

        checkout(key, body).then().statusCode(201).header("Idempotent-Replayed", "true");
        BigDecimal totalAfter = persistedTotalAmount(orderId);

        // Not repriceOrder(), which would write totalAmount — see design §4. This
        // is the one assertion in this file that a response-shape check alone
        // cannot catch, since repriceOrder's response looks identical.
        assertEquals(0, totalBefore.compareTo(totalAfter), "replay must not write the persisted totalAmount");
    }

    private Integer currentStock(UUID variantId)
    {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductVariantEntity reloaded = ProductVariantEntity.findById(variantId);
            return reloaded.getStockQuantity();
        });
    }

    private long statusHistoryRowCount(String orderId)
    {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(h) from OrderStatusHistoryEntity h where h.order.id = :id", Long.class)
                        .setParameter("id", UUID.fromString(orderId))
                        .getSingleResult());
    }

    private BigDecimal persistedTotalAmount(String orderId)
    {
        return QuarkusTransaction.requiringNew().call(() -> {
            OrderEntity order = OrderEntity.findById(UUID.fromString(orderId));
            return order.getTotalAmount();
        });
    }
}
