package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.backend.service.WishlistHydrationService;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.dto.WishlistHydratedItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Integration test for POST /api/storefront/wishlist/hydrate.
 * Full HTTP round-trip — no JWT required (endpoint is public).
 *
 * Validates: Requirements 3.1, 3.2, 3.7
 */
@QuarkusTest
class WishlistHydrationIT {

    @InjectMock
    WishlistHydrationService wishlistHydrationService;

    private UUID activeVariantId;
    private UUID inactiveVariantId;
    private WishlistHydratedItemDto hydratedItem;

    @BeforeEach
    void setUp() {
        activeVariantId = UUID.randomUUID();
        inactiveVariantId = UUID.randomUUID();

        // Build a hydrated item for the active variant
        hydratedItem = new WishlistHydratedItemDto();
        hydratedItem.variantId = activeVariantId;
        hydratedItem.variantLabel = "{\"size\": \"M\", \"color\": \"Blue\"}";
        hydratedItem.sku = "TEST-SKU-001";
        hydratedItem.productId = UUID.randomUUID();
        hydratedItem.productName = "Test Product";
        hydratedItem.productSlug = "test-product";
        hydratedItem.imagePath = "images/01/test-product.png";

        VariantPriceDto retailPrice = new VariantPriceDto();
        retailPrice.id = UUID.randomUUID().toString();
        retailPrice.priceType = "RETAIL_PRICE";
        retailPrice.price = new BigDecimal("199.99");
        retailPrice.isActive = true;
        hydratedItem.retailPrice = retailPrice;

        hydratedItem.wholesalePrice = null;
        hydratedItem.retailSalePrice = null;
        hydratedItem.wholesaleSalePrice = null;
    }

    // ── Valid IDs return hydrated items ───────────────────────────────────────

    @Test
    void hydrate_validActiveIds_returnsHydratedItems() {
        when(wishlistHydrationService.hydrate(List.of(activeVariantId)))
                .thenReturn(List.of(hydratedItem));

        given()
                .contentType("application/json")
                .body("{\"variantIds\": [\"" + activeVariantId + "\"]}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].variantId", equalTo(activeVariantId.toString()))
                .body("items[0].variantLabel", equalTo("{\"size\": \"M\", \"color\": \"Blue\"}"))
                .body("items[0].sku", equalTo("TEST-SKU-001"))
                .body("items[0].productName", equalTo("Test Product"))
                .body("items[0].productSlug", equalTo("test-product"))
                .body("items[0].imagePath", equalTo("images/01/test-product.png"))
                .body("items[0].retailPrice.price", equalTo(199.99f))
                .body("items[0].retailPrice.priceType", equalTo("RETAIL_PRICE"))
                .body("items[0].retailPrice.isActive", equalTo(true));
    }

    // ── Mixed active/inactive returns only active ────────────────────────────

    @Test
    void hydrate_mixedActiveAndInactive_returnsOnlyActive() {
        // The service filters out inactive variants — returns only the active one
        when(wishlistHydrationService.hydrate(List.of(activeVariantId, inactiveVariantId)))
                .thenReturn(List.of(hydratedItem));

        given()
                .contentType("application/json")
                .body("{\"variantIds\": [\"" + activeVariantId + "\", \"" + inactiveVariantId + "\"]}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].variantId", equalTo(activeVariantId.toString()));
    }

