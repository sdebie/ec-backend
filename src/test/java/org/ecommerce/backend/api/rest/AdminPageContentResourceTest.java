package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.PageContentService;
import org.ecommerce.common.dto.PageContentDto;
import org.ecommerce.common.dto.PageContentSummaryDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for the admin page content endpoint.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 3.5
 */
@QuarkusTest
class AdminPageContentResourceTest {

    @InjectMock
    PageContentService pageContentService;

    private String generateAdminJwt(String role) {
        return Jwt.subject("admin@example.com")
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    // ── PUT (save draft) ─────────────────────────────────────────────────────

    @Test
    void saveDraft_asSuperAdmin_returns200() {
        UUID pageId = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.now();

        PageContentDto result = new PageContentDto(
                pageId,
                "privacy-policy",
                "Privacy Policy",
                "LEGAL",
                "<p>Updated draft content</p>",
                null,
                null,
                updatedAt
        );

        when(pageContentService.saveDraft(eq(pageId), eq("<p>Updated draft content</p>")))
                .thenReturn(result);

        String token = generateAdminJwt("SUPER_ADMIN");

        given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body("{\"content\":\"<p>Updated draft content</p>\"}")
                .when()
                .put("/api/admin/pages/{id}", pageId)
                .then()
                .statusCode(200)
                .body("id", equalTo(pageId.toString()))
                .body("draftContent", equalTo("<p>Updated draft content</p>"));
    }

    @Test
    void saveDraft_asViewer_returns403() {
        UUID pageId = UUID.randomUUID();
        String token = generateAdminJwt("VIEWER");

        given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body("{\"content\":\"<p>Some content</p>\"}")
                .when()
                .put("/api/admin/pages/{id}", pageId)
                .then()
                .statusCode(403);
    }

    // ── POST publish ─────────────────────────────────────────────────────────

    @Test
    void publish_asSuperAdmin_returns200() {
        UUID pageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        PageContentDto result = new PageContentDto(
                pageId,
                "terms-and-conditions",
                "Terms & Conditions",
                "LEGAL",
                "<p>Published terms</p>",
                "<p>Published terms</p>",
                now,
                now
        );

        when(pageContentService.publish(eq(pageId))).thenReturn(result);

        String token = generateAdminJwt("SUPER_ADMIN");

        given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/admin/pages/{id}/publish", pageId)
                .then()
                .statusCode(200)
                .body("id", equalTo(pageId.toString()))
                .body("publishedAt", notNullValue())
                .body("publishedContent", equalTo("<p>Published terms</p>"));
    }

    @Test
    void publish_asViewer_returns403() {
        UUID pageId = UUID.randomUUID();
        String token = generateAdminJwt("VIEWER");

        given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/admin/pages/{id}/publish", pageId)
                .then()
                .statusCode(403);
    }

    // ── GET list ─────────────────────────────────────────────────────────────

    @Test
    void listByCategory_asViewer_returns200() {
        UUID pageId = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.now();

        List<PageContentSummaryDto> summaries = List.of(
                new PageContentSummaryDto(
                        pageId,
                        "privacy-policy",
                        "Privacy Policy",
                        "LEGAL",
                        null,
                        updatedAt,
                        false
                )
        );

        when(pageContentService.listByCategory("LEGAL")).thenReturn(summaries);

        String token = generateAdminJwt("VIEWER");

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/pages?category=LEGAL")
                .then()
                .statusCode(200)
                .body("[0].id", equalTo(pageId.toString()))
                .body("[0].title", equalTo("Privacy Policy"))
                .body("[0].slug", equalTo("privacy-policy"));
    }

    // ── GET by id ────────────────────────────────────────────────────────────

    @Test
    void getById_invalidId_returns404() {
        UUID nonExistentId = UUID.randomUUID();

        when(pageContentService.getById(nonExistentId)).thenReturn(null);

        String token = generateAdminJwt("SUPER_ADMIN");

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/pages/{id}", nonExistentId)
                .then()
                .statusCode(404);
    }
}
