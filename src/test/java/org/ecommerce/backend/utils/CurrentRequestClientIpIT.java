package org.ecommerce.backend.utils;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration test proving that {@link CurrentRequestClientIp} correctly resolves
 * the client IP from HTTP headers when injected into a GraphQL resolver context.
 * <p>
 * Uses {@link ClientIpProbeResource} — a test-only GraphQL resolver that exposes
 * the resolved IP for assertion.
 * <p>
 * Validates: Requirement 7.2 (GraphQL resolvers obtain the same headers from the
 * current HTTP request via a small request-scoped helper).
 */
@QuarkusTest
class CurrentRequestClientIpIT
{
    @Test
    @DisplayName("resolves the LAST X-Forwarded-For entry (proxy-appended; spoofed prefixes ignored)")
    void resolve_xForwardedFor_returnsLastEntry()
    {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1, 192.168.1.1")
                .body("{\"query\":\"{ probeClientIp }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.probeClientIp", equalTo("192.168.1.1"))
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("CF-Connecting-IP takes precedence over X-Forwarded-For")
    void resolve_cfConnectingIp_takesPrecedence()
    {
        given()
                .contentType("application/json")
                .header("CF-Connecting-IP", "203.0.113.7")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.1")
                .header("X-Real-IP", "198.51.100.7")
                .body("{\"query\":\"{ probeClientIp }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.probeClientIp", equalTo("203.0.113.7"))
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("resolves X-Real-IP when X-Forwarded-For is absent")
    void resolve_xRealIp_fallback()
    {
        given()
                .contentType("application/json")
                .header("X-Real-IP", "198.51.100.7")
                .body("{\"query\":\"{ probeClientIp }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.probeClientIp", equalTo("198.51.100.7"))
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("returns 'unknown' when no proxy headers are present")
    void resolve_noHeaders_returnsUnknown()
    {
        given()
                .contentType("application/json")
                .body("{\"query\":\"{ probeClientIp }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.probeClientIp", equalTo("unknown"))
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("X-Forwarded-For takes precedence over X-Real-IP")
    void resolve_bothHeaders_prefersXForwardedFor()
    {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "192.0.2.99")
                .header("X-Real-IP", "198.51.100.7")
                .body("{\"query\":\"{ probeClientIp }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.probeClientIp", equalTo("192.0.2.99"))
                .body("errors", nullValue());
    }
}
