package org.ecommerce.backend.api;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests verifying the authorization boundary matrix.
 * <p>
 * Tests that:
 * 1. Unauthenticated requests to admin endpoints receive 401
 * 2. Customer JWT to admin endpoints receives 403
 * 3. Staff JWT to customer-only endpoints receives 403
 * 4. Correct-role staff JWT reaches admin endpoints (non-401/403)
 * 5. Anonymous access to public storefront reads succeeds (no over-guarding)
 * <p>
 */
@QuarkusTest
class AuthorizationIT {

    // ─── JWT Helpers ─────────────────────────────────────────────────────────

    private String generateStaffJwt(String email, String role) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    private String generateCustomerJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    // ─── GraphQL Body Builders ──────────────────────────────────────────────

    private static final String CREATE_BRAND_BODY =
            "{\"query\":\"mutation { createBrand(brandDto: {name: \\\"authTest\\\", slug: \\\"auth-test\\\"}) }\"}";

    private static final String UPDATE_ORDER_STATUS_BODY =
            "{\"query\":\"mutation { updateOrderStatus(sessionId: \\\"fake-session\\\", status: \\\"SHIPPED\\\") { sessionId } }\"}";

    private static final String ADD_STAFF_USER_BODY =
            "{\"query\":\"mutation { addStaffUser(staffDto: {email: \\\"x@test.com\\\", fullName: \\\"X\\\", role: SUPER_ADMIN, active: true, resetPassword: false}) }\"}";

    private static final String SAVE_STORE_SETTINGS_BODY =
            "{\"query\":\"mutation { saveStoreSettings(storeSettingsDto: [{key: \\\"test.key\\\", value: \\\"test.val\\\"}]) { key } }\"}";

    private static final String ALL_CUSTOMERS_BODY =
            "{\"query\":\"{ allCustomers { id } }\"}";

    private static final String STAFF_LIST_BODY =
            "{\"query\":\"{ staffList { id } }\"}";

    private static final String ALL_ORDERS_BODY =
            "{\"query\":\"{ allOrders { sessionId } }\"}";

    private static final String SETTINGS_BODY =
            "{\"query\":\"{ settings { storeSettings { key } } }\"}";

    private static final String STORE_SETTINGS_BODY =
            "{\"query\":\"{ storeSettings { key value } }\"}";

    private static final String SHIPPING_METHODS_BODY =
            "{\"query\":\"{ shippingMethods { id } }\"}";

    private static final String COUNTRY_SETTINGS_BODY =
            "{\"query\":\"{ countrySettings { countryCode } }\"}";

    private static final String ALL_BRANDS_BODY =
            "{\"query\":\"{ allBrands { id name } }\"}";

    private static final String ALL_CATEGORIES_BODY =
            "{\"query\":\"{ allCategories { id name } }\"}";

    private static final String BRAND_COUNT_BODY =
            "{\"query\":\"{ brandCount }\"}";

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Unauthenticated → Admin REST Mutations → 401
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unauthenticated → admin REST mutations → 401")
    class UnauthenticatedAdminRestMutations {

