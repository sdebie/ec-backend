package org.ecommerce.backend.api;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Rehash-on-login: a customer whose stored hash is still the legacy unsalted
 * SHA-256 digest (pre-dating {@link CustomerPasswordHashUtil}) must keep
 * authenticating, and a successful login must silently upgrade the stored hash
 * to BCrypt — without ever rehashing on a failed attempt.
 */
@QuarkusTest
class CustomerPasswordMigrationIT
{
    @Inject
    EntityManager em;

    private static final String CORRECT_EMAIL = "pwmig-correct@test.com";
    private static final String WRONG_EMAIL = "pwmig-wrong@test.com";
    private static final String PASSWORD = "LegacyPass1";

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
    void insertUserWithLegacyHash(String email)
    {
        UUID userId = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO users (id, email, password_hash, is_active, mfa_enabled, created_at, password_reset_code_attempts, roles) " +
                                "VALUES (:id, :email, :hash, true, false, now(), 0, '{RETAIL}')")
                .setParameter("id", userId)
                .setParameter("email", email)
                .setParameter("hash", PasswordHashUtil.hash(PASSWORD))
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO customers (id, user_id, first_name, last_name, status, shopper_type) " +
                                "VALUES (:id, :userId, 'Legacy', 'Customer', 'ACTIVE', 'RETAILER')")
                .setParameter("id", UUID.randomUUID())
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    String queryPasswordHash(String email)
    {
        return em.createNativeQuery("SELECT password_hash FROM users WHERE lower(email) = lower(:email)")
                .setParameter("email", email)
                .getSingleResult()
                .toString();
    }

    @BeforeEach
    void cleanBefore()
    {
        deleteByEmail(CORRECT_EMAIL);
        deleteByEmail(WRONG_EMAIL);
    }

    @AfterEach
    void cleanAfter()
    {
        deleteByEmail(CORRECT_EMAIL);
        deleteByEmail(WRONG_EMAIL);
    }

    @Test
    void login_correctPasswordAgainstLegacyHash_migratesToBcryptAndKeepsWorking()
    {
        insertUserWithLegacyHash(CORRECT_EMAIL);
        String legacyHash = queryPasswordHash(CORRECT_EMAIL);
        assertFalse(legacyHash.startsWith("$2"), "Precondition: seeded hash must be the legacy SHA-256 format");

        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.71")
                .body("{\"email\":\"" + CORRECT_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}")
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue());

        String migratedHash = queryPasswordHash(CORRECT_EMAIL);
        assertTrue(migratedHash.startsWith("$2"), "A successful login against a legacy hash must upgrade it to BCrypt");
        assertNotEquals(legacyHash, migratedHash);
        assertTrue(CustomerPasswordHashUtil.verify(PASSWORD, migratedHash),
                "The migrated hash must still authenticate the same password");

        // Second login, now against the already-migrated BCrypt hash — must not re-migrate.
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.72")
                .body("{\"email\":\"" + CORRECT_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}")
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue());

        String hashAfterSecondLogin = queryPasswordHash(CORRECT_EMAIL);
        assertEquals(migratedHash, hashAfterSecondLogin,
                "A login against an already-migrated BCrypt hash must not rehash again");
    }

    @Test
    void login_wrongPasswordAgainstLegacyHash_rejectedAndHashLeftUnmigrated()
    {
        insertUserWithLegacyHash(WRONG_EMAIL);
        String legacyHash = queryPasswordHash(WRONG_EMAIL);

        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "198.51.100.73")
                .body("{\"email\":\"" + WRONG_EMAIL + "\",\"password\":\"totallyWrongPassword\"}")
                .when()
                .post("/api/customers/login")
                .then()
                .statusCode(401);

        String hashAfterFailedLogin = queryPasswordHash(WRONG_EMAIL);
        assertEquals(legacyHash, hashAfterFailedLogin,
                "A failed login attempt must never rehash the stored password");
        assertFalse(hashAfterFailedLogin.startsWith("$2"));
    }
}
