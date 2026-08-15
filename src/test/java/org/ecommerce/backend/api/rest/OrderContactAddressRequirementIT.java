package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A delivery method must arrive with somewhere to deliver to, and a collection method
 * must not demand one.
 * <p>
 * Address presence used to be enforced only by the browser's Zod schema, so any caller
 * that skipped the form could persist a courier order with no address. These drive the
 * real endpoint rather than the schema.
 */
@QuarkusTest
class OrderContactAddressRequirementIT
{
    @Inject
    EntityManager em;

    private UUID orderId;
    private UUID deliveryMethodId;
    private UUID collectionMethodId;

    @BeforeEach
    void seed()
    {
        String marker = "ZZADDR-" + UUID.randomUUID().toString().substring(0, 8);

        QuarkusTransaction.requiringNew().run(() -> {
            ShippingMethodEntity delivery = new ShippingMethodEntity();
            delivery.setName(marker + "-courier");
            delivery.setActive(true);
            delivery.setBaseFee(new BigDecimal("115.00"));
            delivery.setEstimatedDays("2-4 Working Days");
            delivery.setRequiresAddress(true);
            delivery.persist();
            deliveryMethodId = delivery.getId();

            // Free and same-day, exactly the shape the old heuristic misread as delivery.
            ShippingMethodEntity collection = new ShippingMethodEntity();
            collection.setName(marker + "-pickup");
            collection.setActive(true);
            collection.setBaseFee(BigDecimal.ZERO);
            collection.setEstimatedDays("Same Day");
            collection.setRequiresAddress(false);
            collection.persist();
            collectionMethodId = collection.getId();

            OrderEntity order = new OrderEntity();
            order.setSessionId(UUID.randomUUID());
            order.setTotalAmount(new BigDecimal("100.00"));
            order.setStatus(OrderStatusEn.CREATED);
            order.persist();
            orderId = order.getId();
        });
    }

    @AfterEach
    void cleanup()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate();
            em.createQuery("delete from ShippingMethodEntity m where m.id in (:a, :b)")
                    .setParameter("a", deliveryMethodId)
                    .setParameter("b", collectionMethodId)
                    .executeUpdate();
        });
    }

    private Map<String, Object> contactPayload(UUID methodId)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "shopper@example.com");
        body.put("firstName", "Sam");
        body.put("lastName", "Shopper");
        body.put("shippingMethodId", methodId.toString());
        return body;
    }

    private OrderEntity reload()
    {
        return QuarkusTransaction.requiringNew().call(() -> OrderEntity.findById(orderId));
    }

    @Test
    @DisplayName("a collection order is accepted with no address at all")
    void collectionMethod_needsNoAddress()
    {
        given().contentType(ContentType.JSON)
                .body(contactPayload(collectionMethodId))
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(200);

        OrderEntity order = reload();
        assertEquals(collectionMethodId, order.getShippingMethod().getId());
        assertNull(order.getStreetAddress(), "collection must not invent an address");
    }

    @Test
    @DisplayName("a delivery order without an address is rejected and nothing is written")
    void deliveryMethod_withoutAddress_isRejected()
    {
        given().contentType(ContentType.JSON)
                .body(contactPayload(deliveryMethodId))
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(422);

        OrderEntity order = reload();
        assertNull(order.getShippingMethod(), "a rejected request must not persist the method");
        assertNull(order.getContactEmail(), "a rejected request must not persist contact details either");
    }

    @Test
    @DisplayName("a delivery order with a complete address is accepted")
    void deliveryMethod_withAddress_isAccepted()
    {
        Map<String, Object> body = contactPayload(deliveryMethodId);
        body.put("streetAddress", "12 Main Road");
        body.put("city", "Cape Town");
        body.put("province", "Western Cape");
        body.put("postalCode", "8001");

        given().contentType(ContentType.JSON)
                .body(body)
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(200);

        assertEquals("12 Main Road", reload().getStreetAddress());
    }

    @Test
    @DisplayName("a partial address does not satisfy a delivery method")
    void deliveryMethod_withPartialAddress_isRejected()
    {
        Map<String, Object> body = contactPayload(deliveryMethodId);
        body.put("streetAddress", "12 Main Road");
        body.put("city", "Cape Town");

        given().contentType(ContentType.JSON)
                .body(body)
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(422);

        assertNull(reload().getStreetAddress());
    }

    @Test
    @DisplayName("blank strings do not satisfy a delivery method")
    void deliveryMethod_withBlankAddress_isRejected()
    {
        Map<String, Object> body = contactPayload(deliveryMethodId);
        body.put("streetAddress", "   ");
        body.put("city", "Cape Town");
        body.put("province", "Western Cape");
        body.put("postalCode", "8001");

        given().contentType(ContentType.JSON)
                .body(body)
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(422);
    }

    @Test
    @DisplayName("an address already on the order satisfies a later delivery selection")
    void addressFromEarlierCall_satisfiesRequirement()
    {
        // Checkout can supply the address before the method; the requirement is about
        // the order's resulting state, not about one request carrying everything.
        Map<String, Object> first = contactPayload(collectionMethodId);
        first.put("streetAddress", "12 Main Road");
        first.put("city", "Cape Town");
        first.put("province", "Western Cape");
        first.put("postalCode", "8001");

        given().contentType(ContentType.JSON).body(first)
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(200);

        given().contentType(ContentType.JSON).body(contactPayload(deliveryMethodId))
                .when().patch("/api/orders/" + orderId + "/contact")
                .then().statusCode(200);

        assertEquals(deliveryMethodId, reload().getShippingMethod().getId());
    }
}