        @Test
        @DisplayName("POST /api/admin/auth/reset-password → 401")
        void resetPassword_unauthenticated_returns401() {
            given()
                    .contentType("application/json")
                    .body("{\"email\":\"test@test.com\",\"password\":\"Pass1234\",\"confirmPassword\":\"Pass1234\"}")
            .when()
                    .post("/api/admin/auth/reset-password")
            .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("POST /api/admin/products/upload-csv → 401")
        void productUploadCsv_unauthenticated_returns401() {
            given()
                    .contentType("multipart/form-data")
            .when()
                    .post("/api/admin/products/upload-csv")
            .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("POST /api/admin/images/upload/brand → 401")
        void uploadBrandImage_unauthenticated_returns401() {
            given()
                    .contentType("multipart/form-data")
            .when()
                    .post("/api/admin/images/upload/brand")
            .then()
                    .statusCode(401);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Unauthenticated → Admin REST Reads → 401
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unauthenticated → admin REST reads → 401")
    class UnauthenticatedAdminRestReads {

        @Test
        @DisplayName("GET /api/admin/products/price_export → 401")
        void priceExport_unauthenticated_returns401() {
            given()
            .when()
                    .get("/api/admin/products/price_export")
            .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("GET /api/admin/images/directories → 401")
        void imageDirectories_unauthenticated_returns401() {
            given()
            .when()
                    .get("/api/admin/images/directories")
            .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("GET /api/admin/products/full_export → 401")
        void fullExport_unauthenticated_returns401() {
            given()
            .when()
                    .get("/api/admin/products/full_export")
            .then()
                    .statusCode(401);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Unauthenticated → Admin GraphQL Mutations → UNAUTHORIZED
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unauthenticated → admin GraphQL mutations → UNAUTHORIZED")
    class UnauthenticatedAdminGraphQLMutations {

        @Test
        @DisplayName("createBrand → UNAUTHORIZED")
        void createBrand_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(CREATE_BRAND_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("updateOrderStatus → UNAUTHORIZED")
        void updateOrderStatus_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(UPDATE_ORDER_STATUS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("addStaffUser → UNAUTHORIZED")
        void addStaffUser_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(ADD_STAFF_USER_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("saveStoreSettings → UNAUTHORIZED")
        void saveStoreSettings_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(SAVE_STORE_SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Unauthenticated → Admin GraphQL Privileged Reads → UNAUTHORIZED
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unauthenticated → admin GraphQL privileged reads → UNAUTHORIZED")
    class UnauthenticatedAdminGraphQLReads {

        @Test
        @DisplayName("allCustomers → UNAUTHORIZED")
        void allCustomers_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(ALL_CUSTOMERS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("staffList → UNAUTHORIZED")
        void staffList_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(STAFF_LIST_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("allOrders → UNAUTHORIZED")
        void allOrders_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(ALL_ORDERS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }

        @Test
        @DisplayName("settings → UNAUTHORIZED")
        void settings_unauthenticated_unauthorized() {
            given()
                    .contentType("application/json")
                    .body(SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. Customer JWT → Admin REST Endpoints → 403
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Customer JWT → admin REST endpoints → 403")
    class CustomerJwtAdminRestEndpoints {

        @Test
        @DisplayName("POST /api/admin/auth/reset-password → 403")
        void resetPassword_customer_returns403() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body("{\"email\":\"test@test.com\",\"password\":\"Pass1234\",\"confirmPassword\":\"Pass1234\"}")
            .when()
                    .post("/api/admin/auth/reset-password")
            .then()
                    .statusCode(403);
        }

        @Test
        @DisplayName("GET /api/admin/products/price_export → 403")
        void priceExport_customer_returns403() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
            .when()
                    .get("/api/admin/products/price_export")
            .then()
                    .statusCode(403);
        }

        @Test
        @DisplayName("GET /api/admin/images/directories → 403")
        void imageDirectories_customer_returns403() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
            .when()
                    .get("/api/admin/images/directories")
            .then()
                    .statusCode(403);
        }

        @Test
        @DisplayName("GET /api/admin/me → 403")
        void adminMe_customer_returns403() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
            .when()
                    .get("/api/admin/me")
            .then()
                    .statusCode(403);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. Customer JWT → Admin GraphQL Endpoints → FORBIDDEN
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Customer JWT → admin GraphQL endpoints → FORBIDDEN")
    class CustomerJwtAdminGraphQLEndpoints {

        @Test
        @DisplayName("createBrand → FORBIDDEN")
        void createBrand_customer_forbidden() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body(CREATE_BRAND_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("addStaffUser → FORBIDDEN")
        void addStaffUser_customer_forbidden() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body(ADD_STAFF_USER_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("allCustomers → FORBIDDEN")
        void allCustomers_customer_forbidden() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body(ALL_CUSTOMERS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("staffList → FORBIDDEN")
        void staffList_customer_forbidden() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body(STAFF_LIST_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("settings → FORBIDDEN")
        void settings_customer_forbidden() {
            String customerToken = generateCustomerJwt("customer@test.com");
            given()
                    .header("Authorization", "Bearer " + customerToken)
                    .contentType("application/json")
                    .body(SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. Staff JWT → Customer-Only Endpoints → 403
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Staff JWT → customer-only endpoints → 403")
    class StaffJwtCustomerEndpoints {

        @Test
        @DisplayName("GET /api/storefront/customer-portal → 403")
        void customerPortal_staff_returns403() {
            String staffToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + staffToken)
            .when()
                    .get("/api/storefront/customer-portal")
            .then()
                    .statusCode(403);
        }

        @Test
        @DisplayName("PATCH /api/customers/profile → 403")
        void customerProfile_staff_returns403() {
            String staffToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + staffToken)
                    .contentType("application/json")
                    .body("{\"firstName\":\"Test\"}")
            .when()
                    .patch("/api/customers/profile")
            .then()
                    .statusCode(403);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. Correct-Role Staff JWT → Admin Endpoints → non-401/403
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Correct-role staff JWT → admin endpoints → non-401/403")
    class CorrectRoleStaffAdminEndpoints {

        @Test
        @DisplayName("GET /api/admin/me with SUPER_ADMIN → 200")
        void adminMe_superAdmin_succeeds() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + superAdminToken)
            .when()
                    .get("/api/admin/me")
            .then()
                    .statusCode(200);
        }

        @Test
        @DisplayName("GET /api/admin/products/price_export with SUPER_ADMIN → non-401/403")
        void priceExport_superAdmin_reachable() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            int status = given()
                    .header("Authorization", "Bearer " + superAdminToken)
            .when()
                    .get("/api/admin/products/price_export")
            .then()
                    .extract().statusCode();

            Assertions.assertTrue(
                    status != 401 && status != 403,
                    "Expected non-auth-error status but got " + status);
        }

        @Test
        @DisplayName("GET /api/admin/images/directories with SUPER_ADMIN → non-401/403")
        void imageDirectories_superAdmin_reachable() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            int status = given()
                    .header("Authorization", "Bearer " + superAdminToken)
            .when()
                    .get("/api/admin/images/directories")
            .then()
                    .extract().statusCode();

            Assertions.assertTrue(
                    status != 401 && status != 403,
                    "Expected non-auth-error status but got " + status);
        }

        @Test
        @DisplayName("createBrand with SUPER_ADMIN → no auth error")
        void createBrand_superAdmin_noAuthError() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + superAdminToken)
                    .contentType("application/json")
                    .body(CREATE_BRAND_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("settings query with SUPER_ADMIN → no auth error")
        void settings_superAdmin_noAuthError() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + superAdminToken)
                    .contentType("application/json")
                    .body(SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("allOrders with SUPER_ADMIN → no auth error")
        void allOrders_superAdmin_noAuthError() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + superAdminToken)
                    .contentType("application/json")
                    .body(ALL_ORDERS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("staffList with SUPER_ADMIN → no auth error")
        void staffList_superAdmin_noAuthError() {
            String superAdminToken = generateStaffJwt("admin@test.com", "SUPER_ADMIN");
            given()
                    .header("Authorization", "Bearer " + superAdminToken)
                    .contentType("application/json")
                    .body(STAFF_LIST_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. Anonymous → Public Storefront Reads → Success (Over-Guard Guard)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Anonymous → public storefront reads → success")
    class AnonymousPublicReads {

        @Test
        @DisplayName("GET /api/storefront/config → 200")
        void storefrontConfig_anonymous_succeeds() {
            given()
            .when()
                    .get("/api/storefront/config")
            .then()
                    .statusCode(200);
        }

        @Test
        @DisplayName("storeSettings query → no auth error")
        void storeSettings_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(STORE_SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("shippingMethods query → no auth error")
        void shippingMethods_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(SHIPPING_METHODS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("countrySettings query → no auth error")
        void countrySettings_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(COUNTRY_SETTINGS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("allBrands query → no auth error")
        void allBrands_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(ALL_BRANDS_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("allCategories query → no auth error")
        void allCategories_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(ALL_CATEGORIES_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }

        @Test
        @DisplayName("brandCount query → no auth error")
        void brandCount_anonymous_succeeds() {
            given()
                    .contentType("application/json")
                    .body(BRAND_COUNT_BODY)
            .when()
                    .post("/api/graphql")
            .then()
                    .statusCode(200)
                    .body("errors.find { it.extensions.code == 'unauthorized' }", nullValue())
                    .body("errors.find { it.extensions.code == 'forbidden' }", nullValue());
        }
    }
}
