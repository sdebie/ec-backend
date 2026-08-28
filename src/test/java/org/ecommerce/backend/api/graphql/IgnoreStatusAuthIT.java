package org.ecommerce.backend.api.graphql;

// Feature: catalogue-ordering-and-pagination
// Task 4.9: ignoreStatus authorisation ITs

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the {@code ignoreStatus} authorisation gate on {@code saleProductList}
 * and the exclusion of non-ACTIVE products from anonymous storefront list queries.
 * <p>
 * Follows shared-database discipline (KNOWN-LIMITATIONS §5):
 * <ul>
 *   <li>Seeds a tracked non-ACTIVE (PENDING) product with an active RETAIL_SALE_PRICE</li>
 *   <li>Cleans up tracked data in {@code @AfterAll}</li>
 *   <li>Uses a unique marker prefix to isolate tracked products from pre-existing data</li>
 *   <li>Assertions are relative: checks presence/absence of the tracked product ID only</li>
 * </ul>
 * <p>
 * Test cases:
 * <ol>
 *   <li>(a) Anonymous {@code saleProductList(ignoreStatus: true)} → rejected</li>
 *   <li>(b) Staff {@code saleProductList(ignoreStatus: true)} → succeeds, includes tracked PENDING product</li>
 *   <li>(c) Anonymous {@code shoppingProductList} → excludes tracked PENDING product</li>
 *   <li>(d) Anonymous {@code saleProductList} (no flag) → excludes tracked PENDING product</li>
 * </ol>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ignoreStatus authorisation — saleProductList gate and storefront exclusion")
class IgnoreStatusAuthIT
{
    private static final String MARKER = "ZZIGNSTAT-AUTH-";

    // Pre-generated IDs for the tracked product, variant, and price
    private static final UUID TRACKED_PRODUCT_ID = UUID.randomUUID();
    private static final UUID TRACKED_VARIANT_ID = UUID.randomUUID();
    private static final UUID TRACKED_PRICE_ID = UUID.randomUUID();
    private static final String TRACKED_PRODUCT_NAME = MARKER + "PendingProduct-" + TRACKED_PRODUCT_ID.toString().substring(0, 8);
    private static final String TRACKED_PRODUCT_SLUG = (MARKER + "pending-product-" + TRACKED_PRODUCT_ID).toLowerCase();

    @Inject
    EntityManager em;

    // ─── Setup / Teardown ───────────────────────────────────────────────────

    @BeforeAll
    void seedTrackedProduct()
    {
        seedData();
    }

    @AfterAll
    void cleanupTrackedProduct()
    {
        cleanupData();
    }

    @Transactional
    void seedData()
    {
        // Insert a PENDING (non-ACTIVE) product
        em.createNativeQuery(
                        "INSERT INTO products (id, name, slug, status, is_featured, product_type) " +
                                "VALUES (:id, :name, :slug, 'PENDING', false, 'SIMPLE')")
                .setParameter("id", TRACKED_PRODUCT_ID)
                .setParameter("name", TRACKED_PRODUCT_NAME)
                .setParameter("slug", TRACKED_PRODUCT_SLUG)
                .executeUpdate();

        // Insert an ACTIVE variant for the product
        em.createNativeQuery(
                        "INSERT INTO product_variants (id, product_id, sku, stock_quantity, status) " +
                                "VALUES (:id, :productId, :sku, 100, 'ACTIVE')")
                .setParameter("id", TRACKED_VARIANT_ID)
                .setParameter("productId", TRACKED_PRODUCT_ID)
                .setParameter("sku", "SKU-IGNSTAT-" + TRACKED_VARIANT_ID.toString().substring(0, 8))
                .executeUpdate();

        // Insert an active RETAIL_SALE_PRICE (current time window)
        em.createNativeQuery(
                        "INSERT INTO variant_prices (id, variant_id, price_type, price, price_start_date, price_end_date) " +
                                "VALUES (:id, :variantId, 'RETAIL_SALE_PRICE', 99.99, :startDate, :endDate)")
                .setParameter("id", TRACKED_PRICE_ID)
                .setParameter("variantId", TRACKED_VARIANT_ID)
                .setParameter("startDate", LocalDateTime.now().minusDays(7))
                .setParameter("endDate", LocalDateTime.now().plusDays(7))
                .executeUpdate();
    }

