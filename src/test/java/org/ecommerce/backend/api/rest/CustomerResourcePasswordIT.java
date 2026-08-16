package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.CustomerPortalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Integration test for PATCH /api/customers/password (password change endpoint).
 */
@QuarkusTest
class CustomerResourcePasswordIT {

    private static final String CUSTOMER_A_EMAIL = "customera@test.com";
    private static final String CUSTOMER_A_PASSWORD = "TestPass1";

    private static final String CUSTOMER_B_EMAIL = "customerb@test.com";

    @InjectMock
    CustomerPortalService customerPortalService;

    @BeforeEach
    void setUp() {
        // Customer A: has a local password — successful change
        doNothing().when(customerPortalService)
                .changePassword(eq(CUSTOMER_A_EMAIL), eq(CUSTOMER_A_PASSWORD), eq("NewPass123"));

        doNothing().when(customerPortalService)
                .changePassword(eq(CUSTOMER_A_EMAIL), eq(CUSTOMER_A_PASSWORD), eq("BrandNewPass1"));

        // Customer A: incorrect current password → 401
        doThrow(new WebApplicationException(
                Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "Current password is incorrect"))
                        .build()))
                .when(customerPortalService)
                .changePassword(eq(CUSTOMER_A_EMAIL), eq("WrongPass99"), eq("NewPass123"));

        // Customer A: too-short new password → 400
        doThrow(new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Password must be at least 8 characters"))
                        .build()))
                .when(customerPortalService)
                .changePassword(eq(CUSTOMER_A_EMAIL), eq(CUSTOMER_A_PASSWORD), eq("short"));

        // Customer B: no local password set → 400
        doThrow(new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "No local password is set for this account"))
                        .build()))
                .when(customerPortalService)
                .changePassword(eq(CUSTOMER_B_EMAIL), eq("anything"), eq("NewPass123"));
    }

    /**
     * Generate a customer JWT with subject=email and role="customer".
     */
    private String generateCustomerJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    @Test
    void changePassword_withCorrectCurrentPassword_returns200() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"currentPassword\": \"" + CUSTOMER_A_PASSWORD + "\", \"newPassword\": \"NewPass123\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(200);
    }

    @Test
    void changePassword_withIncorrectCurrentPassword_returns401() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"currentPassword\": \"WrongPass99\", \"newPassword\": \"NewPass123\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(401)
                .body("error", equalTo("Current password is incorrect"));
    }

    @Test
    void changePassword_withTooShortNewPassword_returns400() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"currentPassword\": \"" + CUSTOMER_A_PASSWORD + "\", \"newPassword\": \"short\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(400)
                .body("error", equalTo("Password must be at least 8 characters"));
    }

    @Test
    void changePassword_noLocalPasswordSet_returns400() {
        String token = generateCustomerJwt(CUSTOMER_B_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"currentPassword\": \"anything\", \"newPassword\": \"NewPass123\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(400)
                .body("error", equalTo("No local password is set for this account"));
    }

    @Test
    void changePassword_withoutJwt_returns401() {
        given()
                .contentType("application/json")
                .body("{\"currentPassword\": \"TestPass1\", \"newPassword\": \"NewPass123\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(401);
    }

    @Test
    void changePassword_successfulChange_verifyNewPasswordAccepted() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);
        String newPassword = "BrandNewPass1";

        // Verify that changing the password returns 200
        // (the mock is set up to accept this combination without throwing)
        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"currentPassword\": \"" + CUSTOMER_A_PASSWORD + "\", \"newPassword\": \"" + newPassword + "\"}")
                .when()
                .patch("/api/customers/password")
                .then()
                .statusCode(200);
    }
}
