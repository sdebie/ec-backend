package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.payfast.HtmlFormField;
import org.ecommerce.backend.service.payfast.PayFastService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class PayFastResourceTest {

    @InjectMock
    PayFastService payFastService;

    @InjectMock
    OrderNotificationService orderNotificationService;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(OrderEntity.class);
    }

    @Test
    void checkout_withEmailParam_returns202WithGatewayUrl() {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.id = orderId;
        order.totalAmount = new BigDecimal("1000.00");
        order.status = OrderStatusEn.PENDING;
        order.customerEntity = null; // Guest order

        when(OrderEntity.findById(orderId)).thenReturn(order);

        List<HtmlFormField> mockFields = List.of(
                new HtmlFormField("merchant_id", "hidden", "10000100"),
                new HtmlFormField("amount", "hidden", "1000.00")
        );
        when(payFastService.generateHiddenHTMLForm(any(OrderEntity.class), eq("guest@example.com")))
                .thenReturn(mockFields);

        given()
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .formParam("email", "guest@example.com")
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202)
                .body("gatewayUrl", equalTo("https://sandbox.payfast.co.za/eng/process"))
                .body("fields", hasSize(2))
                .body("fields[0].name", equalTo("merchant_id"))
                .body("fields[0].value", equalTo("10000100"));
    }

    @Test
    void checkout_withoutEmailAndNoCustomer_returns400() {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.id = orderId;
        order.totalAmount = new BigDecimal("500.00");
        order.status = OrderStatusEn.PENDING;
        order.customerEntity = null; // No customer — guest with no email

        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(400)
                .body("error", equalTo("Email is required"));
    }

    @Test
    void itn_withInvalidSignature_returns401AndDoesNotTouchOrder() {
        when(payFastService.verifyItnSignature(anyString())).thenReturn(false);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + UUID.randomUUID() + "&payment_status=COMPLETE&amount_gross=100.00&signature=forged")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(401);

        verify(orderNotificationService, never()).sendConfirmationEmail(any());
    }

    @Test
    void itn_withValidSignatureAndCompleteStatus_marksOrderPaidAndNotifies() {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = spy(new OrderEntity());
        order.id = orderId;
        order.totalAmount = new BigDecimal("100.00");
        order.status = OrderStatusEn.PENDING;
        doNothing().when(order).persist();

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&pf_payment_id=999&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(200);

        assertEquals(OrderStatusEn.PAID, order.status);
        verify(orderNotificationService).sendConfirmationEmail(order);
    }
}
