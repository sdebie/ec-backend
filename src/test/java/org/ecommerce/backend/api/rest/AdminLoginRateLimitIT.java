package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.AdminAuthService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.dto.LoginRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for admin login rate limiting on {@code POST /api/admin/auth/login}.
 * <p>
 * The rate limiter is mocked to control denial/allowance independently of wall-clock time.
 * The auth service is mocked so credential evaluation is irrelevant — we are testing
 * that rate limit responses fire correctly and that credentials are NOT evaluated when denied.
 * <p>
 * Validates: Requirements 4.3, 4.4, 9.2
 */
@QuarkusTest
@DisplayName("AdminLoginRateLimitIT — admin login rate limiting")
class AdminLoginRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    AdminAuthService adminAuthService;

    @BeforeEach
    void setUp()
    {
        // Default: allow all requests
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong())).thenReturn(new RateLimitDecision(true, 0));
        // Default: authentication returns null (invalid credentials)
        when(adminAuthService.authenticate(any(LoginRequestDto.class))).thenReturn(null);
    }

    private String loginPayload(String email, String password)
    {
        return """
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password);
    }

    // ── IP rate limit exceeded → 429 + Retry-After (Req 4.3, 4.4, 9.2) ─────

    @Test
    @DisplayName("exceeding IP rate limit returns 429 with Retry-After header")
    void ipLimitExceeded_returns429WithRetryAfter()
    {
        when(rateLimiterService.check(eq("admin-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 120));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.50")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(429)
                .header("Retry-After", "120");

        // Credentials SHALL NOT be evaluated when denied (Req 4.4)
        verify(adminAuthService, never()).authenticate(any(LoginRequestDto.class));
    }

    @Test
    @DisplayName("IP denial does NOT increment the email limiter counter")
    void ipDenied_emailLimiterNotConsulted()
    {
        when(rateLimiterService.check(eq("admin-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 60));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.50")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(429);

        // Email limiter should never have been consulted
        verify(rateLimiterService, never()).check(eq("admin-login-email"), anyString(), anyInt(), anyLong());
    }

    // ── Email rate limit exceeded → 429 + Retry-After (Req 4.3, 4.4, 9.2) ──

    @Test
    @DisplayName("exceeding email rate limit returns 429 with Retry-After header")
    void emailLimitExceeded_returns429WithRetryAfter()
    {
        // IP passes
        when(rateLimiterService.check(eq("admin-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
        // Email denied
        when(rateLimiterService.check(eq("admin-login-email"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 45));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.51")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(429)
                .header("Retry-After", "45");

        // Credentials SHALL NOT be evaluated when denied (Req 4.4)
        verify(adminAuthService, never()).authenticate(any(LoginRequestDto.class));
    }

    // ── Email normalisation: lowercased + trimmed (Req 7.4) ─────────────────

    @Test
    @DisplayName("email key is normalised to lowercase and trimmed for the email limiter")
    void emailKeyNormalisedLowercaseTrimmed()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.52")
                .body(loginPayload("  Admin@Test.COM  ", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401); // normal auth failure (mocked to return null)

        // Verify the email limiter was called with normalised key
        verify(rateLimiterService).check(eq("admin-login-email"), eq("admin@test.com"), anyInt(), anyLong());
    }

    // ── Recovery after window (Req 9.2) ─────────────────────────────────────

    @Test
    @DisplayName("request succeeds after rate limit window expires (simulated via mock)")
    void recoveryAfterWindow_requestSucceeds()
    {
        // First: denied
        when(rateLimiterService.check(eq("admin-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 2));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.53")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(429);

        // After window: allowed again
        when(rateLimiterService.check(eq("admin-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
        when(rateLimiterService.check(eq("admin-login-email"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.53")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401); // Normal auth failure (not 429)
    }

    // ── X-Forwarded-For resolution (Req 7.1) ───────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For LAST entry (proxy-appended) is the resolved client IP for the IP limiter")
    void xForwardedForLastEntryUsedForIpLimiter()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.99, 10.0.0.1")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("admin-login"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("rotating spoofed X-Forwarded-For prefixes all resolve to the same (appended) IP")
    void spoofedXffPrefixes_allResolveToAppendedIp()
    {
        for (String spoofed : new String[]{"6.6.6.1", "6.6.6.2", "6.6.6.3"}) {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", spoofed + ", 10.0.0.1")
                    .body(loginPayload("admin@test.com", "password123"))
                    .when()
                    .post("/api/admin/auth/login")
                    .then()
                    .statusCode(401);
        }

        verify(rateLimiterService, times(3)).check(eq("admin-login"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("CF-Connecting-IP takes precedence over X-Forwarded-For for the IP limiter")
    void cfConnectingIpTakesPrecedence()
    {
        given()
                .contentType(ContentType.JSON)
                .header("CF-Connecting-IP", "203.0.113.7")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.1")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("admin-login"), eq("203.0.113.7"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("falls back to X-Real-IP when X-Forwarded-For is absent")
    void xRealIpFallback()
    {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.42")
                .body(loginPayload("admin@test.com", "password123"))
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("admin-login"), eq("198.51.100.42"), anyInt(), anyLong());
    }

    // ── Body validation (400) stays ahead of limiter ────────────────────────

    @Test
    @DisplayName("missing email/password returns 400 without consulting the rate limiter")
    void missingBody_returns400_noLimiterConsulted()
    {
        String emptyPayload = """
                {
                    "email": "",
                    "password": ""
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.54")
                .body(emptyPayload)
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(400);

        // Rate limiter should not have been consulted for an invalid body
        verify(rateLimiterService, never()).check(anyString(), anyString(), anyInt(), anyLong());
    }
}
