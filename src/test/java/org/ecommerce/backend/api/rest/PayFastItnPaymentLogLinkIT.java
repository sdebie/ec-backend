package org.ecommerce.backend.api.rest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.service.payfast.PayFastService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.PaymentLogEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BACKLOG.md payment-logs-never-linked-to-their-order. Real-DB, not PanacheMock:
 * the defect is specifically that the persisted row's order_id FK is never
 * written, which a mocked order (never actually a row in `orders`) can't prove
 * either way — linking a real FK to a fake order would either silently no-op
 * under mocking or violate the real constraint. Only a real persist-and-read-back
 * proves the column itself is set.
 */
@QuarkusTest
class PayFastItnPaymentLogLinkIT
{
    @InjectMock
    PayFastService payFastService;

    @Inject
    EntityManager em;

    private UUID orderId;

    @BeforeEach
    void seedOrder()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            OrderEntity order = new OrderEntity();
            order.setSessionId(UUID.randomUUID());
            order.setStatus(OrderStatusEn.PENDING_PAYMENT);
            order.setTotalAmount(new BigDecimal("250.00"));
            order.persist();
            orderId = order.getId();
        });

        when(payFastService.verifyItnSignature(anyString())).thenReturn(true);
        when(payFastService.isTrustedSource(anyString())).thenReturn(true);
    }

    @AfterEach
    void cleanup()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("delete from PaymentLogEntity l where l.internalReference = :ref")
                    .setParameter("ref", orderId.toString())
                    .executeUpdate();
            em.createQuery("delete from OrderEntity o where o.id = :id")
                    .setParameter("id", orderId)
                    .executeUpdate();
        });
    }

    /**
     * payment_status is deliberately neither COMPLETE nor FAILED — this proves the
     * generic logging step (step 3 in handleITN) links the order on every ITN, not
     * just the ones that also happen to trigger a status transition.
     */
    @Test
    @DisplayName("the persisted payment log carries the order's id, not just a matching internal_reference string")
    void itn_persistsPaymentLog_linkedToTheRealOrder()
    {
        given()
                .contentType(ContentType.URLENC)
                .body("m_payment_id=" + orderId + "&pf_payment_id=999&payment_status=PENDING&amount_gross=250.00&signature=valid")
                .when()
                .post("/api/payments/itn")
                .then()
                .statusCode(200);

        PaymentLogEntity log = PaymentLogEntity.find("internalReference", orderId.toString()).firstResult();

        assertNotNull(log, "expected a payment log row to have been persisted for this ITN");
        assertNotNull(log.getOrderEntity(), "payment_gateway_logs.order_id was left null");
        assertEquals(orderId, log.getOrderEntity().getId());
    }
}
