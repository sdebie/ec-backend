package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Rate limiting on {@code PATCH /api/orders/{orderId}/contact} (S3, guest-order-authorization
 * Requirement 7.1) — modelled on {@link OrderCheckoutRateLimitIT}. This endpoint had no
 * ceiling of any kind before this spec.
 */
@QuarkusTest
@DisplayName("OrderContactRateLimitIT")
class OrderContactRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    OrderRepository orderRepository;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(org.ecommerce.common.entity.ShippingMethodEntity.class);
        when(rateLimiterService.enforce(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(null);
    }

    private OrderEntity order(UUID orderId)
    {
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);
        return order;
    }

    private String body()
    {
        return """
                { "email": "shopper@example.com", "firstName": "Sam", "lastName": "Shopper" }
                """;
    }

    @Test
    @DisplayName("a denied caller gets 429 + Retry-After and nothing is written")
    void deniedCaller_returns429AndDoesNotMutate()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId);
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order);
        when(rateLimiterService.enforce(eq("order-contact"), anyString(), anyInt(), anyLong()))
                .thenReturn(Response.status(429).header("Retry-After", 900L).build());

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.40")
                .contentType(ContentType.JSON)
                .body(body())
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(429)
                .header("Retry-After", "900");

        org.junit.jupiter.api.Assertions.assertNull(order.getContactEmail());
    }

    @Test
    @DisplayName("the limiter is keyed on the proxy-appended IP, not a client-supplied prefix")
    void limiterIsKeyedOnResolvedClientIp()
    {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order(orderId));

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.80")
                .contentType(ContentType.JSON)
                .body(body())
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200);

        org.mockito.Mockito.verify(rateLimiterService).enforce(eq("order-contact"), eq("203.0.113.80"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("an allowed caller updates contact normally")
    void allowedCaller_updatesNormally()
    {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order(orderId));

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.41")
                .contentType(ContentType.JSON)
                .body(body())
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200);
    }
}
