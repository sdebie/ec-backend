package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorefrontShippingResourceTest {

    @BeforeEach
    void setUp() {
        PanacheMock.mock(ShippingMethodEntity.class);
    }

    @Test
    void getActiveShippingMethods_returnsOnlyActiveMethods() {
        ShippingMethodEntity delivery = new ShippingMethodEntity();
        delivery.id = UUID.randomUUID();
        delivery.name = "Standard Delivery";
        delivery.isActive = true;
        delivery.baseFee = new BigDecimal("89.00");
        delivery.estimatedDays = "3-5";

        ShippingMethodEntity collection = new ShippingMethodEntity();
        collection.id = UUID.randomUUID();
        collection.name = "In-store Collection";
        collection.isActive = true;
        collection.baseFee = BigDecimal.ZERO;
        collection.estimatedDays = null;

        when(ShippingMethodEntity.<ShippingMethodEntity>list("isActive", true))
                .thenReturn(List.of(delivery, collection));

        given()
                .when()
                .get("/api/storefront/shipping-methods")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].name", equalTo("Standard Delivery"))
                .body("[0].baseFee", equalTo(89.00f))
                .body("[0].estimatedDays", equalTo("3-5"))
                .body("[1].name", equalTo("In-store Collection"))
                .body("[1].baseFee", equalTo(0))
                .body("[1].estimatedDays", nullValue());
    }

    @Test
    void getActiveShippingMethods_returnsEmptyWhenNoneActive() {
        when(ShippingMethodEntity.<ShippingMethodEntity>list("isActive", true))
                .thenReturn(Collections.emptyList());

        given()
                .when()
                .get("/api/storefront/shipping-methods")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }
}
