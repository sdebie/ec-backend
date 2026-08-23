package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.service.payfast.PayFastService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Rate limiting on {@code POST /api/payments/checkout} (S4, guest-order-authorization
 * Requirement 7.2) — modelled on {@link OrderCheckoutRateLimitIT}. This endpoint had no
 * ceiling of any kind before this spec — the {@code checkout} limiter on
 * {@code POST /api/orders} covers order *creation*, not payment initiation.
 */
@QuarkusTest
@DisplayName("PaymentCheckoutRateLimitIT")
class PaymentCheckoutRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    PayFastService payFastService;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class, OrderStatusHistoryEntity.class);
        when(rateLimiterService.enforce(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(null);
        when(payFastService.generateHiddenHTMLForm(any(), anyString())).thenReturn(List.of());
    }

    private OrderEntity order(UUID orderId)
    {
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setContactEmail("shopper@example.com");
        return order;
    }

    @Test
    @DisplayName("a denied caller gets 429 + Retry-After before any order state changes")
    void deniedCaller_returns429AndDoesNotTransition()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findById(orderId)).thenReturn(order(orderId));
        when(rateLimiterService.enforce(eq("payment-checkout"), anyString(), anyInt(), anyLong()))
                .thenReturn(Response.status(429).header("Retry-After", 600L).build());

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.50")
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(429)
                .header("Retry-After", "600");

        PanacheMock.verify(OrderEntity.class, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("the limiter is keyed on the proxy-appended IP, not a client-supplied prefix")
    void limiterIsKeyedOnResolvedClientIp()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findById(orderId)).thenReturn(order(orderId));
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.90")
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202);

        org.mockito.Mockito.verify(rateLimiterService).enforce(eq("payment-checkout"), eq("203.0.113.90"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("an allowed caller initiates payment normally")
    void allowedCaller_initiatesNormally()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findById(orderId)).thenReturn(order(orderId));
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.51")
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202);
    }
}
