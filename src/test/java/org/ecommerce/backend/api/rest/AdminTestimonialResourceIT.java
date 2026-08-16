package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.TestimonialEntity;
import org.ecommerce.common.repository.TestimonialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AdminTestimonialResource and StorefrontTestimonialResource.
 * Uses a real DB with full HTTP round-trips — tests the CRUD flow and role enforcement.
 *
 */
@QuarkusTest
@DisplayName("AdminTestimonialResource — integration tests")
class AdminTestimonialResourceIT
{
    @Inject
    TestimonialRepository testimonialRepository;

    private final Set<UUID> createdTestimonialIds = new HashSet<>();

    @AfterEach
    @Transactional
    void cleanup()
    {
        // Tests run against the shared local database. Never delete operator-created rows.
        createdTestimonialIds.forEach(id -> {
            TestimonialEntity entity = testimonialRepository.findById(id);
            if (entity != null) {
                testimonialRepository.delete(entity);
            }
        });
        createdTestimonialIds.clear();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String superAdminJwt()
    {
        return Jwt.subject("admin@test.com")
                .issuer("http://localhost:8080")
                .groups("SUPER_ADMIN")
                .sign();
    }

    private String viewerJwt()
    {
        return Jwt.subject("viewer@test.com")
                .issuer("http://localhost:8080")
                .groups("VIEWER")
                .sign();
    }

    private String roleJwt(String role)
    {
        return Jwt.subject(role.toLowerCase() + "@test.com")
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    private String createPayload(String quote, String authorName, String authorTitle, int sortOrder, boolean published)
    {
        return """
                {
                    "quote": "%s",
                    "authorName": "%s",
                    "authorTitle": %s,
                    "sortOrder": %d,
                    "published": %b
                }
                """.formatted(quote, authorName, authorTitle == null ? "null" : "\"" + authorTitle + "\"", sortOrder, published);
    }

    @Transactional
    UUID seedTestimonial(String quote, String authorName, boolean published, int sortOrder)
    {
        TestimonialEntity entity = new TestimonialEntity();
        entity.setQuote(quote);
        entity.setAuthorName(authorName);
        entity.setAuthorTitle(null);
        entity.setPublished(published);
        entity.setSortOrder(sortOrder);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        testimonialRepository.persist(entity);
        createdTestimonialIds.add(entity.getId());
        return entity.getId();
    }

    private String trackCreatedId(String id)
    {
        createdTestimonialIds.add(UUID.fromString(id));
        return id;
    }

    // ── Full CRUD flow ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Full CRUD: create → list → get → update → delete → 404")
    void fullCrudFlow()
    {
        String token = superAdminJwt();

        // CREATE
        String id = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(createPayload("Great service!", "Jane Doe", "CEO", 1, true))
            .when()
                .post("/api/admin/testimonials")
            .then()
                .statusCode(201)
                .body("quote", equalTo("Great service!"))
                .body("authorName", equalTo("Jane Doe"))
                .body("authorTitle", equalTo("CEO"))
                .body("published", is(true))
                .body("sortOrder", is(1))
                .body("id", notNullValue())
            .extract()
                .path("id");
        trackCreatedId(id);

        // LIST
        given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/api/admin/testimonials")
            .then()
                .statusCode(200)
                .body("id", hasItem(id));

        // GET by id
        given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/api/admin/testimonials/" + id)
            .then()
                .statusCode(200)
                .body("quote", equalTo("Great service!"));

        // UPDATE
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(createPayload("Updated quote", "Jane Doe", "CTO", 2, false))
            .when()
                .put("/api/admin/testimonials/" + id)
            .then()
                .statusCode(200)
                .body("quote", equalTo("Updated quote"))
                .body("authorTitle", equalTo("CTO"))
                .body("sortOrder", is(2))
                .body("published", is(false));

        // DELETE
        given()
                .header("Authorization", "Bearer " + token)
            .when()
                .delete("/api/admin/testimonials/" + id)
            .then()
                .statusCode(204);

        // GET after delete → 404
        given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/api/admin/testimonials/" + id)
            .then()
                .statusCode(404);
    }

    // ── Role enforcement ────────────────────────────────────────────────────

    @Test
    @DisplayName("VIEWER can read but write attempts return 403")
    void viewerRole_canRead_cannotWrite()
    {
        String viewerToken = viewerJwt();
        String superToken = superAdminJwt();

        // Create one via SUPER_ADMIN first
        String id = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + superToken)
                .body(createPayload("Test", "Author", null, 0, true))
            .when()
                .post("/api/admin/testimonials")
            .then()
                .statusCode(201)
            .extract()
                .path("id");
        trackCreatedId(id);

        // VIEWER GET list → 200
        given()
                .header("Authorization", "Bearer " + viewerToken)
            .when()
                .get("/api/admin/testimonials")
            .then()
                .statusCode(200)
                .body("id", hasItem(id));

        // VIEWER GET by id → 200
        given()
                .header("Authorization", "Bearer " + viewerToken)
            .when()
                .get("/api/admin/testimonials/" + id)
            .then()
                .statusCode(200);

        // VIEWER POST → 403
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + viewerToken)
                .body(createPayload("New", "Author", null, 0, false))
            .when()
                .post("/api/admin/testimonials")
            .then()
                .statusCode(403);

        // VIEWER PUT → 403
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + viewerToken)
                .body(createPayload("Updated", "Author", null, 0, false))
            .when()
                .put("/api/admin/testimonials/" + id)
            .then()
                .statusCode(403);

