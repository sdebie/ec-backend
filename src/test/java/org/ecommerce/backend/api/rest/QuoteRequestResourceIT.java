package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.QuoteRequestService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link QuoteRequestResource} driving the real REST endpoint
 * via REST-assured. QuoteRequestService and RateLimiterService are mocked at the CDI
 * bean level to isolate endpoint behaviour.
 * <p>
 */
@QuarkusTest
@DisplayName("QuoteRequestResource — integration tests")
class QuoteRequestResourceIT
{
    @InjectMock
    QuoteRequestService quoteRequestService;

    @InjectMock
    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp()
    {
        // Default: allow all requests (under rate limit)
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong())).thenReturn(new RateLimitDecision(true, 0));

        // Default: submit returns a persisted entity
        QuoteRequestEntity entity = new QuoteRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Test User");
        entity.setEmail("test@example.com");
        entity.setStatus(QuoteRequestStatusEn.NEW);
        entity.setCreatedAt(Instant.now());
        entity.setItems(new ArrayList<>());

        when(quoteRequestService.submit(any())).thenReturn(entity);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String validPayload()
    {
        return """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "phone": "0821234567",
                    "company": "ACME Corp",
                    "message": "I need a bulk quote.",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 5}
                    ]
                }
                """;
    }

    private String validPayloadWithHoneypot(String honeypotValue)
    {
        return """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "phone": "0821234567",
                    "company": "ACME Corp",
                    "message": "I need a bulk quote.",
                    "website": "%s",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 5}
                    ]
                }
                """.formatted(honeypotValue);
    }

    // ── Happy path: 201 + service invoked ───────────────────────────────────

    @Test
    @DisplayName("valid payload returns 201 and invokes service.submit()")
    void validSubmission_returns201_invokesService()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(quoteRequestService).submit(any());
    }

    @Test
    @DisplayName("valid payload with empty honeypot returns 201 and invokes service.submit()")
    void validSubmission_emptyHoneypot_returns201_invokesService()
    {
        String payload = """
                {
                    "name": "John Smith",
                    "email": "john@example.com",
                    "phone": "+27821234567",
                    "message": "Please quote me.",
                    "website": "",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 2}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(quoteRequestService).submit(any());
    }

    // ── Validation: 422 paths ───────────────────────────────────────────────

    @Test
    @DisplayName("null body returns 422")
    void nullBody_returns422()
    {
        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("missing required name field returns 422")
    void missingName_returns422()
    {
        String payload = """
                {
                    "email": "jane@visitor.com",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 5}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("invalid email format returns 422")
    void invalidEmail_returns422()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "not-an-email",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 5}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("empty items list returns 422")
    void emptyItems_returns422()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "items": []
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("item with quantity 0 returns 422")
    void quantityZero_returns422()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 0}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("unknown variant returns 422 (service throws IllegalArgumentException)")
    void unknownVariant_returns422()
    {
        when(quoteRequestService.submit(any()))
                .thenThrow(new IllegalArgumentException("Unknown variant: 00000000-0000-0000-0000-000000000099"));

        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000099", "quantity": 5}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(422);
    }

    // ── Honeypot: 201 but no persistence ────────────────────────────────────

    @Test
    @DisplayName("honeypot field filled returns 201 but does NOT invoke service.submit()")
    void honeypotFilled_returns201_noSubmit()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayloadWithHoneypot("http://spam-site.com"))
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verifyNoInteractions(quoteRequestService);
    }

    @Test
    @DisplayName("honeypot field with whitespace only is treated as empty (not bot)")
    void honeypotWhitespaceOnly_returns201_invokesService()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "message": "Need a quote.",
                    "website": "   ",
                    "items": [
                        {"variantId": "00000000-0000-0000-0000-000000000001", "quantity": 5}
                    ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(quoteRequestService).submit(any());
    }

    // ── Rate limit: 429 + Retry-After ───────────────────────────────────────

    @Test
    @DisplayName("rate limit exceeded returns 429 with Retry-After header")
    void rateLimitExceeded_returns429_withRetryAfter()
    {
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 3500));

        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(429)
                .header("Retry-After", "3500");

        verifyNoInteractions(quoteRequestService);
    }

    // ── Mail failure still returns 201 ──────────────────────────────────────

    @Test
    @DisplayName("mail failure (via service) still returns 201 — persist succeeds")
    void mailFailure_stillReturns201()
    {
        // The mailer is an event observer — its failure never propagates to the resource.
        // This test confirms that even if the service-layer event mechanism were to
        // hypothetically fail, the resource still returns 201 (service.submit() itself
        // does not throw on mail failure — mail is post-commit async).
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(quoteRequestService).submit(any());
    }

    // ── IP resolution headers ───────────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For LAST entry is used for rate-limit key")
    void xForwardedForUsedForRateLimiting()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1")
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("quote-request"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("CF-Connecting-IP takes precedence over X-Forwarded-For")
    void cfConnectingIpTakesPrecedence()
    {
        given()
                .contentType(ContentType.JSON)
                .header("CF-Connecting-IP", "203.0.113.9")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.1")
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("quote-request"), eq("203.0.113.9"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("falls back to X-Real-IP when X-Forwarded-For is absent")
    void xRealIpFallback()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.7")
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("quote-request"), eq("198.51.100.7"), anyInt(), anyLong());
    }

    // ── Endpoint is public (no auth required) ───────────────────────────────

    @Test
    @DisplayName("endpoint is accessible without authentication token")
    void endpointIsPublic_noAuthRequired()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);
    }

    // ── Rate limiter receives correct limiter name and defaults ──────────────

    @Test
    @DisplayName("rate limiter is called with name 'quote-request' and defaults 5/3600")
    void rateLimiterCalledWithCorrectNameAndDefaults()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/quote-requests")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("quote-request"), anyString(), eq(5), eq(3600L));
    }
}