    @Test
    void hydrate_allInactive_returnsEmptyItems() {
        // All requested variants are inactive — service returns empty list
        when(wishlistHydrationService.hydrate(List.of(inactiveVariantId)))
                .thenReturn(Collections.emptyList());

        given()
                .contentType("application/json")
                .body("{\"variantIds\": [\"" + inactiveVariantId + "\"]}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    // ── Empty request returns empty items ────────────────────────────────────

    @Test
    void hydrate_emptyVariantIds_returnsEmptyItems() {
        given()
                .contentType("application/json")
                .body("{\"variantIds\": []}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    @Test
    void hydrate_nullVariantIds_returnsEmptyItems() {
        given()
                .contentType("application/json")
                .body("{}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    @Test
    void hydrate_nullRequestBody_returnsEmptyItems() {
        given()
                .contentType("application/json")
                .body("null")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    // ── Over-limit (>50) returns 400 ─────────────────────────────────────────

    @Test
    void hydrate_overFiftyIds_returns400() {
        List<UUID> fiftyOneIds = IntStream.range(0, 51)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        // Build JSON array of 51 UUIDs
        StringBuilder body = new StringBuilder("{\"variantIds\": [");
        for (int i = 0; i < fiftyOneIds.size(); i++) {
            if (i > 0) body.append(", ");
            body.append("\"").append(fiftyOneIds.get(i)).append("\"");
        }
        body.append("]}");

        given()
                .contentType("application/json")
                .body(body.toString())
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(400)
                .body("error", equalTo("Maximum 50 variant IDs per request"));
    }

    @Test
    void hydrate_exactlyFiftyIds_isAllowed() {
        List<UUID> fiftyIds = IntStream.range(0, 50)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        when(wishlistHydrationService.hydrate(anyList()))
                .thenReturn(Collections.emptyList());

        // Build JSON array of 50 UUIDs
        StringBuilder body = new StringBuilder("{\"variantIds\": [");
        for (int i = 0; i < fiftyIds.size(); i++) {
            if (i > 0) body.append(", ");
            body.append("\"").append(fiftyIds.get(i)).append("\"");
        }
        body.append("]}");

        given()
                .contentType("application/json")
                .body(body.toString())
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(0));
    }

    // ── No auth required (public endpoint) ───────────────────────────────────

    @Test
    void hydrate_withoutJwt_returns200() {
        // Endpoint is public — no JWT required
        when(wishlistHydrationService.hydrate(List.of(activeVariantId)))
                .thenReturn(List.of(hydratedItem));

        given()
                .contentType("application/json")
                .body("{\"variantIds\": [\"" + activeVariantId + "\"]}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].variantId", equalTo(activeVariantId.toString()));
    }

    // ── Multiple valid items ─────────────────────────────────────────────────

    @Test
    void hydrate_multipleValidIds_returnsAllHydratedItems() {
        UUID secondVariantId = UUID.randomUUID();

        WishlistHydratedItemDto secondItem = new WishlistHydratedItemDto();
        secondItem.variantId = secondVariantId;
        secondItem.variantLabel = "{\"size\": \"L\"}";
        secondItem.sku = "TEST-SKU-002";
        secondItem.productId = UUID.randomUUID();
        secondItem.productName = "Second Product";
        secondItem.productSlug = "second-product";
        secondItem.imagePath = null; // No image

        VariantPriceDto wholesalePrice = new VariantPriceDto();
        wholesalePrice.id = UUID.randomUUID().toString();
        wholesalePrice.priceType = "WHOLESALE_PRICE";
        wholesalePrice.price = new BigDecimal("149.50");
        wholesalePrice.isActive = true;
        secondItem.wholesalePrice = wholesalePrice;

        when(wishlistHydrationService.hydrate(List.of(activeVariantId, secondVariantId)))
                .thenReturn(List.of(hydratedItem, secondItem));

        given()
                .contentType("application/json")
                .body("{\"variantIds\": [\"" + activeVariantId + "\", \"" + secondVariantId + "\"]}")
        .when()
                .post("/api/storefront/wishlist/hydrate")
        .then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].variantId", equalTo(activeVariantId.toString()))
                .body("items[0].productName", equalTo("Test Product"))
                .body("items[1].variantId", equalTo(secondVariantId.toString()))
                .body("items[1].productName", equalTo("Second Product"))
                .body("items[1].imagePath", nullValue())
                .body("items[1].wholesalePrice.price", equalTo(149.50f));
    }
}
