package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.repository.ShippingMethodRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorefrontShippingResourceTest
{
    @InjectMock
    ShippingMethodRepository shippingMethodRepository;

    @Test
    void getActiveShippingMethods_returnsOnlyActiveMethods()
    {
        ShippingMethodEntity delivery = new ShippingMethodEntity();
        delivery.setId(UUID.randomUUID());
        delivery.setName("Standard Delivery");
        delivery.setActive(true);
        delivery.setBaseFee(new BigDecimal("89.00"));
        delivery.setEstimatedDays("3-5");

        ShippingMethodEntity collection = new ShippingMethodEntity();
        collection.setId(UUID.randomUUID());
        collection.setName("In-store Collection");
        collection.setActive(true);
        collection.setBaseFee(BigDecimal.ZERO);
        collection.setEstimatedDays(null);

        when(shippingMethodRepository.findAllActive()).thenReturn(List.of(delivery, collection));

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
                .body("[1].estimatedDays", nullValue())
                // Every row came from the isActive=true query, so the published
                // flag must say so — it used to fall through as the Java default.
                .body("[0].active", equalTo(true))
                .body("[1].active", equalTo(true));
    }

    @Test
    void getActiveShippingMethods_returnsEmptyWhenNoneActive()
    {
        when(shippingMethodRepository.findAllActive())
                .thenReturn(Collections.emptyList());

        given()
                .when()
                .get("/api/storefront/shipping-methods")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }
}
