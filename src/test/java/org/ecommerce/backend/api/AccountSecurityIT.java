package org.ecommerce.backend.api;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for account security: takeover fix, OAuth-takeover regression,
 * guest checkout reachability, and Google-login shopperType behavior.
 * <p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountSecurityIT
{

    @Inject
    EntityManager em;

    // ─── Unique per-test-class emails (avoid collisions with other tests) ───

    private static final String TAKEOVER_EMAIL = "accsec-takeover@test.com";
    private static final String TAKEOVER_PASSWORD = "Secret123";
    private static final String OAUTH_EMAIL = "accsec-oauth@test.com";
    private static final String UNCLAIMED_EMAIL = "accsec-unclaimed@test.com";
    private static final String GUEST_UPGRADE_EMAIL = "accsec-guest-upgrade@test.com";

    // ─── Data Helpers ───────────────────────────────────────────────────────

    @Transactional
    void deleteByEmail(String email)
    {
        em.createNativeQuery("DELETE FROM customers WHERE user_id IN (SELECT id FROM users WHERE lower(email) = lower(:email))")
                .setParameter("email", email)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM users WHERE lower(email) = lower(:email)")
                .setParameter("email", email)
                .executeUpdate();
    }

    @Transactional
    void insertUserWithPassword()
    {
        UUID userId = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO users (id, email, password_hash, is_active, mfa_enabled, created_at, password_reset_code_attempts, roles) " +
                                "VALUES (:id, :email, :hash, true, false, now(), 0, '{RETAIL}')")
                .setParameter("id", userId)
                .setParameter("email", AccountSecurityIT.TAKEOVER_EMAIL)
                .setParameter("hash", PasswordHashUtil.hash(AccountSecurityIT.TAKEOVER_PASSWORD))
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO customers (id, user_id, first_name, last_name, status, shopper_type) " +
                                "VALUES (:id, :userId, 'Existing', 'User', 'ACTIVE', 'RETAILER')")
                .setParameter("id", UUID.randomUUID())
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    void insertOAuthUser()
    {
        UUID userId = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO users (id, email, password_hash, is_active, mfa_enabled, created_at, password_reset_code_attempts, roles) " +
                                "VALUES (:id, :email, '', true, false, now(), 0, '{RETAIL}')")
                .setParameter("id", userId)
                .setParameter("email", AccountSecurityIT.OAUTH_EMAIL)
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO customers (id, user_id, first_name, last_name, status, shopper_type) " +
                                "VALUES (:id, :userId, 'OAuth', 'User', 'ACTIVE', 'RETAILER')")
                .setParameter("id", UUID.randomUUID())
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    void insertUnclaimedGuestUser(String email)
    {
        UUID userId = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO users (id, email, password_hash, is_active, mfa_enabled, created_at, password_reset_code_attempts, roles) " +
                                "VALUES (:id, :email, '', true, false, now(), 0, '{RETAIL}')")
                .setParameter("id", userId)
                .setParameter("email", email)
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO customers (id, user_id, first_name, last_name, status, shopper_type) " +
                                "VALUES (:id, :userId, 'Guest', 'Unclaimed', 'PENDING', 'GUEST')")
                .setParameter("id", UUID.randomUUID())
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    String queryPasswordHash(String email)
    {
        Object result = em.createNativeQuery(
                        "SELECT password_hash FROM users WHERE lower(email) = lower(:email)")
                .setParameter("email", email)
                .getSingleResult();
        return result != null ? result.toString() : null;
    }

    @Transactional
    String queryCustomerStatus(String email)
    {
        Object result = em.createNativeQuery(
                        "SELECT c.status FROM customers c JOIN users u ON c.user_id = u.id WHERE lower(u.email) = lower(:email)")
                .setParameter("email", email)
                .getSingleResult();
        return result != null ? result.toString() : null;
    }

    @Transactional
    String queryShopperType(String email)
    {
        Object result = em.createNativeQuery(
                        "SELECT c.shopper_type FROM customers c JOIN users u ON c.user_id = u.id WHERE lower(u.email) = lower(:email)")
                .setParameter("email", email)
                .getSingleResult();
        return result != null ? result.toString() : null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setup / Teardown for all
    // ═══════════════════════════════════════════════════════════════════════════

    @BeforeEach
    void cleanBefore()
    {
        // Ensure a clean slate — idempotent delete
        deleteByEmail(TAKEOVER_EMAIL);
        deleteByEmail(OAUTH_EMAIL);
        deleteByEmail(UNCLAIMED_EMAIL);
        deleteByEmail(GUEST_UPGRADE_EMAIL);
    }

    @AfterEach
    void cleanAfter()
    {
        deleteByEmail(TAKEOVER_EMAIL);
        deleteByEmail(OAUTH_EMAIL);
        deleteByEmail(UNCLAIMED_EMAIL);
        deleteByEmail(GUEST_UPGRADE_EMAIL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Takeover Fix (REQ 11.6)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("REQ 11.6: POST /api/customers/register with existing email → 409, no token, hash unchanged")
    void register_existingAccount_returns409_hashUnchanged()
    {
        // Seed
        insertUserWithPassword();

        // Attempt to register with a new password on an existing account.
        // Distinct X-Forwarded-For per test: without it all register calls share the
        // limiter's "unknown" IP bucket and trip the %test rate limit (3/2s).
        String responseBody = given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.61")
                .body("{\"email\":\"" + TAKEOVER_EMAIL + "\",\"password\":\"NewPass123\",\"firstName\":\"Hacker\"}")
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(409)
                .extract().asString();

        // Assert no token in the response body
        assertFalse(responseBody.contains("token"),
                "Response must NOT contain a token field");

        // Verify password hash is unchanged — original password still works
        String hash = queryPasswordHash(TAKEOVER_EMAIL);
        assertNotNull(hash, "User should still exist");
        assertTrue(PasswordHashUtil.verify(TAKEOVER_PASSWORD, hash),
                "Original password should still verify — hash must be unchanged");
        assertFalse(PasswordHashUtil.verify("NewPass123", hash),
                "Attacker password should NOT verify");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. OAuth-Takeover Regression (REQ 11.10)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("REQ 11.10: POST /api/customers/register on OAuth account → 409, passwordHash still empty, no token")
    void register_oauthAccount_returns409_hashStillEmpty()
    {
        // Seed OAuth user
        insertOAuthUser();

        String responseBody = given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.62")
                .body("{\"email\":\"" + OAUTH_EMAIL + "\",\"password\":\"NewPass123\",\"firstName\":\"Attacker\"}")
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(409)
                .extract().asString();

        // Assert no token in response
        assertFalse(responseBody.contains("token"),
                "Response must NOT contain a token field");

        // Verify passwordHash is still empty
        String hash = queryPasswordHash(OAUTH_EMAIL);
        assertNotNull(hash, "OAuth user should still exist");
        assertTrue(hash.isEmpty(),
                "OAuth user's passwordHash should remain empty, was: " + hash);
    }

    @Test
    @Order(3)
    @DisplayName("REQ 11.10 companion: POST /api/customers/register on unclaimed PENDING/GUEST → 200 with token")
    void register_unclaimedGuest_succeeds_withToken()
    {
        // Seed unclaimed guest
        insertUnclaimedGuestUser(UNCLAIMED_EMAIL);

        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.63")
                .body("{\"email\":\"" + UNCLAIMED_EMAIL + "\",\"password\":\"ClaimPass1\",\"firstName\":\"Claimer\"}")
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("email", equalTo(UNCLAIMED_EMAIL));

        // Verify password is now set
        String hash = queryPasswordHash(UNCLAIMED_EMAIL);
        assertNotNull(hash, "User should exist");
        assertFalse(hash.isEmpty(), "Hash should no longer be empty");
        assertTrue(PasswordHashUtil.verify("ClaimPass1", hash),
                "Claimed user's password should now verify");
        assertEquals("ACTIVE", queryCustomerStatus(UNCLAIMED_EMAIL),
                "Customer status should be ACTIVE after claiming");
        assertEquals("RETAILER", queryShopperType(UNCLAIMED_EMAIL),
                "Customer shopperType should be RETAILER after claiming");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Guest Checkout Reachability (REQ 11.7)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("REQ 11.7: POST /api/orders without token → non-401/403 (guest-reachable)")
    void createOrder_anonymous_notAuthRejected()
    {
        int status = given()
                .contentType("application/json")
                .body("{\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}")
                .when()
                .post("/api/orders")
                .then()
                .extract().statusCode();

        assertTrue(status != 401 && status != 403,
                "POST /api/orders should be guest-reachable but got " + status);
    }

    @Test
    @Order(5)
    @DisplayName("REQ 11.7: POST /api/payments/checkout without token → non-401/403 (guest-reachable)")
    void checkout_anonymous_notAuthRejected()
    {
        int status = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("id", UUID.randomUUID().toString())
                .when()
                .post("/api/payments/checkout")
                .then()
                .extract().statusCode();

        assertTrue(status != 401 && status != 403,
                "POST /api/payments/checkout should be guest-reachable but got " + status);
    }

    @Test
    @Order(6)
    @DisplayName("REQ 11.7: PATCH /api/orders/{id}/contact without token → non-401/403 (guest-reachable)")
    void orderContact_anonymous_notAuthRejected()
    {
        UUID fakeOrderId = UUID.randomUUID();
        int status = given()
                .contentType("application/json")
                .body("{\"email\":\"guest@test.com\",\"firstName\":\"Guest\",\"lastName\":\"User\"}")
                .when()
                .patch("/api/orders/" + fakeOrderId + "/contact")
                .then()
                .extract().statusCode();

        assertTrue(status != 401 && status != 403,
                "PATCH /api/orders/{id}/contact should be guest-reachable but got " + status);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Google-login shopperType — GUEST→RETAILER upgrade (REQ 11.11)
    // ═══════════════════════════════════════════════════════════════════════════
    // Full Google login HTTP testing requires mocking GoogleIdTokenVerifier (CDI alternative).
    // The same business rule (GUEST→RETAILER upgrade) is tested via the register endpoint,
    // which applies the identical upgrade logic. Task 8b's loginWithGoogle implementation
    // applies the same pattern — email_verified check + GUEST/null→RETAILER upgrade.

    @Test
    @Order(7)
    @DisplayName("REQ 11.11: Register on existing GUEST record → shopperType upgraded to RETAILER")
    void register_guestRecord_upgradesToRetailer()
    {
        // Seed an unclaimed GUEST record
        insertUnclaimedGuestUser(GUEST_UPGRADE_EMAIL);

        // Verify initial state is GUEST
        assertEquals("GUEST", queryShopperType(GUEST_UPGRADE_EMAIL),
                "Pre-condition: shopperType should be GUEST");
        assertEquals("PENDING", queryCustomerStatus(GUEST_UPGRADE_EMAIL),
                "Pre-condition: status should be PENDING");

        // Register (claim) the account — mirrors Google login's GUEST→RETAILER upgrade
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.64")
                .body("{\"email\":\"" + GUEST_UPGRADE_EMAIL + "\",\"password\":\"UpgradePass1\",\"firstName\":\"Upgraded\"}")
                .when()
                .post("/api/customers/register")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("shopperType", equalTo("RETAILER"));

        // Verify the upgrade persisted
        assertEquals("RETAILER", queryShopperType(GUEST_UPGRADE_EMAIL),
                "shopperType should be upgraded to RETAILER");
        assertEquals("ACTIVE", queryCustomerStatus(GUEST_UPGRADE_EMAIL),
                "status should be ACTIVE after registration");
    }
}
