package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Proves the two halves of the pricing contract on the live checkout path:
 * <ol>
 *   <li>an anonymous guest can create an order at all — the capability that the
 *       deleted GraphQL {@code createOrder} mutation was mistakenly assumed to
 *       provide (see {@code OrderMutationAbsenceIT});</li>
 *   <li>the unit price on every persisted line is chosen by the server from the
 *       shopper's tier, and the request has no way to influence it — the request
 *       body carries nothing but {@code variantId} and {@code quantity}.</li>
 * </ol>
 * A guest is priced at the retail tier and a signed-in wholesale customer at the
 * wholesale tier, from the same request body, which is the whole rule in one pair
 * of assertions.
 * <p>
 * <b>Shared-database discipline (KNOWN-LIMITATIONS §5):</b> this suite runs against
 * the shared local {@code ecommerce_db}, and an HTTP request executes on another
 * thread in its own transaction, so {@code @TestTransaction} cannot roll it back.
 * Fixtures are therefore tracked by id and deleted in {@link #cleanup()}, and every
 * assertion is about rows this test created — never a table-wide count.
 */
@QuarkusTest
class GuestCheckoutPricingIT
{
    private static final BigDecimal RETAIL_PRICE = new BigDecimal("150.00");
    private static final BigDecimal WHOLESALE_PRICE = new BigDecimal("90.00");

    @Inject
    EntityManager em;

    private UUID productId;
    private UUID variantId;
    private final java.util.List<UUID> createdOrderIds = new java.util.ArrayList<>();

    @BeforeEach
    @Transactional
    void seedPricedVariant()
    {
        ProductEntity product = new ProductEntity();
        product.setName("Guest checkout fixture " + UUID.randomUUID());
        product.setSlug("guest-checkout-fixture-" + UUID.randomUUID());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();
        productId = product.getId();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku("GCF-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(100);
        variant.persist();
        variantId = variant.getId();

        persistPrice(variant, PriceTypeEn.RETAIL_PRICE, RETAIL_PRICE);
        persistPrice(variant, PriceTypeEn.WHOLESALE_PRICE, WHOLESALE_PRICE);
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID orderId : createdOrderIds) {
            em.createQuery("delete from OrderItemEntity oi where oi.orderEntity.id = :id")
                    .setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id")
                    .setParameter("id", orderId).executeUpdate();
        }
        createdOrderIds.clear();

        em.createQuery("delete from VariantPricesEntity vp where vp.variant.id = :id")
                .setParameter("id", variantId).executeUpdate();
        em.createQuery("delete from ProductVariantEntity v where v.id = :id")
                .setParameter("id", variantId).executeUpdate();
        em.createQuery("delete from ProductEntity p where p.id = :id")
                .setParameter("id", productId).executeUpdate();
    }

    private void persistPrice(ProductVariantEntity variant, PriceTypeEn type, BigDecimal amount)
    {
        VariantPricesEntity price = new VariantPricesEntity();
        price.setVariant(variant);
        price.setPriceType(type);
        price.setPrice(amount);
        price.persist();
    }

    private String orderBody(int quantity)
    {
        return """
                {"items":[{"variantId":"%s","quantity":%d}]}
                """.formatted(variantId, quantity);
    }

    private String wholesalerJwt()
    {
        return Jwt.subject("wholesale-buyer@test.com")
                .issuer("http://localhost:8080")
                .groups("customer")
                .claim("shopperType", "WHOLESALER")
                .sign();
    }

    private void trackOrder(String orderId)
    {
        createdOrderIds.add(UUID.fromString(orderId));
    }

    @Test
    @DisplayName("an anonymous guest can create an order, priced at the retail tier")
    void guestOrderIsCreatedAtRetailPrice()
    {
        String orderId = given()
                .contentType("application/json")
                .body(orderBody(2))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(201)
                .body("lines[0].unitPrice", comparesEqualTo(150.0f))
                .body("lines[0].lineTotal", comparesEqualTo(300.0f))
                .body("subtotal", comparesEqualTo(300.0f))
                .extract().path("orderId");

        trackOrder(orderId);
    }

    @Test
    @DisplayName("a signed-in wholesale customer is priced at the wholesale tier from the identical request")
    void wholesaleOrderIsCreatedAtWholesalePrice()
    {
        String orderId = given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + wholesalerJwt())
                .body(orderBody(2))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(201)
                .body("lines[0].unitPrice", comparesEqualTo(90.0f))
                .body("lines[0].lineTotal", comparesEqualTo(180.0f))
                .extract().path("orderId");

        trackOrder(orderId);
    }

    @Test
    @DisplayName("the persisted line price comes from the database, not from anything the client could send")
    void persistedUnitPriceIsTheServerSelectedTier()
    {
        // The request body has no price field at all, so the only way a wrong
        // figure could be stored is if the server stopped selecting one.
        String orderId = given()
                .contentType("application/json")
                .body(orderBody(1))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(201)
                .extract().path("orderId");

        trackOrder(orderId);

        BigDecimal storedUnitPrice = em.createQuery(
                        "select oi.unitPrice from OrderItemEntity oi where oi.orderEntity.id = :id",
                        BigDecimal.class)
                .setParameter("id", UUID.fromString(orderId))
                .getSingleResult();

        org.junit.jupiter.api.Assertions.assertEquals(0, RETAIL_PRICE.compareTo(storedUnitPrice),
                "guest line should persist the retail tier price, got " + storedUnitPrice);
    }

    @Test
    @DisplayName("the order is persisted and readable back by its own session id")
    void orderIsPersisted()
    {
        String orderId = given()
                .contentType("application/json")
                .body(orderBody(1))
        .when()
                .post("/api/orders")
        .then()
                .statusCode(201)
                .body("sessionId", not(emptyOrNullString()))
                .extract().path("orderId");

        trackOrder(orderId);

        OrderEntity persisted = em.find(OrderEntity.class, UUID.fromString(orderId));
        org.junit.jupiter.api.Assertions.assertNotNull(persisted,
                "guest checkout must persist an order row");
    }
}
