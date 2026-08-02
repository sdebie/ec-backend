package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Failsafe integration test for the wishlist hydration endpoint
 * ({@code POST /api/storefront/wishlist/hydrate}).
 *
 * <p>Validates design Property 3 — the four availability partitions:
 * <ol>
 *   <li>ACTIVE variant + ACTIVE product + stock &gt; 0 → {@code inStock: true, productActive: true}</li>
 *   <li>ACTIVE variant + ACTIVE product + stock 0 → {@code inStock: false, productActive: true}</li>
 *   <li>DISABLED variant + ACTIVE product → {@code inStock: false, productActive: true}</li>
 *   <li>DISABLED product → {@code inStock: false, productActive: false}</li>
 * </ol>
 * Plus: nonexistent UUID → omitted from the response.
 *
 * <p><b>Shared-database discipline (KNOWN-LIMITATIONS §5):</b> fixtures are tracked by ID and
 * cleaned up in {@link #cleanup()}. Assertions are relative (checking presence/absence and field
 * values of tracked items only), never absolute row counts on shared tables.
 */
@QuarkusTest
@DisplayName("WishlistHydration — availability partition IT")
class WishlistHydrationIT
{
    private static final Logger LOG = Logger.getLogger(WishlistHydrationIT.class);

    @Inject
    EntityManager em;

    // Tracked IDs for cleanup
    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdProductIds = new ArrayList<>();

    // Fixture variant IDs — one per partition
    private UUID activeInStockVariantId;
    private UUID activeOutOfStockVariantId;
    private UUID disabledVariantId;
    private UUID disabledProductVariantId;

    @BeforeEach
    @Transactional
    void seedFixtures()
    {
        // ── Partition 1: ACTIVE variant + ACTIVE product + stock > 0 ─────────
        ProductEntity activeProduct1 = createProduct("Hydration IT Active1", ProductStatusEn.ACTIVE);
        activeInStockVariantId = createVariant(activeProduct1, ProductStatusEn.ACTIVE, 10).getId();

        // ── Partition 2: ACTIVE variant + ACTIVE product + stock 0 ───────────
        activeOutOfStockVariantId = createVariant(activeProduct1, ProductStatusEn.ACTIVE, 0).getId();

        // ── Partition 3: DISABLED variant + ACTIVE product ───────────────────
        disabledVariantId = createVariant(activeProduct1, ProductStatusEn.DISABLED, 5).getId();

        // ── Partition 4: DISABLED product (variant status irrelevant) ────────
        ProductEntity disabledProduct = createProduct("Hydration IT Disabled", ProductStatusEn.DISABLED);
        disabledProductVariantId = createVariant(disabledProduct, ProductStatusEn.ACTIVE, 20).getId();
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        // Delete variants first (FK to products), then products
        for (UUID variantId : createdVariantIds) {
            em.createQuery("DELETE FROM ProductVariantEntity v WHERE v.id = :id")
                    .setParameter("id", variantId)
                    .executeUpdate();
        }
        for (UUID productId : createdProductIds) {
            em.createQuery("DELETE FROM ProductEntity p WHERE p.id = :id")
                    .setParameter("id", productId)
                    .executeUpdate();
        }
        createdVariantIds.clear();
        createdProductIds.clear();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private ProductEntity createProduct(String nameSuffix, ProductStatusEn status)
    {
        ProductEntity p = new ProductEntity();
        p.setName(nameSuffix + " " + UUID.randomUUID());
        p.setSlug("hydration-it-" + UUID.randomUUID());
        p.setStatus(status);
        p.setProductType(ProductTypeEn.SIMPLE);
        p.persist();
        createdProductIds.add(p.getId());
        return p;
    }

    private ProductVariantEntity createVariant(ProductEntity product, ProductStatusEn status, int stock)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku("HIT-" + UUID.randomUUID());
        v.setStatus(status);
        v.setStockQuantity(stock);
        v.persist();
        createdVariantIds.add(v.getId());
        return v;
    }

