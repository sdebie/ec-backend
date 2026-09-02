package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.CustomerPasswordResetService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for password reset rate limiting.
 * <p>
 * <p>
 * Key assertions:
 * <ul>
 *   <li>REST /password-reset/request: response body on denial is byte-identical to allowed response</li>
 *   <li>PasswordResetNotificationService NOT invoked on denial</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("Password Reset Rate Limiting — integration tests")
class PasswordResetRateLimitIT
{

    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    CustomerPasswordResetService customerPasswordResetService;

    @BeforeEach
    void setUp()
    {
        // Default: allow all requests
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
        // Default: service calls do nothing
        doNothing().when(customerPasswordResetService).initiatePasswordResetCode(anyString());
    }

    // ── REST /password-reset/request ────────────────────────────────────────────

    @Nested
    @DisplayName("REST POST /api/customers/password-reset/request")
    class RestPasswordResetRequest
    {

        private static final String GENERIC_RESPONSE = "If an account exists, a reset code has been sent.";

        @Test
        @DisplayName("allowed request returns generic success and invokes service")
        void allowed_returnsGenericSuccess_invokesService()
        {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \"user@test.com\"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(200)
                    .body(equalTo(GENERIC_RESPONSE));

            verify(customerPasswordResetService).initiatePasswordResetCode("user@test.com");
        }

        @Test
        @DisplayName("IP rate limit denial: response is byte-identical to allowed response, service NOT invoked")
        void ipDenied_responseByteIdentical_serviceNotInvoked()
        {
            // IP limiter denies
            when(rateLimiterService.check(eq("password-reset-request"), anyString(), anyInt(), anyLong()))
                    .thenReturn(new RateLimitDecision(false, 3500));

            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \"user@test.com\"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(200)
                    .body(equalTo(GENERIC_RESPONSE))
                    // No Retry-After header — it would leak the denial (Req 5.2)
                    .header("Retry-After", nullValue());

            verify(customerPasswordResetService, never()).initiatePasswordResetCode(anyString());
        }

        @Test
        @DisplayName("email rate limit denial: response is byte-identical to allowed response, service NOT invoked")
        void emailDenied_responseByteIdentical_serviceNotInvoked()
        {
            // IP limiter allows
            when(rateLimiterService.check(eq("password-reset-request"), anyString(), anyInt(), anyLong()))
                    .thenReturn(new RateLimitDecision(true, 0));
            // Email limiter denies
            when(rateLimiterService.check(eq("password-reset-request-email"), anyString(), anyInt(), anyLong()))
                    .thenReturn(new RateLimitDecision(false, 2000));

            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \"user@test.com\"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(200)
                    .body(equalTo(GENERIC_RESPONSE))
                    .header("Retry-After", nullValue());

            verify(customerPasswordResetService, never()).initiatePasswordResetCode(anyString());
        }

        @Test
        @DisplayName("IP denied short-circuits: email limiter is NOT consulted")
        void ipDenied_emailLimiterNotConsulted()
        {
            when(rateLimiterService.check(eq("password-reset-request"), anyString(), anyInt(), anyLong()))
                    .thenReturn(new RateLimitDecision(false, 3500));

            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \"user@test.com\"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(200);

            // Only the IP limiter was consulted — email limiter call never made
            verify(rateLimiterService, never()).check(eq("password-reset-request-email"), anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("email key is normalised (lowercased and trimmed)")
        void emailKey_normalised()
        {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \" User@Test.COM \"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(200);

            verify(rateLimiterService).check(eq("password-reset-request-email"), eq("user@test.com"), anyInt(), anyLong());
        }

        @Test
        @DisplayName("validation failure (missing email) returns 400 before limiter")
        void missingEmail_returns400_limiterNotConsulted()
        {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "10.0.0.1")
                    .body("{\"email\": \"\"}")
                    .when()
                    .post("/api/customers/password-reset/request")
                    .then()
                    .statusCode(400);

            verify(rateLimiterService, never()).check(anyString(), anyString(), anyInt(), anyLong());
        }
    }
}
