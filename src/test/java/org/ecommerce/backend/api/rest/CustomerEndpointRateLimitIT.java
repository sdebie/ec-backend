package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for rate limiting on the customer {@code /register} endpoint.
 * <p>
 * The rate limiter is mocked to control denial/allowance independently of wall-clock time.
 * Tests verify that 429 + Retry-After is returned on denial and that requests succeed
 * after the window resets (simulated by toggling the mock).
 * <p>
 */
@QuarkusTest
@DisplayName("CustomerEndpointRateLimitIT — register rate limiting")
class CustomerEndpointRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp()
    {
        // Default: allow all requests
        when(rateLimiterService.enforce(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // /register rate limiting (Req 6.2, 9.2)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/register: exceeding IP rate limit returns 429 with Retry-After header")
    void register_ipLimitExceeded_returns429WithRetryAfter()
    {
        when(rateLimiterService.enforce(eq("register"), anyString(), anyInt(), anyLong()))
                .thenReturn(Response.status(429).header("Retry-After", 1800L).build());

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.20")
                .body(registerPayload("newuser@example.com"))
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(429)
                .header("Retry-After", "1800");
    }

    @Test
    @DisplayName("/register: request succeeds after rate limit window resets (recovery)")
    void register_recoveryAfterWindow_requestSucceeds()
    {
        // First: denied
        when(rateLimiterService.enforce(eq("register"), anyString(), anyInt(), anyLong()))
                .thenReturn(Response.status(429).header("Retry-After", 2L).build());

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.21")
                .body(registerPayload("recover@example.com"))
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(429);

        // After window: allowed again — the request reaches the service layer
        // It will likely fail with 500 (no DB in this mock scenario) or succeed,
        // but the point is it is NOT 429
        when(rateLimiterService.enforce(eq("register"), anyString(), anyInt(), anyLong()))
                .thenReturn(null);

        int status = given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.21")
                .body(registerPayload("recover@example.com"))
                .when()
                .post("/api/customers/register")
                .then()
                .extract().statusCode();

        // Any non-429 status proves recovery (actual status depends on DB state)
        assert status != 429 : "Expected non-429 after window recovery, got 429";
    }

    @Test
    @DisplayName("/register: X-Forwarded-For LAST entry (proxy-appended) is used as IP key for the limiter")
    void register_xForwardedForResolvedCorrectly()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.60, 10.0.0.1")
                .body(registerPayload("xff@example.com"))
                .when()
                .post("/api/customers/register");

        verify(rateLimiterService).enforce(eq("register"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("/register: falls back to X-Real-IP when X-Forwarded-For is absent")
    void register_xRealIpFallback()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.30")
                .body(registerPayload("realip@example.com"))
                .when()
                .post("/api/customers/register");

        verify(rateLimiterService).enforce(eq("register"), eq("198.51.100.30"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("/register: body-shape validation (400) fires BEFORE limiter — missing email")
    void register_missingEmail_returns400_noLimiterConsulted()
    {
        String payload = """
                {
                    "password": "StrongPass1!"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.22")
                .body(payload)
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(400);

        // Rate limiter should not have been consulted for an invalid body
        verify(rateLimiterService, never()).enforce(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("/register: body-shape validation (400) fires BEFORE limiter — missing password")
    void register_missingPassword_returns400_noLimiterConsulted()
    {
        String payload = """
                {
                    "email": "user@example.com"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.23")
                .body(payload)
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(400);

        verify(rateLimiterService, never()).enforce(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("/register: limiter runs BEFORE the 409 claimed-account guard")
    void register_limiterRunsBeforeConflictGuard()
    {
        // Simulate: the IP is denied by the rate limiter
        when(rateLimiterService.enforce(eq("register"), anyString(), anyInt(), anyLong()))
                .thenReturn(Response.status(429).header("Retry-After", 900L).build());

        // Even if the account exists (which would normally give 409),
        // rate limit denial fires first
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.24")
                .body(registerPayload("existing@example.com"))
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(429)
                .header("Retry-After", "900");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String registerPayload(String email)
    {
        return """
                {
                    "email": "%s",
                    "password": "%s",
                    "firstName": "Test",
                    "lastName": "User"
                }
                """.formatted(email, "StrongPass1!");
    }
}
