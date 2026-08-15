package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Role matrix for the staff order surface.
 * <p>
 * CATALOG_MANAGER is pinned deliberately: a capability accidentally wired to
 * the wrong backend role still passes a test that only checks SUPER_ADMIN and
 * an unauthenticated caller, because both ends behave identically either way.
 * VIEWER is pinned for the same reason on the read/write boundary — it may read
 * orders but must never move one.
 * <p>
 * The reads use ids no order has. An authorized caller therefore gets a plain
 * "Order not found", which is the proof the role gate let them through to the
 * resolver body rather than rejecting them at the door.
 */
@QuarkusTest
class OrderAdminResourceAuthIT
{
    private static final String ADMIN_ORDER_LIST_BODY =
            "{\"query\":\"{ adminOrderList(pageIndex: 0, pageSize: 1) { totalElements } }\"}";

    private static final String ADMIN_ORDER_BODY =
            "{\"query\":\"{ adminOrder(id: \\\"not-a-uuid\\\") { id } }\"}";

    private static final String UPDATE_ORDER_STATUS_BODY =
            "{\"query\":\"mutation { updateOrderStatus(orderId: \\\"not-a-uuid\\\", status: \\\"CANCELLED\\\") { id } }\"}";

    private String staffJwt(String role)
    {
        return Jwt.subject(role.toLowerCase() + "@test.com")
                .issuer("http://localhost:8080")
                .groups(role)
                .claim("full_name", "Test " + role)
                .sign();
    }

    private io.restassured.response.ValidatableResponse post(String role, String body)
    {
        return given()
                .header("Authorization", "Bearer " + staffJwt(role))
                .contentType("application/json")
                .body(body)
        .when()
                .post("/api/graphql")
        .then()
                .statusCode(200);
    }

    @Nested
    @DisplayName("adminOrderList / adminOrder — SUPER_ADMIN, ORDER_MANAGER and VIEWER may read")
    class Reads
    {
        @Test
        @DisplayName("SUPER_ADMIN → allowed")
        void superAdmin_allowed()
        {
            post("SUPER_ADMIN", ADMIN_ORDER_LIST_BODY).body("errors", nullValue());
        }

        @Test
        @DisplayName("ORDER_MANAGER → allowed")
        void orderManager_allowed()
        {
            post("ORDER_MANAGER", ADMIN_ORDER_LIST_BODY).body("errors", nullValue());
        }

        @Test
        @DisplayName("VIEWER → allowed")
        void viewer_allowed()
        {
            post("VIEWER", ADMIN_ORDER_LIST_BODY).body("errors", nullValue());
        }

        @Test
        @DisplayName("CATALOG_MANAGER → FORBIDDEN")
        void catalogManager_forbidden()
        {
            post("CATALOG_MANAGER", ADMIN_ORDER_LIST_BODY)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("adminOrder: VIEWER reaches the resolver, CATALOG_MANAGER does not")
        void adminOrder_readRoles()
        {
            post("VIEWER", ADMIN_ORDER_BODY)
                    .body("errors[0].message", equalTo("Order not found"));

            post("CATALOG_MANAGER", ADMIN_ORDER_BODY)
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }
    }

    @Nested
    @DisplayName("updateOrderStatus — only SUPER_ADMIN and ORDER_MANAGER may write")
    class Writes
    {
        @Test
        @DisplayName("ORDER_MANAGER → reaches the resolver")
        void orderManager_allowed()
        {
            post("ORDER_MANAGER", UPDATE_ORDER_STATUS_BODY)
                    .body("errors[0].message", equalTo("Order not found"));
        }

        @Test
        @DisplayName("VIEWER → FORBIDDEN")
        void viewer_forbidden()
        {
            post("VIEWER", UPDATE_ORDER_STATUS_BODY)
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("CATALOG_MANAGER → FORBIDDEN")
        void catalogManager_forbidden()
        {
            post("CATALOG_MANAGER", UPDATE_ORDER_STATUS_BODY)
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }
    }
}
