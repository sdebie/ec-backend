package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration test for StorefrontCustomerPortalResource.
 * Full HTTP round-trip with real customer JWT.
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8
 */
@QuarkusTest
class StorefrontCustomerPortalResourceIT {

    private static final String CUSTOMER_WITH_ADDR_EMAIL = "portal-test@example.com";
    private static final String CUSTOMER_NO_ADDR_EMAIL = "portal-noaddr@example.com";

    private CustomerEntity customerWithAddresses;
    private CustomerEntity customerWithoutAddresses;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(CustomerEntity.class);

        // ── Customer WITH addresses and password ──────────────────────────────
        UserEntity userWithAddr = new UserEntity();
        userWithAddr.id = UUID.randomUUID();
        userWithAddr.email = CUSTOMER_WITH_ADDR_EMAIL;
        userWithAddr.passwordHash = "abc123hashedvalue";
        userWithAddr.isActive = true;

        customerWithAddresses = new CustomerEntity();
        customerWithAddresses.id = UUID.randomUUID();
        customerWithAddresses.user = userWithAddr;
        customerWithAddresses.firstName = "Jane";
        customerWithAddresses.lastName = "Smith";
        customerWithAddresses.phone = "0821234567";
        customerWithAddresses.shopperType = CustomerTypeEn.RETAILER;
        customerWithAddresses.status = CustomerStatusEn.ACTIVE;

        CustomerAddressEntity physical = new CustomerAddressEntity();
        physical.id = UUID.randomUUID();
        physical.customer = customerWithAddresses;
        physical.addressType = AddressTypeEn.PHYSICAL;
        physical.addressLine1 = "10 Main Street";
        physical.addressLine2 = "Unit 5";
        physical.suburb = "Sandton";
        physical.city = "Johannesburg";
        physical.province = "Gauteng";
        physical.postalCode = "2196";

        CustomerAddressEntity postal = new CustomerAddressEntity();
        postal.id = UUID.randomUUID();
        postal.customer = customerWithAddresses;
        postal.addressType = AddressTypeEn.POSTAL;
        postal.addressLine1 = "PO Box 123";
        postal.addressLine2 = null;
        postal.suburb = null;
        postal.city = "Cape Town";
        postal.province = "Western Cape";
        postal.postalCode = "8001";

        customerWithAddresses.addresses = List.of(physical, postal);

        // ── Customer WITHOUT addresses and no password ────────────────────────
        UserEntity userNoAddr = new UserEntity();
        userNoAddr.id = UUID.randomUUID();
        userNoAddr.email = CUSTOMER_NO_ADDR_EMAIL;
        userNoAddr.passwordHash = null;
        userNoAddr.isActive = true;

        customerWithoutAddresses = new CustomerEntity();
        customerWithoutAddresses.id = UUID.randomUUID();
        customerWithoutAddresses.user = userNoAddr;
        customerWithoutAddresses.firstName = "Bob";
        customerWithoutAddresses.lastName = "NoAddress";
        customerWithoutAddresses.phone = null;
        customerWithoutAddresses.shopperType = CustomerTypeEn.WHOLESALER;
        customerWithoutAddresses.status = CustomerStatusEn.ACTIVE;
        customerWithoutAddresses.addresses = new ArrayList<>();

        // Mock entity finders
        when(CustomerEntity.findByEmail(CUSTOMER_WITH_ADDR_EMAIL)).thenReturn(customerWithAddresses);
        when(CustomerEntity.findByEmail(CUSTOMER_NO_ADDR_EMAIL)).thenReturn(customerWithoutAddresses);
    }

    /**
     * Generates a customer JWT with the given email as subject and "customer" role.
     * Uses SmallRye JWT Build with the project's private key.
     */
    private String generateCustomerJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void getCustomerPortal_withValidJwt_returnsCorrectJsonShape() {
        String token = generateCustomerJwt(CUSTOMER_WITH_ADDR_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
        .when()
                .get("/api/storefront/customer-portal")
        .then()
                .statusCode(200)
                .body("email", equalTo(CUSTOMER_WITH_ADDR_EMAIL))
                .body("shopperType", equalTo("RETAILER"))
                .body("firstName", equalTo("Jane"))
                .body("lastName", equalTo("Smith"))
                .body("phone", equalTo("0821234567"))
                .body("hasPassword", equalTo(true))
                // Physical address fields
                .body("physicalAddress.line1", equalTo("10 Main Street"))
                .body("physicalAddress.line2", equalTo("Unit 5"))
                .body("physicalAddress.suburb", equalTo("Sandton"))
                .body("physicalAddress.city", equalTo("Johannesburg"))
                .body("physicalAddress.province", equalTo("Gauteng"))
                .body("physicalAddress.postalCode", equalTo("2196"))
                // Postal address fields
                .body("postalAddress.line1", equalTo("PO Box 123"))
                .body("postalAddress.line2", nullValue())
                .body("postalAddress.suburb", nullValue())
                .body("postalAddress.city", equalTo("Cape Town"))
                .body("postalAddress.province", equalTo("Western Cape"))
                .body("postalAddress.postalCode", equalTo("8001"));
    }

    @Test
    void getCustomerPortal_withoutToken_returns401() {
        given()
        .when()
                .get("/api/storefront/customer-portal")
        .then()
                .statusCode(401);
    }

    @Test
    void getCustomerPortal_customerWithNoAddresses_addressesAreNull() {
        String token = generateCustomerJwt(CUSTOMER_NO_ADDR_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
        .when()
                .get("/api/storefront/customer-portal")
        .then()
                .statusCode(200)
                .body("email", equalTo(CUSTOMER_NO_ADDR_EMAIL))
                .body("shopperType", equalTo("WHOLESALER"))
                .body("firstName", equalTo("Bob"))
                .body("lastName", equalTo("NoAddress"))
                .body("phone", nullValue())
                .body("hasPassword", equalTo(false))
                .body("physicalAddress", nullValue())
                .body("postalAddress", nullValue());
    }
}
