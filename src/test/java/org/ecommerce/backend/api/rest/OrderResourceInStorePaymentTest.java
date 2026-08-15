package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pay-at-collection confirmation — checkout's counterpart to handing off to
 * PayFast.
 * <p>
 * What makes this endpoint load-bearing rather than cosmetic: an order left at
 * CREATED is indistinguishable from an abandoned cart, and {@code StockRecoveryJob}
 * cancels those. Before it existed the storefront navigated straight to the success
 * page, so a shopper who chose to pay in store was told their order was placed and
 * then had it auto-cancelled half an hour later.
 * <p>
 * {@code OrderEntity.update} is stubbed explicitly on every path that must succeed:
 * under {@link PanacheMock} an unstubbed static returns its type's default, so the
 * atomic status claim would silently report zero rows affected and every success
 * case would pass while proving nothing.
 */
@QuarkusTest
class OrderResourceInStorePaymentTest
{
    @InjectMock
    OrderNotificationService orderNotificationService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class, OrderStatusHistoryEntity.class);
    }

    private OrderEntity order(UUID orderId, OrderStatusEn status, boolean requiresAddress)
    {
        ShippingMethodEntity method = new ShippingMethodEntity();
        method.setId(UUID.randomUUID());
        method.setName(requiresAddress ? "Standard Delivery" : "In-Store Pickup");
        method.setActive(true);
        method.setRequiresAddress(requiresAddress);

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("319.00"));
        order.setShippingMethod(method);
        return order;
    }

    private io.restassured.response.Response confirm(UUID orderId)
    {
        return given().when().post("/api/orders/" + orderId + "/in-store-payment");
    }

    @Test
    @DisplayName("confirms a collection order, moving it out of the sweep's reach and emailing the shopper")
    void collectionOrder_isConfirmed()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatusEn.CREATED, false);
        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        confirm(orderId).then()
                .statusCode(200)
                .body("status", equalTo("IN_STORE_PAYMENT"));

        PanacheMock.verify(OrderStatusHistoryEntity.class)
                .record(any(), any(), anyString(), anyString());
        verify(orderNotificationService, times(1)).sendConfirmationEmail(order);
    }

    /**
     * Nobody is at the counter to take money for an order being couriered. The
     * storefront already withholds the option, but a rule enforced only in the
     * browser is not a rule.
     */
    @Test
    @DisplayName("refuses an order being delivered — paying in store needs somebody collecting")
    void deliveryOrder_isRefused()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order(orderId, OrderStatusEn.CREATED, true));

        confirm(orderId).then().statusCode(422);

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendConfirmationEmail(any());
    }

    /**
     * Fails closed, matching {@code shipping_methods.requires_address DEFAULT TRUE}:
     * a method nobody has classified is treated as a delivery rather than waved through.
     */
    @Test
    @DisplayName("refuses an order with no delivery method chosen yet")
    void orderWithoutShippingMethod_isRefused()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatusEn.CREATED, false);
        order.setShippingMethod(null);
        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        confirm(orderId).then().statusCode(422);

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
    }

    /**
     * A resubmitted checkout must not read as a failure to the shopper whose order
     * was in fact placed — and must not send them a second confirmation email.
     */
    @Test
    @DisplayName("a repeated confirmation succeeds without confirming twice")
    void alreadyConfirmed_isIdempotent()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findOrderInfoById(orderId))
                .thenReturn(order(orderId, OrderStatusEn.IN_STORE_PAYMENT, false));

        confirm(orderId).then()
                .statusCode(200)
                .body("status", equalTo("IN_STORE_PAYMENT"));

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendConfirmationEmail(any());
    }

    /**
     * The sweep can cancel the order between it being loaded and this write. Naming
     * CREATED as the expected status is what turns that into a reported conflict
     * rather than a payment-state overwrite.
     */
    @Test
    @DisplayName("reports a conflict when the order moved on, rather than forcing the status")
    void orderNoLongerCreated_returns409()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findOrderInfoById(orderId))
                .thenReturn(order(orderId, OrderStatusEn.SYSTEM_CANCELED, false));

        confirm(orderId).then().statusCode(409);

        verify(orderNotificationService, never()).sendConfirmationEmail(any());
    }

    @Test
    @DisplayName("reports an unknown order as not found")
    void unknownOrder_returns404()
    {
        UUID orderId = UUID.randomUUID();
        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(null);

        confirm(orderId).then().statusCode(404);
    }
}
