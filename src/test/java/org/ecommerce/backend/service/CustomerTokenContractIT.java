package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Producer→consumer contract test for the customer JWT.
 * <p>
 * Every other IT mints its own token with Jwt.subject(...), which lets the real
 * issuer drift from what the consumers expect — exactly the shape of defect this
 * guards against: {@code CustomerAuthService} setting only {@code upn} while every
 * endpoint reads {@code jwt.getSubject()}. This test uses the REAL production issuer
 * (CustomerAuthService.generateToken) and asserts the token is accepted by the
 * real consumer endpoints.
 */
@QuarkusTest
class CustomerTokenContractIT
{
    private static final String EMAIL = "contract-test@test.com";

    @Inject
    CustomerAuthService customerAuthService;

    @InjectMock
    OrderService orderService;

    @InjectMock
    WishlistService wishlistService;

    @InjectMock
    CustomerPortalService customerPortalService;

    private CustomerEntity customer;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);

        UUID customerId = UUID.randomUUID();

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(EMAIL);
        user.setPasswordHash("somehash");
        user.setActive(true);

        customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setUser(user);
        customer.setFirstName("Contract");
        customer.setLastName("Test");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        user.setCustomer(customer);

        when(CustomerEntity.findByEmail(EMAIL)).thenReturn(customer);
        when(orderService.getMyOrders(eq(customerId))).thenReturn(List.of());
        when(wishlistService.getWishlistVariantIds(eq(customerId))).thenReturn(List.of());

        StorefrontCustomerPortalDto profile = new StorefrontCustomerPortalDto();
        profile.setEmail(EMAIL);
        profile.setShopperType("RETAILER");
        profile.setFirstName("Contract");
        profile.setLastName("Test");
        profile.setHasPassword(true);
        when(customerPortalService.getPortalProfile(eq(EMAIL))).thenReturn(profile);
    }

    private String realToken()
    {
        return customerAuthService.generateToken(customer);
    }

    @Test
    @DisplayName("Production token carries sub = email (consumers resolve identity via jwt.getSubject())")
    void productionToken_hasSubjectClaim()
    {
        String[] parts = realToken().split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        JsonObject payload = Json.createReader(new StringReader(payloadJson)).readObject();

        assertEquals(EMAIL, payload.getString("sub", null), "CustomerAuthService must set .subject(email): every customer endpoint resolves identity via jwt.getSubject()");
        assertEquals(EMAIL, payload.getString("upn", null), "upn must also be set (MP-JWT principal name)");
    }

    @Test
    @DisplayName("Production token is accepted by myOrders (GraphQL)")
    void productionToken_acceptedByMyOrders()
    {
        given()
                .header("Authorization", "Bearer " + realToken())
                .contentType("application/json")
                .body("{\"query\": \"{ myOrders { id } }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.myOrders", notNullValue());
    }

    @Test
    @DisplayName("Production token is accepted by GET /api/storefront/customer-portal")
    void productionToken_acceptedByCustomerPortal()
    {
        given()
                .header("Authorization", "Bearer " + realToken())
                .when()
                .get("/api/storefront/customer-portal")
                .then()
                .statusCode(200)
                .body("email", equalTo(EMAIL));
    }

    @Test
    @DisplayName("Production token is accepted by GET /api/storefront/wishlist")
    void productionToken_acceptedByWishlist()
    {
        given()
                .header("Authorization", "Bearer " + realToken())
                .when()
                .get("/api/storefront/wishlist")
                .then()
                .statusCode(200)
                .body("variantIds", hasSize(0));
    }
}
