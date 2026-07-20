package org.ecommerce.backend.exception;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.FeaturedProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests that the SmallRye GraphQL show-runtime-exception-message whitelist correctly
 * surfaces exception messages to the client for whitelisted exception types.
 *
 * Validates: Requirement 3.3 (rate-limit message surfaces in GraphQL errors array)
 * and the pre-existing FeaturedCapExceededException FQN fix.
 */
@QuarkusTest
class GraphQlExceptionWhitelistTest {

    private static final String SET_FEATURED_MUTATION =
            "mutation($productId: String!, $featured: Boolean!) { setProductFeatured(productId: $productId, featured: $featured) { productId featured } }";

    @InjectMock
    FeaturedProductService featuredProductService;

    private String generateStaffJwt() {
        return Jwt.subject("admin@test.com")
                .issuer("http://localhost:8080")
                .groups("SUPER_ADMIN")
                .sign();
    }

    private String graphqlBody(String query, String productId, boolean featured) {
        return "{\"query\":\"" + query.replace("\"", "\\\"") + "\","
                + "\"variables\":{\"productId\":\"" + productId + "\",\"featured\":" + featured + "}}";
    }

    @Test
    @DisplayName("RateLimitExceededException message surfaces in GraphQL errors array")
    void rateLimitExceededException_messageSurfacesInGraphQlErrors() {
        String token = generateStaffJwt();
        String productId = UUID.randomUUID().toString();

        when(featuredProductService.setFeatured(any(UUID.class), eq(true)))
                .thenThrow(new RateLimitExceededException());

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlBody(SET_FEATURED_MUTATION, productId, true))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", equalTo("Too many requests \u2014 please try again later."))
                .body("data.setProductFeatured", nullValue());
    }

    @Test
    @DisplayName("FeaturedCapExceededException message surfaces in GraphQL errors array (post-FQN fix)")
    void featuredCapExceededException_messageSurfacesInGraphQlErrors() {
        String token = generateStaffJwt();
        String productId = UUID.randomUUID().toString();

        when(featuredProductService.setFeatured(any(UUID.class), eq(true)))
                .thenThrow(new FeaturedCapExceededException());

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlBody(SET_FEATURED_MUTATION, productId, true))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", equalTo("Featured limit of 50 reached. Remove a product before adding another."))
                .body("data.setProductFeatured", nullValue());
    }
}
