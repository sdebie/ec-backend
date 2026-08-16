package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rate limiting on {@code GET /api/countries} — modelled on {@link OrderContactRateLimitIT}.
 * This endpoint proxies a live, paid REST Countries API call and had no rate limit and no
 * auth of any kind before this fix, so any caller could spend the configured API key at
 * unlimited volume.
 * <p>
 * Every case here stays on the DENIAL path deliberately. The backend's real
 * {@code restcountries.api.key} is a genuine, live key loaded from {@code .env} even under
 * the test profile (config precedence: env vars outrank {@code %test.*} properties), so a
 * permissive mock decision would let the resource fall through to a real, billable upstream
 * call. Asserting only the denial path is not a compromise: it is exactly the property this
 * fix must guarantee — a rate-limited caller never reaches the key check or the outbound
 * call, which is what "spends a live paid API key" actually means.
 */
@QuarkusTest
@DisplayName("CountriesRateLimitIT")
class CountriesRateLimitIT {

    @InjectMock
    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 900));
    }

    @Test
    @DisplayName("a denied caller gets 429 + Retry-After, never reaching the upstream call")
    void deniedCaller_returns429() {
        given()
                .header("X-Forwarded-For", "192.0.2.50")
                .when()
                .get("/api/countries")
                .then()
                .statusCode(429)
                .header("Retry-After", "900");
    }

    @Test
    @DisplayName("the limiter is keyed on the resolved client IP, not a client-supplied XFF prefix")
    void limiterIsKeyedOnResolvedClientIp() {
        given()
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.90")
                .when()
                .get("/api/countries")
                .then()
                .statusCode(429);

        verify(rateLimiterService).check(eq("countries"), eq("203.0.113.90"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("CF-Connecting-IP takes precedence over X-Forwarded-For for the limiter key")
    void cfConnectingIpTakesPrecedence() {
        given()
                .header("CF-Connecting-IP", "203.0.113.91")
                .header("X-Forwarded-For", "198.51.100.1")
                .when()
                .get("/api/countries")
                .then()
                .statusCode(429);

        verify(rateLimiterService).check(eq("countries"), eq("203.0.113.91"), anyInt(), anyLong());
    }
}
