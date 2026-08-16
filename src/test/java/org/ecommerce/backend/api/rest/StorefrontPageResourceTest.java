package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.backend.service.PageContentService;
import org.ecommerce.common.dto.PageContentDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for the public storefront page content endpoint.
 */
@QuarkusTest
class StorefrontPageResourceTest {

    @InjectMock
    PageContentService pageContentService;

    @Test
    void getBySlug_publishedPage_returns200WithPublishedContent() {
        UUID id = UUID.randomUUID();
        OffsetDateTime publishedAt = OffsetDateTime.now().minusDays(3);
        OffsetDateTime updatedAt = OffsetDateTime.now().minusDays(1);

        PageContentDto published = new PageContentDto(
                id,
                "privacy-policy",
                "Privacy Policy",
                "LEGAL",
                null,
                "<p>Your privacy matters to us.</p>",
                publishedAt,
                updatedAt
        );

        when(pageContentService.getPublishedBySlug("privacy-policy")).thenReturn(published);

        given()
                .when()
                .get("/api/storefront/pages/privacy-policy")
                .then()
                .statusCode(200)
                .body("slug", equalTo("privacy-policy"))
                .body("title", equalTo("Privacy Policy"))
                .body("content", equalTo("<p>Your privacy matters to us.</p>"))
                .body("publishedAt", notNullValue());
    }

    @Test
    void getBySlug_neverPublishedPage_returns404() {
        when(pageContentService.getPublishedBySlug("terms-and-conditions")).thenReturn(null);

        given()
                .when()
                .get("/api/storefront/pages/terms-and-conditions")
                .then()
                .statusCode(404);
    }

    @Test
    void getBySlug_nonExistentSlug_returns404() {
        when(pageContentService.getPublishedBySlug("non-existent-page")).thenReturn(null);

        given()
                .when()
                .get("/api/storefront/pages/non-existent-page")
                .then()
                .statusCode(404);
    }

    @Test
    void getBySlug_noAuthHeader_returns200_verifiesPermitAll() {
        UUID id = UUID.randomUUID();
        OffsetDateTime publishedAt = OffsetDateTime.now().minusDays(5);
        OffsetDateTime updatedAt = OffsetDateTime.now().minusDays(2);

        PageContentDto published = new PageContentDto(
                id,
                "delivery-and-returns",
                "Delivery & Returns",
                "LEGAL",
                null,
                "<p>Free returns within 30 days.</p>",
                publishedAt,
                updatedAt
        );

        when(pageContentService.getPublishedBySlug("delivery-and-returns")).thenReturn(published);

        // Explicitly make the request with NO Authorization header to verify @PermitAll
        given()
                .when()
                .get("/api/storefront/pages/delivery-and-returns")
                .then()
                .statusCode(200)
                .body("slug", equalTo("delivery-and-returns"))
                .body("title", equalTo("Delivery & Returns"))
                .body("content", equalTo("<p>Free returns within 30 days.</p>"));
    }
}
