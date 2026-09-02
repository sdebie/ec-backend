package org.ecommerce.backend.api.graphql;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.backend.service.QuoteRequestService;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.ecommerce.common.repository.QuoteRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration test for QuoteRequestAdminResource.
 * <p>
 * Tests:
 * - Full role matrix (anonymous + all four staff roles on all four operations)
 * - Transition validation via the updateQuoteRequestStatus mutation
 * - Variant-deleted detail still resolves from snapshots
 * <p>
 */
@QuarkusTest
@DisplayName("QuoteRequestAdminResource — integration tests")
class QuoteRequestAdminResourceIT
{
    @InjectMock
    QuoteRequestRepository quoteRequestRepository;

    @InjectMock
    QuoteRequestService quoteRequestService;

    private UUID testRequestId;

    // ─── JWT Helpers ─────────────────────────────────────────────────────────

    private String generateStaffJwt(String role)
    {
        return Jwt.subject("staff-" + role.toLowerCase() + "@test.com")
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    // ─── GraphQL Body Builders ──────────────────────────────────────────────

    private static final String ALL_QUOTE_REQUESTS_BODY =
            "{\"query\":\"{ allQuoteRequests { id name company itemCount createdAt status } }\"}";

    private static final String QUOTE_REQUEST_COUNT_BODY =
            "{\"query\":\"{ quoteRequestCount }\"}";

    private String quoteRequestDetailBody(UUID id)
    {
        return "{\"query\":\"{ quoteRequest(id: \\\"" + id + "\\\") { id name email phone company message createdAt status statusChangedAt items { variantId productNameSnapshot variantSkuSnapshot quantity } } }\"}";
    }

    private String updateStatusBody(UUID id, String status)
    {
        return "{\"query\":\"mutation { updateQuoteRequestStatus(id: \\\"" + id + "\\\", status: \\\"" + status + "\\\") { id status statusChangedAt } }\"}";
    }

    // ─── Test Data Setup ────────────────────────────────────────────────────

    @BeforeEach
    void setUp()
    {
        testRequestId = UUID.randomUUID();
        QuoteRequestEntity testEntity = createTestEntity(testRequestId, QuoteRequestStatusEn.NEW);

        // Repository mocks
        when(quoteRequestRepository.findAll(any(), any())).thenReturn(List.of(testEntity));
        when(quoteRequestRepository.count(any())).thenReturn(1L);
        when(quoteRequestRepository.findById(any(UUID.class))).thenReturn(testEntity);

        // Service mocks for valid transitions — updateStatus returns the DTO directly (it maps
        // inside its own @Transactional scope; see QuoteRequestService.updateStatus's javadoc),
        // so these mocks return QuoteRequestDetailsDto, not the entity.
        QuoteRequestDetailsDto inProgressDto = createTestDetailsDto(testRequestId, QuoteRequestStatusEn.IN_PROGRESS);
        inProgressDto.setStatusChangedAt(Instant.parse("2026-07-21T12:00:00Z"));
        when(quoteRequestService.updateStatus(any(UUID.class), eq(QuoteRequestStatusEn.IN_PROGRESS))).thenReturn(inProgressDto);

        QuoteRequestDetailsDto canceledDto = createTestDetailsDto(testRequestId, QuoteRequestStatusEn.CANCELED);
        canceledDto.setStatusChangedAt(Instant.parse("2026-07-21T13:00:00Z"));
        when(quoteRequestService.updateStatus(any(UUID.class), eq(QuoteRequestStatusEn.CANCELED))).thenReturn(canceledDto);

        // Invalid transition
        when(quoteRequestService.updateStatus(any(UUID.class), eq(QuoteRequestStatusEn.NEW))).thenThrow(new InvalidQuoteStatusTransitionException(QuoteRequestStatusEn.IN_PROGRESS, QuoteRequestStatusEn.NEW));
    }

    private QuoteRequestEntity createTestEntity(UUID id, QuoteRequestStatusEn status)
    {
        QuoteRequestEntity entity = new QuoteRequestEntity();
        entity.setId(id);
        entity.setName("John Doe");
        entity.setEmail("john@example.com");
        entity.setPhone("+27123456789");
        entity.setCompany("Acme Corp");
        entity.setMessage("Need bulk pricing");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse("2026-07-21T10:00:00Z"));
        entity.setStatusChangedAt(null);

        QuoteRequestItemEntity item1 = new QuoteRequestItemEntity();
        item1.setId(UUID.randomUUID());
        item1.setQuoteRequest(entity);
        item1.setVariant(createMockVariant());
        item1.setProductNameSnapshot("Widget Pro");
        item1.setVariantSkuSnapshot("WP-001");
        item1.setQuantity(10);

        QuoteRequestItemEntity item2 = new QuoteRequestItemEntity();
        item2.setId(UUID.randomUUID());
        item2.setQuoteRequest(entity);
        item2.setVariant(createMockVariant());
        item2.setProductNameSnapshot("Gadget Plus");
        item2.setVariantSkuSnapshot("GP-002");
        item2.setQuantity(5);

        entity.setItems(new ArrayList<>(List.of(item1, item2)));
        return entity;
    }

