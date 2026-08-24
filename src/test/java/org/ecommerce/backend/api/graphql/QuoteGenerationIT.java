package org.ecommerce.backend.api.graphql;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.ecommerce.common.enums.StaffRoleEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-DB, real-HTTP proof of the full "generate and send a quote" path: staff JWT →
 * StaffUserEntity resolution → pricing → persistence → AFTER_SUCCESS email → a real,
 * correctly-rendered inbox item. QuoteRequestAdminResourceIT mocks both the repository and
 * the service, so it cannot exercise any of this — see QuoteRequestStatusUpdateSessionIT's
 * own header comment for why that matters here specifically (this class extends the same
 * lesson to the new mutation).
 */
@QuarkusTest
@DisplayName("generateAndSendQuote / previewQuoteEmail — real session, real email")
class QuoteGenerationIT
{
    @Inject
    EntityManager em;

    @Inject
    MockMailbox mailbox;

    private final List<UUID> quoteRequestIds = new ArrayList<>();
    private final List<UUID> staffIds = new ArrayList<>();

    private record SeededQuote(UUID quoteId, UUID item1Id, UUID item2Id) {}

    private SeededQuote seedQuote(String customerEmail, QuoteRequestStatusEn status)
    {
        return QuarkusTransaction.requiringNew().call(() -> {
            QuoteRequestEntity request = new QuoteRequestEntity();
            request.setName("IT Customer");
            request.setEmail(customerEmail);
            request.setStatus(status);
            request.setCreatedAt(Instant.now());

            QuoteRequestItemEntity item1 = new QuoteRequestItemEntity();
            item1.setQuoteRequest(request);
            item1.setProductNameSnapshot("Widget Pro");
            item1.setVariantSkuSnapshot("WID-001");
            item1.setQuantity(2);

            QuoteRequestItemEntity item2 = new QuoteRequestItemEntity();
            item2.setQuoteRequest(request);
            item2.setProductNameSnapshot("Gadget Max");
            item2.setVariantSkuSnapshot("GAD-002");
            item2.setQuantity(1);

            request.setItems(new ArrayList<>(List.of(item1, item2)));
            QuoteRequestEntity.persist(request);

            quoteRequestIds.add(request.getId());
            return new SeededQuote(request.getId(), item1.getId(), item2.getId());
        });
    }

