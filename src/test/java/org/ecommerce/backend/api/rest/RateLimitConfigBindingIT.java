package org.ecommerce.backend.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
 * against the real bean. This class closes that gap using {@code POST /api/admin/auth/login}
 * with bogus credentials (%test config: max 3 per 2-second window) — a rejected login is a
 * pure read (no persistence), so it is as safe to hammer as a lookup would be.
 * <p>
 * Login chains a SECOND limiter keyed by email ({@code admin-login-email}) behind the IP
 * check, so every call here uses a distinct, never-reused email — that keeps the email
 * limiter permanently unexhausted and leaves only the IP-keyed {@code admin-login} limiter
 * under test, matching what this class asserts about.
 * <p>
 * Uses IP keys unique to this class so the shared application-scoped bucket map never
 * collides with other test classes running in the same Quarkus instance.
 *
 */
@QuarkusTest
class RateLimitConfigBindingIT {

    private static final String LOGIN_PATH = "/api/admin/auth/login";

    private static String loginPayload(String email) {
        return """
                {
                    "email": "%s",
                    "password": "wrong-password-does-not-matter"
                }
                """.formatted(email);
    }

    @Test
    @DisplayName("%test config binds: real limiter admits 3, denies the 4th with Retry-After, recovers after the window")
    void adminLoginWindow_enforcedByRealLimiter_thenRecovers() throws InterruptedException {
        String ip = "203.0.113.201"; // unique to this test — never used elsewhere

        // %test.ratelimit.admin-login.max=3 — three requests pass through the IP limiter
        for (int i = 1; i <= 3; i++) {
            int status = given()
                    .contentType(ContentType.JSON)
                    .header("X-Forwarded-For", ip)
                    .body(loginPayload("nobody-binding-" + i + "@example.invalid"))
                    .when()
                    .post(LOGIN_PATH)
                    .getStatusCode();
            org.junit.jupiter.api.Assertions.assertEquals(401, status,
                    "request " + i + " must pass the IP limiter (401 = bad credentials, not rate limited)");
        }

        // 4th request in the window: denied by the REAL limiter with the %test window's Retry-After
        String retryAfter = given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", ip)
                .body(loginPayload("nobody-binding-4@example.invalid"))
                .when()
                .post(LOGIN_PATH)
                .then()
                .statusCode(429)
                .extract().header("Retry-After");

        long retryAfterSeconds = Long.parseLong(retryAfter);
        org.junit.jupiter.api.Assertions.assertTrue(retryAfterSeconds >= 1 && retryAfterSeconds <= 2,
                "Retry-After must reflect the %test 2-second window (1..2), was: " + retryAfterSeconds);

        // If the code defaults (10/900) were silently in effect instead of the %test
        // config (3/2s), the 4th request would have been ADMITTED and the assertion
        // above would have failed — this is the config-binding proof.

        // Recovery: after the 2-second %test window elapses, the same IP is admitted again
        Thread.sleep(2_100);
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-For", ip)
                .body(loginPayload("nobody-binding-5@example.invalid"))
                .when()
                .post(LOGIN_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("distinct IPs are isolated on the real limiter — exhausting one never affects another")
    void distinctIps_isolatedOnRealLimiter() {
        String exhaustedIp = "203.0.113.202";
        String freshIp = "203.0.113.203";

        // Exhaust the first IP's budget (3) and confirm denial
        for (int i = 0; i < 3; i++) {
            given().contentType(ContentType.JSON)
                    .header("X-Forwarded-For", exhaustedIp)
                    .body(loginPayload("nobody-isolation-a" + i + "@example.invalid"))
                    .when().post(LOGIN_PATH)
                    .then().statusCode(401);
        }
        given().contentType(ContentType.JSON)
                .header("X-Forwarded-For", exhaustedIp)
                .body(loginPayload("nobody-isolation-a3@example.invalid"))
                .when().post(LOGIN_PATH)
                .then().statusCode(429);

        // A different IP still has its full budget
        given().contentType(ContentType.JSON)
                .header("X-Forwarded-For", freshIp)
                .body(loginPayload("nobody-isolation-b@example.invalid"))
                .when().post(LOGIN_PATH)
                .then().statusCode(401);
    }

    @Test
    @DisplayName("spoofed XFF prefixes cannot evade the real IP's bucket — rotating fake first entries still hits 429")
    void spoofedXffPrefix_cannotEvadeRealIpBucket() {
        // Cloudflare APPENDS the real connecting IP to any client-supplied XFF, so the
        // LAST entry is the trustworthy one. An attacker rotating fabricated prefixes
        // must still land in the real IP's bucket (KNOWN-LIMITATIONS §2 remediation).
        String realIp = "203.0.113.204"; // unique to this test

        for (int i = 1; i <= 3; i++) {
            given().contentType(ContentType.JSON)
                    .header("X-Forwarded-For", "6.6.6." + i + ", " + realIp)
                    .body(loginPayload("nobody-xff-" + i + "@example.invalid"))
                    .when().post(LOGIN_PATH)
                    .then().statusCode(401);
        }

        // 4th request with yet another spoofed prefix: same real bucket → denied.
        // Under the pre-fix first-entry resolution, every request above would have
        // opened a fresh bucket and this would be ADMITTED (401, not 429).
        given().contentType(ContentType.JSON)
                .header("X-Forwarded-For", "6.6.6.99, " + realIp)
                .body(loginPayload("nobody-xff-4@example.invalid"))
                .when().post(LOGIN_PATH)
                .then().statusCode(429);
    }
}
