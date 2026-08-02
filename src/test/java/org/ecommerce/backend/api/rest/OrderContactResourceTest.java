package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class OrderContactResourceTest
{
    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
        PanacheMock.mock(ShippingMethodEntity.class);
    }

    @Test
    void updateContact_validBody_returns200()
    {
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.PENDING);

        ShippingMethodEntity shippingMethod = new ShippingMethodEntity();
        shippingMethod.setId(shippingMethodId);
        shippingMethod.setName("Standard Delivery");
        shippingMethod.setActive(true);
        shippingMethod.setBaseFee(new BigDecimal("89.00"));

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
    void updateContact_invalidOrderId_returns404()
    {
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
    void updateContact_invalidShippingMethodId_returns422()
    {
        UUID orderId = UUID.randomUUID();
        UUID invalidShippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.PENDING);

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
    void updateContact_selectingShippingMethod_repricesTotalToThatMethodsFee()
    {
        // The order is created before any delivery method exists, so its total
        // carries the DEFAULT estimate. Selecting a method must move the total to
        // that method's fee — otherwise PayFast charges without the delivery.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("115.00")); // stale: created with the default estimate
        order.setStatus(OrderStatusEn.PENDING);
        order.getItems().add(orderItem(new BigDecimal("100.00"), 2));

        ShippingMethodEntity express = new ShippingMethodEntity();
        express.setId(shippingMethodId);
        express.setName("Express Overnight");
        express.setActive(true);
        express.setBaseFee(new BigDecimal("250.00"));

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(express);

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

        // 200 subtotal + 30 VAT (15%) + 250 delivery = 480
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("subtotal", comparesEqualTo(200.00f))
                .body("vatAmount", comparesEqualTo(30.00f))
                .body("shippingEstimate", comparesEqualTo(250.00f))
                .body("grandTotal", comparesEqualTo(480.00f))
                .body("totalAmount", comparesEqualTo(480.00f));

        // and the new total is written back to the order the gateway will charge
        assertEquals(0, order.getTotalAmount().compareTo(new BigDecimal("480.00")));
    }

    @Test
    void updateContact_repricedSubtotalComesFromStoredLinePrices()
    {
        // Repricing must never re-price a product — it rebuilds the subtotal from
        // the line prices the server already set for the shopper's tier.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("1.00"));
        order.setStatus(OrderStatusEn.PENDING);
        order.getItems().add(orderItem(new BigDecimal("19.99"), 3));
        order.getItems().add(orderItem(new BigDecimal("5.50"), 2));

        ShippingMethodEntity collection = new ShippingMethodEntity();
        collection.setId(shippingMethodId);
        collection.setName("In-Store Pickup");
        collection.setActive(true);
        collection.setBaseFee(BigDecimal.ZERO);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(collection);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s"
                }
                """.formatted(shippingMethodId);

        // (19.99 × 3) + (5.50 × 2) = 70.97; VAT 10.65 (half-up); collection is free
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("subtotal", comparesEqualTo(70.97f))
                .body("vatAmount", comparesEqualTo(10.65f))
                .body("shippingEstimate", comparesEqualTo(0.00f))
                .body("grandTotal", comparesEqualTo(81.62f));
    }

    private static OrderItemEntity orderItem(BigDecimal unitPrice, int quantity)
    {
        OrderItemEntity item = new OrderItemEntity();
        item.setUnitPrice(unitPrice);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void updateContact_guestOrderNoCustomer_returns200()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setStatus(OrderStatusEn.PENDING);
        order.setCustomerEntity(null); // Guest order — no customer

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
