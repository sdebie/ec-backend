package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.backend.service.ContactEnquiryMailer;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link ContactEnquiryResource} driving the real REST endpoint
 * via REST-assured. The mailer and rate-limiter are mocked at the CDI bean level so we
 * can verify mail invocation without SMTP and control rate-limit behaviour.
 * <p>
 * Validates: Requirements 2.1, 2.4, 2.5, 3.1, 3.2, 3.3, 1.3, 1.4
 */
@QuarkusTest
@DisplayName("ContactEnquiryResource — integration tests")
class ContactEnquiryResourceTest
{
    @InjectMock
    ContactEnquiryMailer contactEnquiryMailer;

    @InjectMock
    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp()
    {
        // Default: allow all requests (under rate limit)
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong())).thenReturn(new RateLimitDecision(true, 0));
        // Default: mailer does nothing (no throw = success)
        doNothing().when(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));
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
                    "message": "I'd like to enquire about your services."
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
                    "message": "I'd like to enquire about your services.",
                    "website": "%s"
                }
                """.formatted(honeypotValue);
    }

    // ── Valid submission: 202 + mail invoked (Req 2.1, 2.4, 1.3) ────────────

    @Test
    @DisplayName("valid payload returns 202 and invokes mailer")
    void validSubmission_returns202_invokesMailer()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verify(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));
    }

    @Test
    @DisplayName("valid payload with empty honeypot returns 202 and invokes mailer")
    void validSubmission_emptyHoneypot_returns202_invokesMailer()
    {
        String payload = """
                {
                    "name": "John Smith",
                    "email": "john@example.com",
                    "phone": "+27821234567",
                    "message": "Hello, I have a question.",
                    "website": ""
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verify(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));
    }

    // ── Invalid payload: 422 validation error + no mail (Req 3.1, 2.5) ─────
    // The resource validates explicitly and returns 422 (Unprocessable Entity) for a
    // well-formed-but-invalid body, per its contract — without a global constraint-
    // violation mapper that would change 400 behaviour on other endpoints.

    @Test
    @DisplayName("missing required name field returns 422 and does not invoke mailer")
    void missingName_returns422_noMail()
    {
        String payload = """
                {
                    "email": "jane@visitor.com",
                    "phone": "0821234567",
                    "message": "Hello"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(422);

        verifyNoInteractions(contactEnquiryMailer);
    }

    @Test
    @DisplayName("missing required email field returns 422 and does not invoke mailer")
    void missingEmail_returns422_noMail()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "phone": "0821234567",
                    "message": "Hello"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(422);

        verifyNoInteractions(contactEnquiryMailer);
    }

    @Test
    @DisplayName("invalid email format returns 422 and does not invoke mailer")
    void invalidEmailFormat_returns422_noMail()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "not-an-email",
                    "phone": "0821234567",
                    "message": "Hello"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(422);

        verifyNoInteractions(contactEnquiryMailer);
    }

    @Test
    @DisplayName("missing required phone field returns 422 and does not invoke mailer")
    void missingPhone_returns422_noMail()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "message": "Hello"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(422);

        verifyNoInteractions(contactEnquiryMailer);
    }

    @Test
    @DisplayName("missing required message field returns 422 and does not invoke mailer")
    void missingMessage_returns422_noMail()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "phone": "0821234567"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(422);

        verifyNoInteractions(contactEnquiryMailer);
    }

    // ── Honeypot filled: 202 + no mail (Req 3.2) ───────────────────────────

    @Test
    @DisplayName("honeypot field filled returns 202 but does NOT invoke mailer")
    void honeypotFilled_returns202_noMail()
    {
        given()
                .contentType(ContentType.JSON)
                .body(validPayloadWithHoneypot("http://spam-site.com"))
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verifyNoInteractions(contactEnquiryMailer);
    }

    @Test
    @DisplayName("honeypot field with whitespace only is treated as empty (not bot)")
    void honeypotWhitespaceOnly_returns202_invokesMailer()
    {
        String payload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@visitor.com",
                    "phone": "0821234567",
                    "message": "Hello, I need help.",
                    "website": "   "
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verify(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));
    }

    // ── Rate limit exceeded: 429 (Req 3.3) ──────────────────────────────────

    @Test
    @DisplayName("rate limit exceeded returns 429 and does not invoke mailer")
    void rateLimitExceeded_returns429_noMail()
    {
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 3500));

        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(429)
                .header("Retry-After", "3500");

        verifyNoInteractions(contactEnquiryMailer);
    }

    // ── No recipient configured: 503 (Req 1.4) ─────────────────────────────

    @Test
    @DisplayName("no recipient configured returns 503 Service Unavailable")
    void noRecipientConfigured_returns503()
    {
        doThrow(new RecipientNotConfiguredException("enquiryEmail is absent"))
                .when(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));

        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(503);
    }

    // ── Recipient never taken from payload (Req 1.3) ────────────────────────

    @Test
    @DisplayName("payload cannot influence recipient — extra fields are ignored")
    void recipientNotFromPayload_extraFieldsIgnored()
    {
        // Even if someone tries to inject a "recipientEmail" or "to" field,
        // the endpoint ignores it — only server-resolved enquiryEmail is used
        String payloadWithExtraRecipientField = """
                {
                    "name": "Attacker",
                    "email": "attacker@evil.com",
                    "phone": "0821234567",
                    "message": "Trying to redirect email",
                    "recipientEmail": "victim@example.com",
                    "to": "victim@example.com"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payloadWithExtraRecipientField)
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        // Mailer is invoked with the DTO (which has no recipient field) — recipient
        // is resolved server-side inside the mailer from storefront.contact config
        verify(contactEnquiryMailer).send(any(ContactEnquiryRequestDto.class));
    }

    // ── Endpoint is public (no auth required) (Req 2.1) ─────────────────────

    @Test
    @DisplayName("endpoint is accessible without authentication token")
    void endpointIsPublic_noAuthRequired()
    {
        // No Authorization header or JWT — should still work (public endpoint)
        given()
                .contentType(ContentType.JSON)
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);
    }

    // ── X-Forwarded-For header is passed to rate limiter (Req 3.3) ──────────

    @Test
    @DisplayName("X-Forwarded-For LAST entry (proxy-appended) is the resolved client IP passed to the rate limiter")
    void xForwardedForUsedForRateLimiting()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1")
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        // Proves XFF parsing at the resource layer: the LAST (proxy-appended) hop —
        // never the client-controlled first entry — is what reaches the rate limiter.
        verify(rateLimiterService).check(eq("enquiry"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("CF-Connecting-IP takes precedence over X-Forwarded-For for the rate limiter")
    void cfConnectingIpTakesPrecedence()
    {
        given()
                .contentType(ContentType.JSON)
                .header("CF-Connecting-IP", "203.0.113.9")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.1")
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verify(rateLimiterService).check(eq("enquiry"), eq("203.0.113.9"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("falls back to X-Real-IP when X-Forwarded-For is absent")
    void xRealIpUsedWhenNoForwardedFor()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.7")
                .body(validPayload())
                .when()
                .post("/api/storefront/enquiries")
                .then()
                .statusCode(202);

        verify(rateLimiterService).check(eq("enquiry"), eq("198.51.100.7"), anyInt(), anyLong());
    }
}
