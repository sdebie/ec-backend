package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for customer login rate limiting on {@code POST /api/customers/login}
 * and {@code POST /api/customers/login/google}.
 * <p>
 * The rate limiter is mocked to control denial/allowance independently of wall-clock time.
 * Credential evaluation is inline in the resource, so when the limiter allows a request
 * through, the endpoint proceeds to evaluate credentials (returning 401 for non-existent
 * users in the test DB). This proves the limiter correctly short-circuits before credential
 * evaluation when denied.
 * <p>
 */
@QuarkusTest
@DisplayName("CustomerLoginRateLimitIT — customer login + Google login rate limiting")
class CustomerLoginRateLimitIT {

    @InjectMock
    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        // Default: allow all requests
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
    }

    private String loginPayload(String email, String password) {
        return """
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password);
    }

    private String googleLoginPayload(String idToken) {
        return """
                {
                    "idToken": "%s"
                }
                """.formatted(idToken);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: IP rate limit (Req 4.1, 4.4, 9.2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: exceeding IP rate limit returns 429 with Retry-After header")
    void customerLogin_ipLimitExceeded_returns429WithRetryAfter() {
        when(rateLimiterService.check(eq("customer-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 180));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.10")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(429)
                .header("Retry-After", "180");
    }

    @Test
    @DisplayName("customer login: IP denial does NOT increment the email limiter counter")
    void customerLogin_ipDenied_emailLimiterNotConsulted() {
        when(rateLimiterService.check(eq("customer-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 90));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.10")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(429);

        // Email limiter should never have been consulted (chained-check semantics)
        verify(rateLimiterService, never()).check(eq("customer-login-email"), anyString(), anyInt(), anyLong());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: Email rate limit (Req 4.1, 4.4, 9.2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: exceeding email rate limit returns 429 with Retry-After header")
    void customerLogin_emailLimitExceeded_returns429WithRetryAfter() {
        // IP passes
        when(rateLimiterService.check(eq("customer-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
        // Email denied
        when(rateLimiterService.check(eq("customer-login-email"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 60));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.11")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(429)
                .header("Retry-After", "60");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: Email normalisation (Req 7.4)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: email key is normalised to lowercase and trimmed")
    void customerLogin_emailKeyNormalisedLowercaseTrimmed() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.12")
                .body(loginPayload("  Customer@Test.COM  ", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401); // Normal auth failure (non-existent user)

        // Verify email limiter was called with normalised key
        verify(rateLimiterService).check(eq("customer-login-email"), eq("customer@test.com"), anyInt(), anyLong());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: Recovery after window (Req 9.2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: recovery after rate limit window expires")
    void customerLogin_recoveryAfterWindow() {
        // First: denied
        when(rateLimiterService.check(eq("customer-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 2));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.13")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(429);

        // After window: allowed again
        when(rateLimiterService.check(eq("customer-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
        when(rateLimiterService.check(eq("customer-login-email"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.13")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401); // Normal auth failure (not 429)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: X-Forwarded-For / X-Real-IP resolution (Req 7.1)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: X-Forwarded-For LAST entry (proxy-appended) is the resolved IP for the limiter")
    void customerLogin_xForwardedForLastEntryUsedForIpLimiter() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("customer-login"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("customer login: CF-Connecting-IP takes precedence over X-Forwarded-For")
    void customerLogin_cfConnectingIpTakesPrecedence() {
        given()
                .contentType(ContentType.JSON)
                .header("CF-Connecting-IP", "203.0.113.8")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.1")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("customer-login"), eq("203.0.113.8"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("customer login: falls back to X-Real-IP when X-Forwarded-For is absent")
    void customerLogin_xRealIpFallback() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.7")
                .body(loginPayload("customer@test.com", "password123"))
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401);

        verify(rateLimiterService).check(eq("customer-login"), eq("198.51.100.7"), anyInt(), anyLong());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Customer login: Body validation stays ahead of limiter
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("customer login: missing email/password returns 400 without consulting rate limiter")
    void customerLogin_missingBody_returns400_noLimiterConsulted() {
        String payload = """
                {
                    "email": null,
                    "password": null
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.14")
                .body(payload)
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(400);

        // Rate limiter should NOT have been consulted for an invalid body
        verify(rateLimiterService, never()).check(anyString(), anyString(), anyInt(), anyLong());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Google login: IP rate limit (Req 4.2, 4.4, 9.2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Google login: exceeding IP rate limit returns 429 with Retry-After header")
    void googleLogin_ipLimitExceeded_returns429WithRetryAfter() {
        when(rateLimiterService.check(eq("google-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 300));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.20")
                .body(googleLoginPayload("some-fake-id-token"))
                .when()
                .post("/api/customers/login/google")
                .then()
                .statusCode(429)
                .header("Retry-After", "300");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Google login: X-Forwarded-For resolution (Req 7.1)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Google login: X-Forwarded-For LAST entry (proxy-appended) is the resolved IP for the limiter")
    void googleLogin_xForwardedForLastEntryUsedForIpLimiter() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "203.0.113.55, 10.0.0.1")
                .body(googleLoginPayload("some-fake-id-token"))
                .when()
                .post("/api/customers/login/google")
                .then()
                // Will be 500 (Google verification fails with fake token) or 401 — not 429
                .statusCode(anyOf(is(500), is(401)));

        verify(rateLimiterService).check(eq("google-login"), eq("10.0.0.1"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Google login: falls back to X-Real-IP when X-Forwarded-For is absent")
    void googleLogin_xRealIpFallback() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Real-IP", "198.51.100.99")
                .body(googleLoginPayload("some-fake-id-token"))
                .when()
                .post("/api/customers/login/google")
                .then()
                .statusCode(anyOf(is(500), is(401)));

        verify(rateLimiterService).check(eq("google-login"), eq("198.51.100.99"), anyInt(), anyLong());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Google login: Recovery after window (Req 9.2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Google login: recovery after rate limit window expires")
    void googleLogin_recoveryAfterWindow() {
        // First: denied
        when(rateLimiterService.check(eq("google-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 2));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.21")
                .body(googleLoginPayload("some-fake-id-token"))
                .when()
                .post("/api/customers/login/google")
                .then()
                .statusCode(429);

        // After window: allowed again
        when(rateLimiterService.check(eq("google-login"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.21")
                .body(googleLoginPayload("some-fake-id-token"))
                .when()
                .post("/api/customers/login/google")
                .then()
                // Not 429 — passes through to credential evaluation (which fails with fake token)
                .statusCode(anyOf(is(500), is(401)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Google login: Body validation stays ahead of limiter
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Google login: missing idToken returns 400 without consulting rate limiter")
    void googleLogin_missingIdToken_returns400_noLimiterConsulted() {
        String payload = """
                {
                    "idToken": ""
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", "192.0.2.22")
                .body(payload)
                .when()
                .post("/api/customers/login/google")
                .then()
                .statusCode(400);

        // Rate limiter should NOT have been consulted for an invalid body
        verify(rateLimiterService, never()).check(anyString(), anyString(), anyInt(), anyLong());
    }
}
