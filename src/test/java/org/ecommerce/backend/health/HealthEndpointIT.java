package org.ecommerce.backend.health;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/**
 * Proves the real HTTP surface a load balancer or orchestrator would actually poll —
 * {@link RedisHealthCheckTest} covers the check's own decision logic in isolation.
 */
@QuarkusTest
class HealthEndpointIT
{
    @Test
    void healthEndpoint_reportsUpWithRedisCheck()
    {
        RestAssured.given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'redis' }.status", equalTo("UP"));
    }

    @Test
    void readinessEndpoint_includesRedisCheck()
    {
        RestAssured.given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.find { it.name == 'redis' }.status", equalTo("UP"));
    }

    @Test
    void livenessEndpoint_isReachable()
    {
        RestAssured.given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
