package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

@QuarkusTest
class OrderContactResourceTest {

    @BeforeEach
    void setUp() {
        PanacheMock.mock(OrderEntity.class);
        PanacheMock.mock(ShippingMethodEntity.class);
    }

    @Test
    void updateContact_validBody_returns200() {
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.id = orderId;
        order.totalAmount = new BigDecimal("500.00");
        order.status = OrderStatusEn.PENDING;

        ShippingMethodEntity shippingMethod = new ShippingMethodEntity();
        shippingMethod.id = shippingMethodId;
        shippingMethod.name = "Standard Delivery";
        shippingMethod.isActive = true;
        shippingMethod.baseFee = new BigDecimal("89.00");

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(shippingMethod);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s",
                    "streetAddress": "123 Main St",
                    "city": "Cape Town",
                    "province": "Western Cape",
                    "postalCode": "8001"
                }
                """.formatted(shippingMethodId);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("orderId", equalTo(orderId.toString()))
                .body("contactEmail", equalTo("test@example.com"))
                .body("contactFirstName", equalTo("John"))
                .body("contactLastName", equalTo("Doe"))
                .body("shippingMethodId", equalTo(shippingMethodId.toString()))
                .body("streetAddress", equalTo("123 Main St"));
    }

    @Test
    void updateContact_invalidOrderId_returns404() {
        UUID orderId = UUID.randomUUID();

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(null);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));
    }

    @Test
    void updateContact_invalidShippingMethodId_returns422() {
        UUID orderId = UUID.randomUUID();
        UUID invalidShippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.id = orderId;
        order.totalAmount = new BigDecimal("500.00");
        order.status = OrderStatusEn.PENDING;

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(invalidShippingMethodId)).thenReturn(null);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s"
                }
                """.formatted(invalidShippingMethodId);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422)
                .body("error", equalTo("Shipping method not found or inactive"));
    }

    @Test
    void updateContact_guestOrderNoCustomer_returns200() {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.id = orderId;
        order.totalAmount = new BigDecimal("250.00");
        order.status = OrderStatusEn.PENDING;
        order.customerEntity = null; // Guest order — no customer

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "email": "guest@example.com",
                    "firstName": "Jane",
                    "lastName": "Guest"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("orderId", equalTo(orderId.toString()))
                .body("contactEmail", equalTo("guest@example.com"))
                .body("contactFirstName", equalTo("Jane"))
                .body("contactLastName", equalTo("Guest"));
    }
}
