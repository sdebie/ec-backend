package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-layer tests for the NEW {@code resolveCustomer()} glue in {@link OrderResource#createOrder}.
 * {@link OrderService} is mocked entirely, so these tests isolate customer resolution
 * from checkout/pricing/stock logic (covered separately by
 * {@code OrderServiceCreateOrderFromCartIT}).
 */
@QuarkusTest
class OrderResourceCreateOrderTest
{
    @InjectMock
    OrderService orderService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);

        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(UUID.randomUUID().toString());
        response.setSessionId(UUID.randomUUID().toString());
        when(orderService.createOrderFromCart(any(), any(), any(), any(), any())).thenReturn(response);
    }

    private String generateCustomerJwt(String email)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
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
    @DisplayName("no Authorization header -> createOrderFromCart is invoked with customer = null")
    void createOrder_noAuthorizationHeader_resolvesNullCustomer()
    {
        given()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201);

        ArgumentCaptor<CustomerEntity> captor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(orderService).createOrderFromCart(any(), any(), captor.capture(), any(), any());
        assertNull(captor.getValue());
    }

    @Test
    @DisplayName("valid customer JWT with a matching CustomerEntity -> invoked with that exact customer")
    void createOrder_validCustomerJwtWithMatchingCustomer_resolvesThatCustomer()
    {
        String email = "alice@test.com";
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);

        given()
                .header("Authorization", "Bearer " + generateCustomerJwt(email))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201);

        ArgumentCaptor<CustomerEntity> captor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(orderService).createOrderFromCart(any(), any(), captor.capture(), any(), any());
        assertNotNull(captor.getValue(), "expected a resolved customer, got null");
        assertEquals(customer.getId(), captor.getValue().getId());
    }

    @Test
    @DisplayName("valid customer JWT but no matching CustomerEntity row -> 401, createOrderFromCart never invoked")
    void createOrder_validCustomerJwtButNoMatchingCustomerRow_returnsUnauthorized()
    {
        // A "customer" role with no matching row is not the same thing as no
        // role at all, and must not collapse into a silent guest checkout —
        // mirrors getOrderDetail/myOrders, which both throw on exactly this.
        String email = "ghost@test.com";
        when(CustomerEntity.findByEmail(email)).thenReturn(null);

        given()
                .header("Authorization", "Bearer " + generateCustomerJwt(email))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                .body(validOrderBody())
                .when()
                .post("/api/orders")
                .then()
                .statusCode(401)
                .body("error", equalTo("Unauthorized"));

        verify(orderService, never()).createOrderFromCart(any(), any(), any(), any(), any());
    }
}