    private String hydrateBody(UUID... ids)
    {
        StringBuilder sb = new StringBuilder("{\"variantIds\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids[i]).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    // ─── Tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All four partitions returned with correct inStock + productActive flags")
    void hydrateReturnsAllFourPartitionsWithCorrectFlags()
    {
        UUID nonExistentId = UUID.randomUUID();

        String body = hydrateBody(
                activeInStockVariantId,
                activeOutOfStockVariantId,
                disabledVariantId,
                disabledProductVariantId,
                nonExistentId
        );

        given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                // Exactly 4 items returned (nonexistent ID omitted)
                .body("items", hasSize(4))

                // Partition 1: ACTIVE variant + ACTIVE product + stock > 0
                .body("items.find { it.variantId == '" + activeInStockVariantId + "' }.inStock", is(true))
                .body("items.find { it.variantId == '" + activeInStockVariantId + "' }.productActive", is(true))

                // Partition 2: ACTIVE variant + ACTIVE product + stock 0
                .body("items.find { it.variantId == '" + activeOutOfStockVariantId + "' }.inStock", is(false))
                .body("items.find { it.variantId == '" + activeOutOfStockVariantId + "' }.productActive", is(true))

                // Partition 3: DISABLED variant + ACTIVE product
                .body("items.find { it.variantId == '" + disabledVariantId + "' }.inStock", is(false))
                .body("items.find { it.variantId == '" + disabledVariantId + "' }.productActive", is(true))

                // Partition 4: DISABLED product
                .body("items.find { it.variantId == '" + disabledProductVariantId + "' }.inStock", is(false))
                .body("items.find { it.variantId == '" + disabledProductVariantId + "' }.productActive", is(false));
    }

    @Test
    @DisplayName("Nonexistent UUID is omitted from the response")
    void hydrateOmitsNonexistentId()
    {
        UUID nonExistentId = UUID.randomUUID();

        String body = hydrateBody(activeInStockVariantId, nonExistentId);

        given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                // Only the existing variant is returned
                .body("items", hasSize(1))
                .body("items[0].variantId", equalTo(activeInStockVariantId.toString()))
                // Nonexistent ID does not appear
                .body("items.variantId", not(hasItem(nonExistentId.toString())));
    }

    @Test
    @DisplayName("Both inStock and productActive JSON keys are present on every returned item")
    void hydrateResponseContainsBothJsonKeys()
    {
        String body = hydrateBody(
                activeInStockVariantId,
                activeOutOfStockVariantId,
                disabledVariantId,
                disabledProductVariantId
        );

        given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(4))
                // Every item has both keys present (not null — they are boolean, so check type)
                .body("items.every { it.containsKey('inStock') }", is(true))
                .body("items.every { it.containsKey('productActive') }", is(true));
    }

    @Test
    @DisplayName("Endpoint is @PermitAll — no auth token required")
    void hydrateEndpointRequiresNoAuth()
    {
        String body = hydrateBody(activeInStockVariantId);

        // No Authorization header — should succeed
        given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(1));
    }

    // ─── Unchanged-contract guards (R1.5) ───────────────────────────────────
    // These cover the request-shape contract the rework deliberately did NOT
    // change: the 50-ID cap and the empty/null short-circuit. They were lost
    // when this class was rewritten for the availability partitions; restored
    // here (audit finding F1, 2026-08-02) against real fixtures rather than the
    // original mocks. Guarding StorefrontWishlistResource:62-69.

    @Test
    @DisplayName("More than 50 IDs is rejected with 400")
    void hydrateOverFiftyIdsReturns400()
    {
        UUID[] ids = new UUID[51];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
        }

        given()
                .contentType(ContentType.JSON)
                .body(hydrateBody(ids))
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(400)
                .body("error", is("Maximum 50 variant IDs per request"));
    }

    @Test
    @DisplayName("Exactly 50 IDs is allowed (boundary)")
    void hydrateExactlyFiftyIdsIsAllowed()
    {
        // 49 nonexistent + 1 real: proves the boundary passes the cap check and
        // still resolves, without depending on absolute row counts.
        UUID[] ids = new UUID[50];
        for (int i = 0; i < 49; i++) {
            ids[i] = UUID.randomUUID();
        }
        ids[49] = activeInStockVariantId;

        given()
                .contentType(ContentType.JSON)
                .body(hydrateBody(ids))
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].variantId", is(activeInStockVariantId.toString()));
    }

    @Test
    @DisplayName("Empty variantIds short-circuits to an empty item list")
    void hydrateEmptyVariantIdsReturnsEmptyItems()
    {
        given()
                .contentType(ContentType.JSON)
                .body("{\"variantIds\":[]}")
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    @Test
    @DisplayName("Null variantIds short-circuits to an empty item list")
    void hydrateNullVariantIdsReturnsEmptyItems()
    {
        given()
                .contentType(ContentType.JSON)
                .body("{\"variantIds\":null}")
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    @Test
    @DisplayName("Null request body short-circuits to an empty item list")
    void hydrateNullRequestBodyReturnsEmptyItems()
    {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
            .when()
                .post("/api/storefront/wishlist/hydrate")
            .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }
}
