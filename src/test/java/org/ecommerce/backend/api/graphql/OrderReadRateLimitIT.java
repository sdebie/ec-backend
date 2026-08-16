package org.ecommerce.backend.api.graphql;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.entity.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Rate limiting on the GraphQL read surfaces S1 ({@code getOrderDetail}) and S2′
 * ({@code orderStatus}) — guest-order-authorization Requirement 7.2a, sized off
 * {@code usePollOrderStatus}'s real polling rate (every 3s for up to 120s — up to 40
 * requests from one ordinary checkout).
 * <p>
 * GraphQL has no per-query HTTP status code, so a denial here is a distinct GraphQL
 * error (200 + {@code errors}) rather than a raw 429 — the same shape every other
 * refusal on this resolver already takes.
 */
@QuarkusTest
@DisplayName("OrderReadRateLimitIT")
class OrderReadRateLimitIT
{
    @InjectMock
    RateLimiterService rateLimiterService;

    @InjectMock
    OrderService orderService;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
        when(rateLimiterService.check(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 0));
    }

    private String getOrderDetailQuery(UUID orderId)
    {
        return """
                {"query": "{ getOrderDetail(id: \\"%s\\") { id } }"}
                """.formatted(orderId);
    }

    private String orderStatusQuery(UUID orderId)
    {
        return """
                {"query": "{ orderStatus(orderId: \\"%s\\") { id } }"}
                """.formatted(orderId);
    }

    @Test
    @DisplayName("getOrderDetail: a denied caller gets a distinct GraphQL error, not order data")
    void getOrderDetail_deniedCaller_returnsRateLimitError()
    {
        UUID orderId = UUID.randomUUID();
        when(rateLimiterService.check(eq("order-read"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 300));

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.60")
                .contentType("application/json")
                .body(getOrderDetailQuery(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Too many requests"))
                .body("data.getOrderDetail", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("orderStatus: a denied caller gets a distinct GraphQL error, not order data")
    void orderStatus_deniedCaller_returnsRateLimitError()
    {
        UUID orderId = UUID.randomUUID();
        when(rateLimiterService.check(eq("order-read"), anyString(), anyInt(), anyLong()))
                .thenReturn(new RateLimitDecision(false, 300));

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "192.0.2.61")
                .contentType("application/json")
                .body(orderStatusQuery(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Too many requests"))
                .body("data.orderStatus", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("the limiter is keyed on the proxy-appended IP, not a client-supplied prefix")
    void limiterIsKeyedOnResolvedClientIp()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        when(OrderEntity.findById(orderId)).thenReturn(order);
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.95")
                .contentType("application/json")
                .body(getOrderDetailQuery(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", org.hamcrest.Matchers.nullValue());

        org.mockito.Mockito.verify(rateLimiterService).check(eq("order-read"), eq("203.0.113.95"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("an allowed caller reads order detail normally")
    void allowedCaller_readsNormally()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        when(OrderEntity.findById(orderId)).thenReturn(order);
        OrderDetailRespDto detail = new OrderDetailRespDto();
        detail.setId(orderId);
        when(orderService.getOrderDetail(orderId)).thenReturn(detail);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType("application/json")
                .body(getOrderDetailQuery(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", org.hamcrest.Matchers.nullValue())
                .body("data.getOrderDetail.id", equalTo(orderId.toString()));
    }
}