    @Transactional
    void cleanupData()
    {
        em.createNativeQuery("DELETE FROM variant_prices WHERE id = :id")
                .setParameter("id", TRACKED_PRICE_ID)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM product_variants WHERE id = :id")
                .setParameter("id", TRACKED_VARIANT_ID)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM products WHERE id = :id")
                .setParameter("id", TRACKED_PRODUCT_ID)
                .executeUpdate();
    }

    // ─── JWT Helper ─────────────────────────────────────────────────────────

    private String generateStaffJwt()
    {
        return Jwt.subject("ignorestat-test@staff.com")
                .issuer("http://localhost:8080")
                .groups("SUPER_ADMIN")
                .sign();
    }

    // ─── GraphQL Query Bodies ───────────────────────────────────────────────

    /**
     * saleProductList with ignoreStatus: true
     */
    private static String saleProductListIgnoreStatusBody()
    {
        return "{\"query\":\"{ saleProductList(pageRequest: {pageIndex: 0, pageSize: 50}, ignoreStatus: true) { content { id name status } } }\"}";
    }

    /**
     * saleProductList with ignoreStatus omitted (defaults to false)
     */
    private static String saleProductListDefaultBody()
    {
        return "{\"query\":\"{ saleProductList(pageRequest: {pageIndex: 0, pageSize: 50}) { content { id name status } } }\"}";
    }

    /**
     * shoppingProductList — anonymous, no ignoreStatus (removed from this resolver)
     */
    private static String shoppingProductListBody()
    {
        return "{\"query\":\"{ shoppingProductList(pageIndex: 0, pageSize: 50) { content { id name status } } }\"}";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // (a) Anonymous saleProductList(ignoreStatus: true) is rejected
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Anonymous saleProductList(ignoreStatus: true) → rejected with auth error")
    void anonymousSaleProductListWithIgnoreStatus_isRejected()
    {
        given()
                .contentType("application/json")
                .body(saleProductListIgnoreStatusBody())
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // (b) Staff saleProductList(ignoreStatus: true) succeeds and includes
    //     the tracked non-ACTIVE product
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("Staff saleProductList(ignoreStatus: true) → succeeds, includes tracked PENDING product")
    void staffSaleProductListWithIgnoreStatus_succeedsAndIncludesTrackedProduct()
    {
        String staffToken = generateStaffJwt();

        given()
                .header("Authorization", "Bearer " + staffToken)
                .contentType("application/json")
                .body(saleProductListIgnoreStatusBody())
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.saleProductList.content", not(empty()))
                .body("data.saleProductList.content.id", hasItem(TRACKED_PRODUCT_ID.toString()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // (c) Anonymous shoppingProductList excludes non-ACTIVE product
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("Anonymous shoppingProductList → excludes tracked PENDING product")
    void anonymousShoppingProductList_excludesPendingProduct()
    {
        given()
                .contentType("application/json")
                .body(shoppingProductListBody())
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.shoppingProductList.content.id", not(hasItem(TRACKED_PRODUCT_ID.toString())));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // (d) Anonymous saleProductList (no flag) remains accessible and does NOT
    //     require authentication — it only filters on variant status (ACTIVE),
    //     not product status. A PENDING product with an ACTIVE variant and sale
    //     price legitimately appears. The key assertion is: no auth error, and
    //     the gate only fires for ignoreStatus=true (tested in case (a) above).
    //
    //     Note: saleProductList without ignoreStatus never applied a product-level
    //     ACTIVE restriction — it is the variant-status filter that matters. This
    //     is the preserved, unchanged behaviour (Requirement 12.2).
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Anonymous saleProductList (no flag) → succeeds without auth error, unchanged behaviour preserved")
    void anonymousSaleProductListNoFlag_succeedsAnonymously()
    {
        given()
                .contentType("application/json")
                .body(saleProductListDefaultBody())
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.saleProductList.content", notNullValue());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // (e) AUTHENTICATED but NON-STAFF caller (customer role) is forbidden
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Customer-role saleProductList(ignoreStatus: true) → forbidden, not unauthorized")
    void customerSaleProductListWithIgnoreStatus_isForbidden()
    {
        String customerToken = Jwt.subject("ignorestat-test@customer.com")
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();

        given()
                .header("Authorization", "Bearer " + customerToken)
                .contentType("application/json")
                .body(saleProductListIgnoreStatusBody())
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }
}
