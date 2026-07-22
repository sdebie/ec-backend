package org.ecommerce.backend.api.rest;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorefrontPaymentResourceTest
{
    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(StoreSettingsEntity.class);
    }

    @SuppressWarnings("unchecked")
    private void mockFindByKey(StoreSettingsEntity result)
    {
        PanacheQuery<PanacheEntityBase> query = Mockito.mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        when(StoreSettingsEntity.find(eq("key"), any(Object[].class))).thenReturn(query);
    }

    @Test
    void getAllowedPaymentMethods_returnsParsedArray()
    {
        StoreSettingsEntity setting = new StoreSettingsEntity();
        setting.setKey("payment_methods_allowed");
        setting.setValue("[\"PAYFAST\", \"IN_STORE\"]");

        mockFindByKey(setting);

        given()
                .when()
                .get("/api/storefront/payment-methods")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0]", equalTo("PAYFAST"))
                .body("[1]", equalTo("IN_STORE"));
    }

    @Test
    void getAllowedPaymentMethods_returnsEmptyWhenSettingAbsent()
    {
        mockFindByKey(null);

        given()
                .when()
                .get("/api/storefront/payment-methods")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }
}
