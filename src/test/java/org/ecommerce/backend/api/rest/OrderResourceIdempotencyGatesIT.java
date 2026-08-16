package org.ecommerce.backend.api.rest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The four {@code resolveExisting} gates (design §6): ownership, voided,
 * window, fingerprint — plus their precedence when more than one fires
 * (Requirement 1.6), and the two {@code 400} cases ahead of all of them.
 * <p>
 * <b>Shared-database discipline (KNOWN-LIMITATIONS §5):</b> HTTP requests run on
 * their own thread in their own transaction, so fixtures are tracked by id and
 * cleaned up explicitly.
 */
@QuarkusTest
class OrderResourceIdempotencyGatesIT
{
    @Inject
    EntityManager em;

    private final List<UUID> createdProductIds = new ArrayList<>();
    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdOrderIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdCustomerIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : createdOrderIds) {
            em.createQuery("delete from OrderStatusHistoryEntity h where h.order.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
        }
        for (UUID customerId : createdCustomerIds) {
            em.createQuery("delete from CustomerEntity c where c.id = :id").setParameter("id", customerId).executeUpdate();
        }
        for (UUID userId : createdUserIds) {
            em.createQuery("delete from UserEntity u where u.id = :id").setParameter("id", userId).executeUpdate();
        }
        for (UUID variantId : createdVariantIds) {
            em.createQuery("delete from ProductVariantEntity v where v.id = :id").setParameter("id", variantId).executeUpdate();
        }
        for (UUID productId : createdProductIds) {
            em.createQuery("delete from ProductEntity p where p.id = :id").setParameter("id", productId).executeUpdate();
        }
    }

    @Transactional
    UUID seedVariant()
    {
        String marker = "IDEMP-GATE-" + UUID.randomUUID().toString().substring(0, 8);

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
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(50);
        variant.persist();
        createdVariantIds.add(variant.getId());
        return variant.getId();
    }

    @Transactional
    CustomerEntity seedCustomer(String email)
    {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash("irrelevant-test-hash");
        user.persist();
        createdUserIds.add(user.getId());

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName("Gate");
        customer.setLastName("Fixture");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.persist();
        createdCustomerIds.add(customer.getId());
        return customer;
    }

    private String orderBody(UUID variantId, int quantity)
    {
        return """
                {"items":[{"variantId":"%s","quantity":%d}]}
                """.formatted(variantId, quantity);
    }

    private io.restassured.response.Response checkout(UUID key, String body, String bearerJwt)
    {
        io.restassured.specification.RequestSpecification spec = given()
                .contentType("application/json")
                .header("Idempotency-Key", key.toString());
        if (bearerJwt != null) {
            spec = spec.header("Authorization", "Bearer " + bearerJwt);
        }
        io.restassured.response.Response response = spec.body(body).when().post("/api/orders");
        if (response.statusCode() == 201) {
            createdOrderIds.add(UUID.fromString(response.jsonPath().getString("orderId")));
        }
        return response;
    }

    private String jwtFor(String email)
    {
        return Jwt.subject(email).issuer("http://localhost:8080").groups("customer").sign();
    }

    @Transactional
    void setStatus(UUID orderId, OrderStatusEn status)
    {
        OrderEntity order = OrderEntity.findById(orderId);
        order.setStatus(status);
    }

    @Transactional
    void setCustomer(UUID orderId, CustomerEntity customer)
    {
        OrderEntity order = OrderEntity.findById(orderId);
        order.setCustomerEntity(customer);
    }

    void backdate(UUID orderId, LocalDateTime createdAt)
    {
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("update OrderEntity o set o.createdAt = :placedAt where o.id = :id")
                        .setParameter("placedAt", createdAt)
                        .setParameter("id", orderId)
                        .executeUpdate());
    }

    // ── 2.1/2.2: the two 400s ───────────────────────────────────────────────

    @Test
    @DisplayName("6.1: no Idempotency-Key header -> 400, no order created")
    void missingHeader_is400()
    {
        UUID variantId = seedVariant();
        long before = OrderEntity.count();

        given()
                .contentType("application/json")
                .body(orderBody(variantId, 1))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(400);

        assertEquals(before, orderCountNewTx(), "a rejected request must create no order");
    }

    @Test
    @DisplayName("6.1: a malformed Idempotency-Key (not a UUID) -> 400, no order created")
    void malformedHeader_is400()
    {
        UUID variantId = seedVariant();
        long before = OrderEntity.count();

        given()
                .contentType("application/json")
                .header("Idempotency-Key", "not-a-uuid")
                .body(orderBody(variantId, 1))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(400);

        assertEquals(before, orderCountNewTx(), "a rejected request must create no order");
    }

    private long orderCountNewTx()
    {
        return QuarkusTransaction.requiringNew().call(() -> OrderEntity.count());
    }

    // ── 3.1: fingerprint mismatch ────────────────────────────────────────────

    @Test
    @DisplayName("6.1: same key, different cart quantity -> 409 IDEMPOTENCY_CART_MISMATCH, original order untouched")
    void differingFingerprint_is409CartMismatch()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();

        io.restassured.response.Response first = checkout(key, orderBody(variantId, 1), null);
        first.then().statusCode(201);
        String orderId = first.jsonPath().getString("orderId");

        checkout(key, orderBody(variantId, 2), null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_CART_MISMATCH"));

        // The mismatch must not have replayed OR created a second order.
        assertEquals(1, ordersForKey(key));
        assertEquals(orderId, first.jsonPath().getString("orderId"));
    }

    private long ordersForKey(UUID key)
    {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(o) from OrderEntity o where o.idempotencyKey = :key", Long.class)
                        .setParameter("key", key)
                        .getSingleResult());
    }

    // ── 4.2: ownership — the three auth-state cases ─────────────────────────

    @Test
    @DisplayName("6.1: a different signed-in customer holding the key is refused (409 IDEMPOTENCY_WRONG_OWNER)")
    void differentSignedInCustomer_is409WrongOwner()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.example";
        String intruderEmail = "intruder-" + UUID.randomUUID() + "@test.example";
        CustomerEntity owner = seedCustomer(ownerEmail);
        seedCustomer(intruderEmail);

        String orderId = checkout(key, orderBody(variantId, 1), jwtFor(ownerEmail)).jsonPath().getString("orderId");
        setCustomer(UUID.fromString(orderId), owner);

        checkout(key, orderBody(variantId, 1), jwtFor(intruderEmail))
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_WRONG_OWNER"));
    }

    @Test
    @DisplayName("6.1: an anonymous requester holding the key is refused when the order has a customer (409 IDEMPOTENCY_WRONG_OWNER)")
    void anonymousRequester_isRefusedWhenOrderHasCustomer()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.example";
        CustomerEntity owner = seedCustomer(ownerEmail);

        String orderId = checkout(key, orderBody(variantId, 1), jwtFor(ownerEmail)).jsonPath().getString("orderId");
        setCustomer(UUID.fromString(orderId), owner);

        // No Authorization header at all — this is the case a requester-keyed
        // check (mayAccess) would wrongly let through.
        checkout(key, orderBody(variantId, 1), null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_WRONG_OWNER"));
    }

    @Test
    @DisplayName("6.1: a guest order still replays for an anonymous requester")
    void guestOrder_stillReplaysForAnonymousRequester()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body, null).jsonPath().getString("orderId");

        checkout(key, body, null)
                .then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId));
    }

    // ── 5.6: voided — the order's stock has already been returned ──────────

    @Test
    @DisplayName("5.6: a SYSTEM_CANCELED order matched by key -> 409 IDEMPOTENCY_ORDER_VOIDED")
    void systemCanceledOrder_is409OrderVoided()
    {
        assertVoidedRefusesReplay(OrderStatusEn.SYSTEM_CANCELED);
    }

    @Test
    @DisplayName("5.6: a USER_CANCELED order matched by key -> 409 IDEMPOTENCY_ORDER_VOIDED")
    void userCanceledOrder_is409OrderVoided()
    {
        assertVoidedRefusesReplay(OrderStatusEn.USER_CANCELED);
    }

    @Test
    @DisplayName("5.6: a FAILED order matched by key -> 409 IDEMPOTENCY_ORDER_VOIDED")
    void failedOrder_is409OrderVoided()
    {
        assertVoidedRefusesReplay(OrderStatusEn.FAILED);
    }

    private void assertVoidedRefusesReplay(OrderStatusEn voidedStatus)
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body, null).jsonPath().getString("orderId");
        setStatus(UUID.fromString(orderId), voidedStatus);

        checkout(key, body, null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_ORDER_VOIDED"));
    }

    @Test
    @DisplayName("5.6 negative case: a PAID order matched by key still replays — the gate is not over-broad")
    void paidOrder_stillReplays()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body, null).jsonPath().getString("orderId");
        setStatus(UUID.fromString(orderId), OrderStatusEn.PAID);

        checkout(key, body, null)
                .then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId));
    }

    // ── 5.3: the 24h replay window ───────────────────────────────────────────

    @Test
    @DisplayName("5.3: a key matching an order inside the window replays")
    void withinWindow_replays()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body, null).jsonPath().getString("orderId");
        backdate(UUID.fromString(orderId), LocalDateTime.now().minusHours(23).minusMinutes(59));

        checkout(key, body, null)
                .then()
                .statusCode(201)
                .header("Idempotent-Replayed", "true")
                .body("orderId", equalTo(orderId));
    }

    @Test
    @DisplayName("5.3: a key matching an order past the window -> 409 IDEMPOTENCY_KEY_EXPIRED, creates no order")
    void pastWindow_is409Expired()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        checkout(key, body, null).then().statusCode(201);
        String orderId = ordersForKeyIds(key).get(0);
        backdate(UUID.fromString(orderId), LocalDateTime.now().minusHours(24).minusMinutes(1));

        checkout(key, body, null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_KEY_EXPIRED"));

        assertEquals(1, ordersForKey(key), "an expired key must not create a second order");
    }

    private List<String> ordersForKeyIds(UUID key)
    {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select o.id from OrderEntity o where o.idempotencyKey = :key", UUID.class)
                        .setParameter("key", key)
                        .getResultList()
                        .stream().map(UUID::toString).toList());
    }

    // ── 1.6: precedence when more than one refusal fires ─────────────────────

    @Test
    @DisplayName("6.1b: expired AND wrong-owner -> reports wrong-owner, not expired")
    void expiredAndWrongOwner_reportsWrongOwner()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.example";
        CustomerEntity owner = seedCustomer(ownerEmail);

        String orderId = checkout(key, orderBody(variantId, 1), jwtFor(ownerEmail)).jsonPath().getString("orderId");
        setCustomer(UUID.fromString(orderId), owner);
        backdate(UUID.fromString(orderId), LocalDateTime.now().minusHours(25));

        // Anonymous requester: wrong owner AND (independently) expired.
        checkout(key, orderBody(variantId, 1), null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_WRONG_OWNER"));
    }

    @Test
    @DisplayName("6.1b: expired AND fingerprint-mismatch -> reports expired, not mismatch")
    void expiredAndMismatch_reportsExpired()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();

        String orderId = checkout(key, orderBody(variantId, 1), null).jsonPath().getString("orderId");
        backdate(UUID.fromString(orderId), LocalDateTime.now().minusHours(25));

        // Same key, a DIFFERENT cart, on an order that is also past its window.
        checkout(key, orderBody(variantId, 2), null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_KEY_EXPIRED"));
    }

    @Test
    @DisplayName("6.1b: voided AND wrong-owner -> reports wrong-owner, not voided")
    void voidedAndWrongOwner_reportsWrongOwner()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.example";
        CustomerEntity owner = seedCustomer(ownerEmail);

        String orderId = checkout(key, orderBody(variantId, 1), jwtFor(ownerEmail)).jsonPath().getString("orderId");
        setCustomer(UUID.fromString(orderId), owner);
        setStatus(UUID.fromString(orderId), OrderStatusEn.SYSTEM_CANCELED);

        checkout(key, orderBody(variantId, 1), null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_WRONG_OWNER"));
    }

    @Test
    @DisplayName("6.1b: voided AND expired -> reports voided, not expired")
    void voidedAndExpired_reportsVoided()
    {
        UUID variantId = seedVariant();
        UUID key = UUID.randomUUID();
        String body = orderBody(variantId, 1);

        String orderId = checkout(key, body, null).jsonPath().getString("orderId");
        setStatus(UUID.fromString(orderId), OrderStatusEn.SYSTEM_CANCELED);
        backdate(UUID.fromString(orderId), LocalDateTime.now().minusHours(25));

        checkout(key, body, null)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_ORDER_VOIDED"));
    }
}