    private String seedStaffAndGetJwt(String role)
    {
        String staffEmail = "quote-it-staff-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID staffId = QuarkusTransaction.requiringNew().call(() -> {
            StaffUserEntity staff = new StaffUserEntity();
            staff.setEmail(staffEmail);
            staff.setPasswordHash("not-a-real-hash");
            staff.setFullName("IT Staff Member");
            staff.setRole(StaffRoleEn.valueOf(role));
            StaffUserEntity.persist(staff);
            return staff.getId();
        });
        staffIds.add(staffId);

        return Jwt.subject(staffEmail)
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID id : quoteRequestIds) {
            em.createQuery("delete from QuoteRequestEntity q where q.id = :id").setParameter("id", id).executeUpdate();
        }
        for (UUID id : staffIds) {
            em.createQuery("delete from StaffUserEntity s where s.id = :id").setParameter("id", id).executeUpdate();
        }
        mailbox.clear();
    }

    private String itemPricesJson(UUID item1Id, String price1, UUID item2Id, String price2)
    {
        return "[{itemId: \\\"" + item1Id + "\\\", unitPrice: " + price1 + "}, "
                + "{itemId: \\\"" + item2Id + "\\\", unitPrice: " + price2 + "}]";
    }

    @Test
    @DisplayName("generates, persists, and emails a real itemized quote")
    void generateAndSendQuote_realPath()
    {
        String customerEmail = "quote-it-customer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        SeededQuote seeded = seedQuote(customerEmail, QuoteRequestStatusEn.NEW);
        String jwt = seedStaffAndGetJwt("SUPER_ADMIN");

        String mutation = "{\"query\":\"mutation { generateAndSendQuote(id: \\\"" + seeded.quoteId()
                + "\\\", items: " + itemPricesJson(seeded.item1Id(), "10.00", seeded.item2Id(), "25.50")
                + ", notes: \\\"Valid 14 days, excludes delivery\\\") "
                + "{ id status quotedAmount quotedNotes quotedByName items { id unitPrice lineTotal } } }\"}";

        given()
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .body(mutation)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.generateAndSendQuote.status", equalTo("QUOTE_SENT"))
                // 2*10.00 + 1*25.50 = 45.50
                .body("data.generateAndSendQuote.quotedAmount", equalTo(45.50f))
                .body("data.generateAndSendQuote.quotedNotes", equalTo("Valid 14 days, excludes delivery"))
                .body("data.generateAndSendQuote.quotedByName", equalTo("IT Staff Member"));

        // DB truth, read back independently of the GraphQL response.
        QuoteRequestEntity reloaded = QuarkusTransaction.requiringNew().call(() -> {
            QuoteRequestEntity r = QuoteRequestEntity.findById(seeded.quoteId());
            r.getItems().size(); // force-init within this transaction
            return r;
        });
        assertEquals(QuoteRequestStatusEn.QUOTE_SENT, reloaded.getStatus());
        assertEquals(0, new BigDecimal("45.50").compareTo(reloaded.getQuotedAmount()));
        assertNotNull(reloaded.getQuotedBy());
        assertNotNull(reloaded.getStatusChangedAt());

        // The actual customer-facing email, not just the DB write.
        List<Mail> mails = mailbox.getMailsSentTo(customerEmail);
        assertEquals(1, mails.size(), "exactly one quote email should be sent");
        String html = mails.get(0).getHtml();
        assertTrue(html.contains("Widget Pro"), "must list the first item");
        assertTrue(html.contains("Gadget Max"), "must list the second item");
        assertTrue(html.contains("45.50"), "must show the computed total");
        assertTrue(html.contains("Valid 14 days"), "must include staff notes");
    }

    @Test
    @DisplayName("preview renders the same content but sends nothing and persists nothing")
    void previewQuoteEmail_rendersWithoutSideEffects()
    {
        String customerEmail = "quote-it-preview-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        SeededQuote seeded = seedQuote(customerEmail, QuoteRequestStatusEn.NEW);
        String jwt = seedStaffAndGetJwt("SUPER_ADMIN");

        String query = "{\"query\":\"{ previewQuoteEmail(id: \\\"" + seeded.quoteId()
                + "\\\", items: " + itemPricesJson(seeded.item1Id(), "10.00", seeded.item2Id(), "25.50")
                + ", notes: \\\"Draft notes\\\") }\"}";

        String html = given()
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .body(query)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .extract().path("data.previewQuoteEmail");

        assertTrue(html.contains("Widget Pro"));
        assertTrue(html.contains("45.50"));
        assertTrue(html.contains("Draft notes"));

        // Nothing sent...
        assertTrue(mailbox.getMailsSentTo(customerEmail).isEmpty(), "preview must not send an email");

        // ...and nothing persisted.
        QuoteRequestEntity reloaded = QuoteRequestEntity.findById(seeded.quoteId());
        assertEquals(QuoteRequestStatusEn.NEW, reloaded.getStatus());
        assertNull(reloaded.getQuotedAmount());
        assertNull(reloaded.getQuotedBy());
    }

    @Test
    @DisplayName("a CLOSED request cannot be quoted")
    void generateAndSendQuote_closedRequestRejected()
    {
        String customerEmail = "quote-it-closed-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        SeededQuote seeded = seedQuote(customerEmail, QuoteRequestStatusEn.CLOSED);
        String jwt = seedStaffAndGetJwt("SUPER_ADMIN");

        String mutation = "{\"query\":\"mutation { generateAndSendQuote(id: \\\"" + seeded.quoteId()
                + "\\\", items: " + itemPricesJson(seeded.item1Id(), "10.00", seeded.item2Id(), "25.50")
                + ", notes: null) { id status } }\"}";

        given()
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .body(mutation)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", containsString("Invalid status transition"));

        assertTrue(mailbox.getMailsSentTo(customerEmail).isEmpty());
    }

    @Test
    @DisplayName("missing a price for one item is rejected and nothing is persisted or sent")
    void generateAndSendQuote_missingPriceRejected()
    {
        String customerEmail = "quote-it-partial-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        SeededQuote seeded = seedQuote(customerEmail, QuoteRequestStatusEn.NEW);
        String jwt = seedStaffAndGetJwt("ORDER_MANAGER");

        String mutation = "{\"query\":\"mutation { generateAndSendQuote(id: \\\"" + seeded.quoteId()
                + "\\\", items: [{itemId: \\\"" + seeded.item1Id() + "\\\", unitPrice: 10.00}], notes: null) "
                + "{ id status } }\"}";

        given()
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .body(mutation)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()));

        QuoteRequestEntity reloaded = QuoteRequestEntity.findById(seeded.quoteId());
        assertEquals(QuoteRequestStatusEn.NEW, reloaded.getStatus());
        assertTrue(mailbox.getMailsSentTo(customerEmail).isEmpty());
    }

    @Test
    @DisplayName("VIEWER is forbidden from generating a quote")
    void generateAndSendQuote_viewerForbidden()
    {
        SeededQuote seeded = seedQuote("quote-it-viewer@example.com", QuoteRequestStatusEn.NEW);
        String jwt = seedStaffAndGetJwt("VIEWER");

        String mutation = "{\"query\":\"mutation { generateAndSendQuote(id: \\\"" + seeded.quoteId()
                + "\\\", items: " + itemPricesJson(seeded.item1Id(), "10.00", seeded.item2Id(), "25.50")
                + ", notes: null) { id status } }\"}";

        given()
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .body(mutation)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }
}
