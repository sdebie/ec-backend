package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.common.repository.OrderStatusHistoryRepository;
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

    @InjectMock
    OrderRepository orderRepository;

    @InjectMock
    OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Inject
    OrderCapabilityService orderCapability;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
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

    /**
     * Every case that means to reach past the ownership gate needs a real, valid
     * capability token for that exact order id (guest-order-authorization) — the
     * suite runs with {@code order.capability.enforce=true} and no {@code %test}
     * override, so an anonymous call is refused before any of these tests' own
     * business rule is ever reached.
     */
    private io.restassured.response.Response confirmWithToken(UUID orderId)
    {
        return given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .when()
                .post("/api/orders/" + orderId + "/in-store-payment");
    }

    @Test
    @DisplayName("confirms a collection order, moving it out of the sweep's reach and emailing the shopper")
    void collectionOrder_isConfirmed()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatusEn.CREATED, false);
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order);
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        confirmWithToken(orderId).then()
                .statusCode(200)
                .body("status", equalTo("IN_STORE_PAYMENT"));

        verify(orderStatusHistoryRepository)
                .record(any(), any(), anyString(), anyString());
        verify(orderNotificationService, times(1)).sendStatusNotification(order, OrderStatusEn.IN_STORE_PAYMENT);
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
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order(orderId, OrderStatusEn.CREATED, true));

        confirmWithToken(orderId).then().statusCode(422);

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
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
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order);

        confirmWithToken(orderId).then().statusCode(422);

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
        when(orderRepository.findOrderInfoById(orderId))
                .thenReturn(order(orderId, OrderStatusEn.IN_STORE_PAYMENT, false));

        confirmWithToken(orderId).then()
                .statusCode(200)
                .body("status", equalTo("IN_STORE_PAYMENT"));

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
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
        when(orderRepository.findOrderInfoById(orderId))
                .thenReturn(order(orderId, OrderStatusEn.SYSTEM_CANCELED, false));

        confirmWithToken(orderId).then().statusCode(409);

        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }

    @Test
    @DisplayName("reports an unknown order as not found")
    void unknownOrder_returns404()
    {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(null);

        confirm(orderId).then().statusCode(404);
    }

    /**
     * The negative case that had never existed anywhere in the suite (design.md §5a,
     * task 0.4): an anonymous caller — no credential of any kind — confirming an
     * order that belongs to a registered customer. Today {@code mayAccess} bypasses
     * for any caller without the "customer" role, so this currently succeeds; it must
     * not.
     */
    @Test
    @DisplayName("refuses an anonymous caller confirming a registered customer's order (Requirement 1.1/1.2)")
    void anonymousCaller_customerOwnedOrder_returnsOrderNotFound()
    {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatusEn.CREATED, false);
        CustomerEntity owner = new CustomerEntity();
        owner.setId(UUID.randomUUID());
        order.setCustomerEntity(owner);
        when(orderRepository.findOrderInfoById(orderId)).thenReturn(order);
        // Stubbed so that, while the guard is bypassed, the request reaches a genuine
        // 200 rather than a false-negative 409 from PanacheMock's unstubbed-update
        // default of 0 rows affected — the failure this test must show is the
        // authorization bypass, not a mocking artifact (tasks.md Notes).
        when(OrderEntity.update(anyString(), any(Object[].class))).thenReturn(1);

        confirm(orderId).then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));

        PanacheMock.verify(OrderEntity.class, never()).update(anyString(), any(Object[].class));
        verify(orderNotificationService, never()).sendStatusNotification(any(), any());
    }
}
