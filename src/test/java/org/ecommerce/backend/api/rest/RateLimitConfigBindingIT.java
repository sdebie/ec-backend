package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * End-to-end rate limit test with the REAL {@link org.ecommerce.backend.service.RateLimiterService}
 * — no {@code @InjectMock}.
 * <p>
 * Every other endpoint IT mocks the limiter to test denial handling, which leaves two
 * things unproven: that the {@code %test.ratelimit.*} configuration keys actually bind
 * (a typo in a key name would silently fall back to code defaults), and that the full
 * request path (proxy header → {@code ClientIpUtils} → limiter → 429/recovery) works
 * against the real bean. This class closes that gap using {@code /api/customers/lookup}
 * (%test config: max 3 per 2-second window).
 * <p>
 * Uses IP keys unique to this class so the shared application-scoped bucket map never
 * collides with other test classes running in the same Quarkus instance.
 *
 */
@QuarkusTest
class RateLimitConfigBindingIT {

    private static final String LOOKUP_PATH = "/api/customers/lookup";
    private static final String NONEXISTENT_EMAIL = "nobody-ratelimit-binding@example.invalid";

    @Test
    @DisplayName("%test config binds: real limiter admits 3, denies the 4th with Retry-After, recovers after the window")
    void testProfileWindow_enforcedByRealLimiter_thenRecovers() throws InterruptedException {
        String ip = "203.0.113.201"; // unique to this test — never used elsewhere

        // %test.ratelimit.customer-lookup.max=3 — three requests pass through the limiter
        for (int i = 1; i <= 3; i++) {
            int status = given()
                    .header("X-Forwarded-For", ip)
                    .queryParam("email", NONEXISTENT_EMAIL)
                    .when()
                    .get(LOOKUP_PATH)
                    .getStatusCode();
            org.junit.jupiter.api.Assertions.assertEquals(204, status,
                    "request " + i + " must pass the limiter (204 = unknown email, not rate limited)");
        }

        // 4th request in the window: denied by the REAL limiter with the %test window's Retry-After
        String retryAfter = given()
                .header("X-Forwarded-For", ip)
                .queryParam("email", NONEXISTENT_EMAIL)
                .when()
                .get(LOOKUP_PATH)
                .then()
                .statusCode(429)
                .extract().header("Retry-After");

        long retryAfterSeconds = Long.parseLong(retryAfter);
        org.junit.jupiter.api.Assertions.assertTrue(retryAfterSeconds >= 1 && retryAfterSeconds <= 2,
                "Retry-After must reflect the %test 2-second window (1..2), was: " + retryAfterSeconds);

        // If the code defaults (20/3600) were silently in effect instead of the %test
        // config (3/2s), the 4th request would have been ADMITTED and the assertion
        // above would have failed — this is the config-binding proof.

        // Recovery: after the 2-second %test window elapses, the same IP is admitted again
        Thread.sleep(2_100);
        given()
                .header("X-Forwarded-For", ip)
                .queryParam("email", NONEXISTENT_EMAIL)
                .when()
                .get(LOOKUP_PATH)
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("distinct IPs are isolated on the real limiter — exhausting one never affects another")
    void distinctIps_isolatedOnRealLimiter() {
        String exhaustedIp = "203.0.113.202";
        String freshIp = "203.0.113.203";

        // Exhaust the first IP's budget (3) and confirm denial
        for (int i = 0; i < 3; i++) {
            given().header("X-Forwarded-For", exhaustedIp)
                    .queryParam("email", NONEXISTENT_EMAIL)
                    .when().get(LOOKUP_PATH)
                    .then().statusCode(204);
        }
        given().header("X-Forwarded-For", exhaustedIp)
                .queryParam("email", NONEXISTENT_EMAIL)
                .when().get(LOOKUP_PATH)
                .then().statusCode(429);

        // A different IP still has its full budget
        given().header("X-Forwarded-For", freshIp)
                .queryParam("email", NONEXISTENT_EMAIL)
                .when().get(LOOKUP_PATH)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("spoofed XFF prefixes cannot evade the real IP's bucket — rotating fake first entries still hits 429")
    void spoofedXffPrefix_cannotEvadeRealIpBucket() {
        // Cloudflare APPENDS the real connecting IP to any client-supplied XFF, so the
        // LAST entry is the trustworthy one. An attacker rotating fabricated prefixes
        // must still land in the real IP's bucket (KNOWN-LIMITATIONS §2 remediation).
        String realIp = "203.0.113.204"; // unique to this test

        for (int i = 1; i <= 3; i++) {
            given().header("X-Forwarded-For", "6.6.6." + i + ", " + realIp)
                    .queryParam("email", NONEXISTENT_EMAIL)
                    .when().get(LOOKUP_PATH)
                    .then().statusCode(204);
        }

        // 4th request with yet another spoofed prefix: same real bucket → denied.
        // Under the pre-fix first-entry resolution, every request above would have
        // opened a fresh bucket and this would be ADMITTED (204).
        given().header("X-Forwarded-For", "6.6.6.99, " + realIp)
                .queryParam("email", NONEXISTENT_EMAIL)
                .when().get(LOOKUP_PATH)
                .then().statusCode(429);
    }
}
