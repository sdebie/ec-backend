package org.ecommerce.backend.api.graphql;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * QuoteRequestAdminResourceIT mocks both QuoteRequestRepository and QuoteRequestService, so
 * it can never catch a Hibernate session-lifetime bug — its mocked entity's items are a
 * plain ArrayList, never a lazy proxy. This class persists a real entity with real
 * lazily-loaded items and drives updateQuoteRequestStatus over real HTTP, so the
 * transaction/session boundary exercised is the actual production one.
 */
@QuarkusTest
class QuoteRequestStatusUpdateSessionIT
{
    @Inject
    EntityManager em;

    private UUID quoteRequestId;

    @BeforeEach
    void seed()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            QuoteRequestEntity request = new QuoteRequestEntity();
            request.setName("Session Test");
            request.setEmail("session-test@example.com");
            request.setStatus(QuoteRequestStatusEn.NEW);
            request.setCreatedAt(Instant.now());

            QuoteRequestItemEntity item = new QuoteRequestItemEntity();
            item.setQuoteRequest(request);
            item.setProductNameSnapshot("Snapshot Only Product");
            item.setVariantSkuSnapshot("SKU-1");
            item.setQuantity(2);
            request.setItems(new ArrayList<>(List.of(item)));

            QuoteRequestEntity.persist(request);
            quoteRequestId = request.getId();
        });
    }

    @AfterEach
    void cleanup()
    {
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("delete from QuoteRequestEntity q where q.id = :id")
                        .setParameter("id", quoteRequestId)
                        .executeUpdate());
    }

    private String generateStaffJwt()
    {
        return Jwt.subject("staff-super_admin@test.com")
                .issuer("http://localhost:8080")
                .groups("SUPER_ADMIN")
                .sign();
    }

    @Test
    @DisplayName("a real NEW quote with items moves to IN_PROGRESS without a LazyInitializationException")
    void updateStatus_realPersistedEntityWithItems_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt())
                .contentType("application/json")
                .body("""
                        {"query": "mutation { updateQuoteRequestStatus(id: \\"%s\\", status: \\"IN_PROGRESS\\") { id status } }"}
                        """.formatted(quoteRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.updateQuoteRequestStatus.id", equalTo(quoteRequestId.toString()))
                .body("data.updateQuoteRequestStatus.status", equalTo("IN_PROGRESS"));
    }
}
