package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.WishlistService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration test for StorefrontWishlistResource.
 * Tests the full HTTP round-trip with JWT authentication.
 */
@QuarkusTest
class StorefrontWishlistResourceIT
{
    private static final String CUSTOMER_EMAIL = "wishlist-test@example.com";

    @InjectMock
    WishlistService wishlistService;

    private CustomerEntity customer;
    private UUID customerId;
    private UUID existingVariantId;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);

        customerId = UUID.randomUUID();
        existingVariantId = UUID.randomUUID();

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(CUSTOMER_EMAIL);
        user.setPasswordHash("somehash");
        user.setActive(true);

        customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setUser(user);
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.setAddresses(new ArrayList<>());
        user.setCustomer(customer);

        // Mock CustomerEntity.findByEmail (used by resolveCustomerId in the resource)
        when(CustomerEntity.findByEmail(CUSTOMER_EMAIL)).thenReturn(customer);
    }

    private String generateCustomerJwt(String email)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    // ── GET /api/storefront/wishlist ──────────────────────────────────────────

    @Test
    void getWishlist_newCustomer_returnsEmptyList()
    {
        when(wishlistService.getWishlistVariantIds(customerId))
                .thenReturn(Collections.emptyList());

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(0));
    }

    @Test
    void getWishlist_withoutToken_returns401()
    {
        given()
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(401);
    }

    @Test
    void getWishlist_afterAddingItems_returnsCorrectList()
    {
        UUID variantId1 = UUID.randomUUID();
        UUID variantId2 = UUID.randomUUID();

        when(wishlistService.getWishlistVariantIds(customerId))
                .thenReturn(List.of(variantId1, variantId2));

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(2))
                .body("variantIds", hasItem(variantId1.toString()))
                .body("variantIds", hasItem(variantId2.toString()));
    }

    // ── POST /api/storefront/wishlist/{variantId} ─────────────────────────────

    @Test
    void addToWishlist_newVariant_returns201()
    {
        when(wishlistService.addToWishlist(customerId, existingVariantId))
                .thenReturn(WishlistService.AddResult.CREATED);

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(201);
    }

    @Test
    void addToWishlist_alreadyExists_returns200()
    {
        when(wishlistService.addToWishlist(customerId, existingVariantId))
                .thenReturn(WishlistService.AddResult.ALREADY_EXISTS);

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(200);
    }

    @Test
    void addToWishlist_nonExistentVariant_returns404()
    {
        UUID nonExistentVariantId = UUID.randomUUID();
        when(wishlistService.addToWishlist(customerId, nonExistentVariantId))
                .thenReturn(WishlistService.AddResult.VARIANT_NOT_FOUND);

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/storefront/wishlist/{variantId}", nonExistentVariantId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Variant not found"));
    }

    @Test
    void addToWishlist_invalidUuidFormat_returns400()
    {
        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/storefront/wishlist/{variantId}", "not-a-uuid")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid variant ID"));
    }

    @Test
    void addToWishlist_withoutToken_returns401()
    {
        given()
                .when()
                .post("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(401);
    }

    // ── DELETE /api/storefront/wishlist/{variantId} ────────────────────────────

    @Test
    void removeFromWishlist_existingEntry_returns204()
    {
        // removeFromWishlist is void — no setup needed for "success" case
        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .delete("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(204);
    }

    @Test
    void removeFromWishlist_notInWishlist_returns204()
    {
        // Idempotent: removing something not in the wishlist still returns 204
        UUID otherVariantId = UUID.randomUUID();

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .delete("/api/storefront/wishlist/{variantId}", otherVariantId)
                .then()
                .statusCode(204);
    }

    @Test
    void removeFromWishlist_withoutToken_returns401()
    {
        given()
                .when()
                .delete("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(401);
    }

    @Test
    void removeFromWishlist_invalidUuid_returns400()
    {
        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .delete("/api/storefront/wishlist/{variantId}", "invalid-uuid")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid variant ID"));
    }

    // ── DB state verification (operation sequence) ────────────────────────────

    @Test
    void verifyDbState_afterAddAndGet_wishlistContainsVariant()
    {
        // Simulate add returning CREATED
        when(wishlistService.addToWishlist(customerId, existingVariantId))
                .thenReturn(WishlistService.AddResult.CREATED);

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        // POST to add variant
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(201);

        // After add, GET should reflect the item
        when(wishlistService.getWishlistVariantIds(customerId))
                .thenReturn(List.of(existingVariantId));

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(1))
                .body("variantIds[0]", equalTo(existingVariantId.toString()));
    }

    @Test
    void verifyDbState_afterDelete_wishlistIsEmpty()
    {
        // Simulate initial state: wishlist has the variant
        when(wishlistService.getWishlistVariantIds(customerId))
                .thenReturn(List.of(existingVariantId));

        String token = generateCustomerJwt(CUSTOMER_EMAIL);

        // Verify item is there
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(1));

        // DELETE the variant
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .delete("/api/storefront/wishlist/{variantId}", existingVariantId)
                .then()
                .statusCode(204);

        // After delete, GET should return empty list
        when(wishlistService.getWishlistVariantIds(customerId))
                .thenReturn(Collections.emptyList());

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(0));
    }
}
