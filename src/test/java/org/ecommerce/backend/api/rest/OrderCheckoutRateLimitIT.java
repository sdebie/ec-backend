package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rate limiting on guest checkout ({@code POST /api/orders}).
 * <p>
 * Checkout is the one unauthenticated write surface that reserves stock, so an
 * unlimited caller can hold a store's inventory hostage: every accepted order
 * decrements stock immediately, and nothing returns it until the hold window
 * passes and {@code StockRecoveryJob} sweeps.
 * <p>
 * {@link RateLimiterService} is mocked so denial is controlled independently of
 * wall-clock time, and {@link OrderService} is mocked so these tests speak only
 * about the limiter and not about pricing or stock.
 */
@QuarkusTest
@DisplayName("OrderCheckoutRateLimitIT — guest checkout rate limiting")
class OrderCheckoutRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    OrderService orderService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);

        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));

        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(UUID.randomUUID().toString());
        response.setSessionId(UUID.randomUUID().toString());
        when(orderService.createOrderFromCart(any(), any(), any(), any(), any())).thenReturn(response);
    }

    /**
     * A fresh header per call: the header is now required (Requirement 2.3) and
     * checked ahead of the rate limiter, and two requests sharing a key would
     * collide on the fast-path idempotency lookup instead of exercising the
     * limiter this suite is about.
     */
    private String freshIdempotencyKey()
    {
        return UUID.randomUUID().toString();
    }

    private String validOrderBody()
    {
        return """
                {
                    "items": [
                        { "variantId": "%s", "quantity": 1 }
                    ]
                }
                """.formatted(UUID.randomUUID());
    }

    @Test
    @DisplayName("a denied caller gets 429 + Retry-After and no order is created")
    void deniedCaller_returns429AndCreatesNoOrder()
    {
        when(rateLimiterService.check(eq("checkout"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 1800));

        given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", freshIdempotencyKey())
                .header("X-Forwarded-For", "192.0.2.30")
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(429)
                .header("Retry-After", "1800");

        verify(orderService, never()).createOrderFromCart(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the limiter is keyed on the proxy-appended IP, not a client-supplied prefix")
    void limiterIsKeyedOnResolvedClientIp()
    {
        // A caller who could pick their own bucket has no rate limit at all: the
        // client controls the front of X-Forwarded-For, the trusted edge appends the
        // real peer at the end.
        given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", freshIdempotencyKey())
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.77")
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("checkout"), eq("203.0.113.77"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("CF-Connecting-IP wins over X-Forwarded-For as the bucket key")
    void cfConnectingIpTakesPrecedence()
    {
        given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", freshIdempotencyKey())
                .header("CF-Connecting-IP", "198.51.100.5")
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.77")
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201);

        verify(rateLimiterService).check(eq("checkout"), eq("198.51.100.5"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("an allowed caller checks out normally")
    void allowedCaller_checksOutNormally()
    {
        given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", freshIdempotencyKey())
                .header("X-Forwarded-For", "192.0.2.31")
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201);

        verify(orderService).createOrderFromCart(any(), any(), any(), any(), any());
    }
}