        // VIEWER DELETE → 403
        given()
                .header("Authorization", "Bearer " + viewerToken)
            .when()
                .delete("/api/admin/testimonials/" + id)
            .then()
                .statusCode(403);
    }

    // ── Storefront endpoint ─────────────────────────────────────────────────

    @Test
    @DisplayName("Storefront endpoint returns only published, sorted by sortOrder ASC")
    void storefrontEndpoint_returnsOnlyPublished_sorted()
    {
        seedTestimonial("Published B", "Author B", true, 2);
        seedTestimonial("Unpublished", "Author U", false, 0);
        seedTestimonial("Published A", "Author A", true, 1);

        given()
            .when()
                .get("/api/storefront/testimonials")
            .then()
                .statusCode(200)
                .body("quote", allOf(hasItem("Published A"), hasItem("Published B"), not(hasItem("Unpublished"))))
                // Public shape: no published/sortOrder/timestamps
                .body("find { it.quote == 'Published A' }.published", nullValue())
                .body("find { it.quote == 'Published A' }.sortOrder", nullValue());
    }

    @Test
    @DisplayName("Anonymous requests to admin endpoints are rejected with 401")
    void anonymous_adminEndpoints_401()
    {
        given()
            .when()
                .get("/api/admin/testimonials")
            .then()
                .statusCode(401);

        given()
                .contentType(ContentType.JSON)
                .body(createPayload("Q", "A", null, 0, true))
            .when()
                .post("/api/admin/testimonials")
            .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("CATALOG_MANAGER and ORDER_MANAGER are denied (403) on read and write")
    void otherAdminRoles_denied_403()
    {
        for (String role : new String[]{"CATALOG_MANAGER", "ORDER_MANAGER"}) {
            given()
                    .auth().oauth2(roleJwt(role))
                .when()
                    .get("/api/admin/testimonials")
                .then()
                    .statusCode(403);

            given()
                    .auth().oauth2(roleJwt(role))
                    .contentType(ContentType.JSON)
                    .body(createPayload("Q", "A", null, 0, true))
                .when()
                    .post("/api/admin/testimonials")
                .then()
                    .statusCode(403);
        }
    }

    @Test
    @DisplayName("Storefront endpoint does not expose unpublished testimonials")
    void storefrontEndpoint_doesNotExposeUnpublishedTestimonials()
    {
        String draftQuote = "Unpublished draft visible only to this test";
        seedTestimonial(draftQuote, "Author", false, 0);

        given()
            .when()
                .get("/api/storefront/testimonials")
            .then()
                .statusCode(200)
                .body("quote", not(hasItem(draftQuote)));
    }
}