    private ProductVariantEntity createMockVariant()
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());
        return variant;
    }

    /** Mirrors createTestEntity's shape — the mocked return value of updateStatus, now a DTO. */
    private QuoteRequestDetailsDto createTestDetailsDto(UUID id, QuoteRequestStatusEn status)
    {
        QuoteRequestDetailsDto dto = new QuoteRequestDetailsDto();
        dto.setId(id);
        dto.setName("John Doe");
        dto.setEmail("john@example.com");
        dto.setPhone("+27123456789");
        dto.setCompany("Acme Corp");
        dto.setMessage("Need bulk pricing");
        dto.setStatus(status);
        dto.setCreatedAt(Instant.parse("2026-07-21T10:00:00Z"));
        dto.setItems(new ArrayList<>());
        return dto;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // allQuoteRequests — role matrix
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("allQuoteRequests — SUPER_ADMIN succeeds")
    void allQuoteRequests_superAdmin_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(ALL_QUOTE_REQUESTS_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.allQuoteRequests", hasSize(1))
                .body("data.allQuoteRequests[0].name", equalTo("John Doe"))
                .body("data.allQuoteRequests[0].company", equalTo("Acme Corp"))
                .body("data.allQuoteRequests[0].itemCount", equalTo(2))
                .body("data.allQuoteRequests[0].status", equalTo("NEW"));
    }

    @Test
    @DisplayName("allQuoteRequests — ORDER_MANAGER succeeds")
    void allQuoteRequests_orderManager_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("ORDER_MANAGER"))
                .contentType("application/json")
                .body(ALL_QUOTE_REQUESTS_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.allQuoteRequests", hasSize(1));
    }

    @Test
    @DisplayName("allQuoteRequests — VIEWER succeeds")
    void allQuoteRequests_viewer_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("VIEWER"))
                .contentType("application/json")
                .body(ALL_QUOTE_REQUESTS_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.allQuoteRequests", hasSize(1));
    }

    @Test
    @DisplayName("allQuoteRequests — CATALOG_MANAGER forbidden")
    void allQuoteRequests_catalogManager_forbidden()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("CATALOG_MANAGER"))
                .contentType("application/json")
                .body(ALL_QUOTE_REQUESTS_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    @DisplayName("allQuoteRequests — anonymous unauthorized")
    void allQuoteRequests_anonymous_unauthorized()
    {
        given()
                .contentType("application/json")
                .body(ALL_QUOTE_REQUESTS_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // quoteRequestCount — role matrix
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("quoteRequestCount — SUPER_ADMIN succeeds")
    void quoteRequestCount_superAdmin_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(QUOTE_REQUEST_COUNT_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequestCount", equalTo(1));
    }

    @Test
    @DisplayName("quoteRequestCount — ORDER_MANAGER succeeds")
    void quoteRequestCount_orderManager_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("ORDER_MANAGER"))
                .contentType("application/json")
                .body(QUOTE_REQUEST_COUNT_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequestCount", equalTo(1));
    }

    @Test
    @DisplayName("quoteRequestCount — VIEWER succeeds")
    void quoteRequestCount_viewer_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("VIEWER"))
                .contentType("application/json")
                .body(QUOTE_REQUEST_COUNT_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequestCount", equalTo(1));
    }

    @Test
    @DisplayName("quoteRequestCount — CATALOG_MANAGER forbidden")
    void quoteRequestCount_catalogManager_forbidden()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("CATALOG_MANAGER"))
                .contentType("application/json")
                .body(QUOTE_REQUEST_COUNT_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    @DisplayName("quoteRequestCount — anonymous unauthorized")
    void quoteRequestCount_anonymous_unauthorized()
    {
        given()
                .contentType("application/json")
                .body(QUOTE_REQUEST_COUNT_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // quoteRequest(id) — role matrix
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("quoteRequest — SUPER_ADMIN full detail returned")
    void quoteRequest_superAdmin_fullDetail()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(quoteRequestDetailBody(testRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequest.id", equalTo(testRequestId.toString()))
                .body("data.quoteRequest.name", equalTo("John Doe"))
                .body("data.quoteRequest.email", equalTo("john@example.com"))
                .body("data.quoteRequest.phone", equalTo("+27123456789"))
                .body("data.quoteRequest.company", equalTo("Acme Corp"))
                .body("data.quoteRequest.message", equalTo("Need bulk pricing"))
                .body("data.quoteRequest.status", equalTo("NEW"))
                .body("data.quoteRequest.items", hasSize(2))
                .body("data.quoteRequest.items[0].productNameSnapshot", equalTo("Widget Pro"))
                .body("data.quoteRequest.items[0].variantSkuSnapshot", equalTo("WP-001"))
                .body("data.quoteRequest.items[0].quantity", equalTo(10));
    }

    @Test
    @DisplayName("quoteRequest — ORDER_MANAGER succeeds")
    void quoteRequest_orderManager_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("ORDER_MANAGER"))
                .contentType("application/json")
                .body(quoteRequestDetailBody(testRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequest.id", equalTo(testRequestId.toString()));
    }

    @Test
    @DisplayName("quoteRequest — VIEWER succeeds")
    void quoteRequest_viewer_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("VIEWER"))
                .contentType("application/json")
                .body(quoteRequestDetailBody(testRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequest.id", equalTo(testRequestId.toString()));
    }

    @Test
    @DisplayName("quoteRequest — CATALOG_MANAGER forbidden")
    void quoteRequest_catalogManager_forbidden()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("CATALOG_MANAGER"))
                .contentType("application/json")
                .body(quoteRequestDetailBody(testRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    @DisplayName("quoteRequest — anonymous unauthorized")
    void quoteRequest_anonymous_unauthorized()
    {
        given()
                .contentType("application/json")
                .body(quoteRequestDetailBody(testRequestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // updateQuoteRequestStatus — role matrix
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("updateQuoteRequestStatus — SUPER_ADMIN succeeds")
    void updateStatus_superAdmin_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.updateQuoteRequestStatus.id", equalTo(testRequestId.toString()))
                .body("data.updateQuoteRequestStatus.status", equalTo("IN_PROGRESS"));
    }

    @Test
    @DisplayName("updateQuoteRequestStatus — ORDER_MANAGER succeeds")
    void updateStatus_orderManager_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("ORDER_MANAGER"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.updateQuoteRequestStatus.id", equalTo(testRequestId.toString()))
                .body("data.updateQuoteRequestStatus.status", equalTo("IN_PROGRESS"));
    }

    @Test
    @DisplayName("updateQuoteRequestStatus — VIEWER forbidden")
    void updateStatus_viewer_forbidden()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("VIEWER"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    @DisplayName("updateQuoteRequestStatus — CATALOG_MANAGER forbidden")
    void updateStatus_catalogManager_forbidden()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("CATALOG_MANAGER"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    @DisplayName("updateQuoteRequestStatus — anonymous unauthorized")
    void updateStatus_anonymous_unauthorized()
    {
        given()
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("unauthorized"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Transition Validation via API
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Transition NEW → IN_PROGRESS succeeds")
    void transition_newToInProgress_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "IN_PROGRESS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.updateQuoteRequestStatus.status", equalTo("IN_PROGRESS"));
    }

    @Test
    @DisplayName("Transition NEW → CANCELED succeeds")
    void transition_newToCanceled_succeeds()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "CANCELED"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.updateQuoteRequestStatus.status", equalTo("CANCELED"));
    }

    @Test
    @DisplayName("QUOTE_DRAFTED as a target via updateQuoteRequestStatus returns error — must go through saveQuoteDraft")
    void transition_toQuoteDrafted_returnsError()
    {
        when(quoteRequestService.updateStatus(any(UUID.class), eq(QuoteRequestStatusEn.QUOTE_DRAFTED)))
                .thenThrow(new IllegalArgumentException("Use saveQuoteDraft to move a request to QUOTE_DRAFTED"));

        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "QUOTE_DRAFTED"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", containsString("saveQuoteDraft"));
    }

    @Test
    @DisplayName("Invalid transition returns error")
    void transition_invalid_returnsError()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "NEW"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", containsString("Invalid status transition"));
    }

    @Test
    @DisplayName("Invalid status string returns error")
    void transition_invalidStatusString_returnsError()
    {
        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(updateStatusBody(testRequestId, "INVALID_STATUS"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", containsString("Invalid status"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Variant-deleted detail still resolves from snapshots
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Detail with deleted variant renders snapshot fields with null variantId")
    void variantDeleted_rendersFromSnapshot()
    {
        UUID requestId = UUID.randomUUID();
        QuoteRequestEntity entity = new QuoteRequestEntity();
        entity.setId(requestId);
        entity.setName("Jane Doe");
        entity.setEmail("jane@example.com");
        entity.setPhone(null);
        entity.setCompany("Deleted Inc");
        entity.setMessage(null);
        entity.setStatus(QuoteRequestStatusEn.IN_PROGRESS);
        entity.setCreatedAt(Instant.parse("2026-07-20T09:00:00Z"));
        entity.setStatusChangedAt(Instant.parse("2026-07-21T11:00:00Z"));

        // Item with deleted variant (variant = null, ON DELETE SET NULL)
        QuoteRequestItemEntity deletedItem = new QuoteRequestItemEntity();
        deletedItem.setId(UUID.randomUUID());
        deletedItem.setQuoteRequest(entity);
        deletedItem.setVariant(null); // variant was deleted
        deletedItem.setProductNameSnapshot("Discontinued Product");
        deletedItem.setVariantSkuSnapshot("DISC-999");
        deletedItem.setQuantity(3);

        entity.setItems(new ArrayList<>(List.of(deletedItem)));

        when(quoteRequestRepository.findById(requestId)).thenReturn(entity);

        given()
                .header("Authorization", "Bearer " + generateStaffJwt("SUPER_ADMIN"))
                .contentType("application/json")
                .body(quoteRequestDetailBody(requestId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.quoteRequest.id", equalTo(requestId.toString()))
                .body("data.quoteRequest.name", equalTo("Jane Doe"))
                .body("data.quoteRequest.items", hasSize(1))
                .body("data.quoteRequest.items[0].variantId", nullValue())
                .body("data.quoteRequest.items[0].productNameSnapshot", equalTo("Discontinued Product"))
                .body("data.quoteRequest.items[0].variantSkuSnapshot", equalTo("DISC-999"))
                .body("data.quoteRequest.items[0].quantity", equalTo(3));
    }
}
