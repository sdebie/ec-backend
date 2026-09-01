package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.payfast.HtmlFormField;
import org.ecommerce.backend.service.payfast.PayFastService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.PaymentLogEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.repository.OrderStatusHistoryRepository;
import org.ecommerce.common.repository.PaymentLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class PayFastResourceTest
{
    @InjectMock
    PayFastService payFastService;

    @InjectMock
    OrderNotificationService orderNotificationService;

    @InjectMock
    OrderStatusHistoryRepository orderStatusHistoryRepository;

    @InjectMock
    PaymentLogRepository paymentLogRepository;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        // PaymentLogEntity is mocked alongside OrderEntity: the ITN handler records
        // the PAID transition on the status timeline (via the injected
        // OrderStatusHistoryRepository mock) AND a payment-gateway log row, and both
        // reference this order. These orders exist only as mocks, so a real persist
        // would fail the row's foreign key to a non-existent order and roll the whole
        // request back.
        PanacheMock.mock(OrderEntity.class, PaymentLogEntity.class);
    }

    @Test
    @DisplayName("a valid token resolves the payer email from the order's own contactEmail, never a caller-supplied parameter (Requirement 8)")
    void checkout_validToken_resolvesEmailFromContactEmail_returns202WithGatewayUrl()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(null); // Guest order
        order.setContactEmail("guest@example.com");

        when(OrderEntity.findById(orderId)).thenReturn(order);
        // Invoking the gateway moves the order to PENDING_PAYMENT through the atomic
        // claim. Unstubbed, the PanacheMock static returns 0 and the claim would "lose".
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        List<HtmlFormField> mockFields = List.of(
                new HtmlFormField("merchant_id", "hidden", "10000100"),
                new HtmlFormField("amount", "hidden", "1000.00")
        );
        when(payFastService.generateHiddenHTMLForm(any(OrderEntity.class), eq("guest@example.com")))
                .thenReturn(mockFields);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202)
                .body("gatewayUrl", equalTo("https://sandbox.payfast.co.za/eng/process"))
                .body("fields", hasSize(2))
                .body("fields[0].name", equalTo("merchant_id"))
                .body("fields[0].value", equalTo("10000100"));
    }

    /**
     * Sabotage-adjacent by construction: a caller-supplied {@code email} form param is
     * sent alongside a DIFFERENT order-held {@code contactEmail}, and the mock only
     * stubs {@code generateHiddenHTMLForm} for the order's own email. If the removed
     * parameter were ever silently honoured again, the unstubbed-mock default would
     * make this fail loudly rather than passing by coincidence.
     */
    @Test
    @DisplayName("a caller-supplied email form param is ignored — the parameter no longer exists on the contract (Requirement 8.2)")
    void checkout_callerSuppliedEmailParam_isIgnored()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(null);
        order.setContactEmail("real-owner@example.com");

        when(OrderEntity.findById(orderId)).thenReturn(order);
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        List<HtmlFormField> mockFields = List.of(new HtmlFormField("merchant_id", "hidden", "10000100"));
        when(payFastService.generateHiddenHTMLForm(any(OrderEntity.class), eq("real-owner@example.com")))
                .thenReturn(mockFields);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .formParam("email", "attacker-controlled@example.com")
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202);

        verify(payFastService).generateHiddenHTMLForm(any(OrderEntity.class), eq("real-owner@example.com"));
        verify(payFastService, never()).generateHiddenHTMLForm(any(OrderEntity.class), eq("attacker-controlled@example.com"));
    }

    @Test
    @DisplayName("falls back to the linked customer's account email when contactEmail is absent")
    void checkout_fallsBackToCustomerEmail_whenContactEmailAbsent()
    {
        UUID orderId = UUID.randomUUID();

        var user = new org.ecommerce.common.entity.UserEntity();
        user.setEmail("customer@example.com");
        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(customer);

        when(OrderEntity.findById(orderId)).thenReturn(order);
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        List<HtmlFormField> mockFields = List.of(new HtmlFormField("merchant_id", "hidden", "10000100"));
        when(payFastService.generateHiddenHTMLForm(any(OrderEntity.class), eq("customer@example.com")))
                .thenReturn(mockFields);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(202);
    }

    @Test
    void checkout_withoutEmailAndNoCustomer_returns400()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(null); // No customer — guest with no email

        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(400)
                .body("error", equalTo("Email is required"));
    }

    @Test
    @DisplayName("without a token, checkout is refused before the email/status logic ever runs (Requirement 1.1/1.2 — S4 had no guard of any kind before this)")
    void checkout_noToken_returnsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setContactEmail("guest@example.com");

        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(payFastService, never()).generateHiddenHTMLForm(any(), any());
    }

    @Test
    @DisplayName("a malformed order id is a clean 400, not an unmapped 500 (tasks.md 6.3)")
    void checkout_malformedOrderId_returns400()
    {
        given()
                .contentType(ContentType.URLENC)
                .formParam("id", "not-a-uuid")
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(400);
    }

    /**
     * BACKLOG.md payfast-checkout-terminal-order-500. This call site reads the order's
     * live status and passes that same value as applyTransition's own expectedFrom, so
     * the mismatch check (which every other call site relies on for a clean lost-claim
     * 409) can never fire here — it trivially always matches itself. The only remaining
     * gate was canSystemTransitionTo, which legitimately returns false for a terminal
     * order and used to throw straight out of this REST method with nothing to catch it.
     */
    @ParameterizedTest(name = "checkout on a {0} order is a clean 409, not an unmapped 500")
    @CsvSource({"DELIVERED", "SYSTEM_CANCELED", "REFUNDED", "COLLECTED"})
    void checkout_terminalOrderStatus_returns409NotUnmapped500(String status)
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.valueOf(status));
        order.setContactEmail("guest@example.com");

        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType(ContentType.URLENC)
                .formParam("id", orderId.toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .statusCode(409)
                .body("error", equalTo("Order can no longer be paid"));

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(payFastService, never()).generateHiddenHTMLForm(any(), any());
    }

    @Test
    void itn_withInvalidSignature_returns401AndDoesNotTouchOrder()
    {
        when(payFastService.verifyItnSignature(anyString())).thenReturn(false);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + UUID.randomUUID() + "&payment_status=COMPLETE&amount_gross=100.00&signature=forged")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(401);

        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    void itn_withValidSignatureAndCompleteStatus_marksOrderPaidAndNotifies()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        // Where a real order is when its ITN arrives: /payments/checkout moved it here
        // when the gateway was invoked, and PAID is claimed from exactly this status.
        order.setStatus(OrderStatusEn.PENDING_PAYMENT);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
        when(payFastService.confirmWithPayFast(anyString())).thenReturn(true);
        when(OrderEntity.findById(orderId)).thenReturn(order);
        // The atomic claim: 1 row affected means this ITN won the race and may proceed.
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&pf_payment_id=999&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(200);

        assertEquals(OrderStatusEn.PAID, order.getStatus());
        verify(orderNotificationService).sendStatusNotification(order, OrderStatusEn.PAID);
        verify(orderStatusHistoryRepository)
                .record(order, OrderStatusEn.PAID, "Payment confirmed by PayFast", OrderService.SYSTEM_ACTOR);
    }

    @Test
    void itn_fromUntrustedSourceIp_returns401AndDoesNotTouchOrder()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(false);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(401);

        assertEquals(OrderStatusEn.CREATED, order.getStatus());
        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    void itn_withAmountBelowOrderTotal_returns401AndDoesNotMarkOrderPaid()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        // amount_gross (1.00) far under the real order total (500.00) — e.g. a forged
        // ITN for a cheap decoy order replayed against a high-value order ID.
        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&payment_status=COMPLETE&amount_gross=1.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(401);

        assertEquals(OrderStatusEn.CREATED, order.getStatus());
        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(payFastService, never()).confirmWithPayFast(anyString());
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    void itn_whenPayFastServerConfirmationFails_returns401AndDoesNotMarkOrderPaid()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
        when(payFastService.confirmWithPayFast(anyString())).thenReturn(false);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(401);

        assertEquals(OrderStatusEn.CREATED, order.getStatus());
        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    void itn_replayedForAnAlreadyPaidOrder_returns200ButDoesNotReprocess()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.PAID);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
        when(OrderEntity.findById(orderId)).thenReturn(order);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(200);

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(payFastService, never()).confirmWithPayFast(anyString());
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    void itn_whenOrderNoLongerCreatedDueToRace_sendsAnomalyAlertAndDoesNotMarkPaid()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = spy(new OrderEntity());
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        // The abandoned-order release job already claimed this order between the
        // shopper's checkout and PayFast's confirmation arriving.
        order.setStatus(OrderStatusEn.SYSTEM_CANCELED);

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
        when(payFastService.confirmWithPayFast(anyString())).thenReturn(true);
        when(OrderEntity.findById(orderId)).thenReturn(order);
        // The atomic claim loses: the order is no longer CREATED, so 0 rows match.
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(0);

        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&pf_payment_id=999&payment_status=COMPLETE&amount_gross=100.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(200);

        assertEquals(OrderStatusEn.SYSTEM_CANCELED, order.getStatus());
        verify(orderNotificationService).sendPaymentAnomalyAlert(order, new BigDecimal("100.00"));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }
}
