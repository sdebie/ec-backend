package org.ecommerce.backend.api;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.StaffRoleEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that {@code ForcedPasswordResetIdentityAugmentor} enforces both
 * staff-account-state gates server-side — not just redirected client-side. A
 * forced-reset account is blocked from everything except the two endpoints that
 * flow requires: self-service reset and {@code /admin/me}. A deactivated account is
 * blocked from those two as well — it has no self-service endpoint left to reach.
 * Uses self-signed JWTs (the established house pattern for staff/admin tests — see
 * AuthorizationIT/OrderAdminResourceAuthIT) against real, seeded StaffUserEntity
 * rows, since the augmentor re-checks the database on every request rather than
 * trusting a token claim.
 */
@QuarkusTest
class ForcedPasswordResetEnforcementIT
{
    @Inject
    EntityManager em;

    private static final String FLAGGED_EMAIL = "pwreset-flagged@test.com";
    private static final String NORMAL_EMAIL = "pwreset-normal@test.com";
    private static final String FLIP_EMAIL = "pwreset-flip@test.com";
    private static final String GHOST_EMAIL = "pwreset-ghost@test.com";
    private static final String DEACTIVATED_EMAIL = "pwreset-deactivated@test.com";
    private static final String DEACTIVATED_AND_FLAGGED_EMAIL = "pwreset-deactivated-flagged@test.com";
    private static final String REACTIVATE_EMAIL = "pwreset-reactivate@test.com";

    private static final String ADMIN_ORDER_LIST_BODY =
            "{\"query\":\"{ adminOrderList(pageIndex: 0, pageSize: 1) { totalElements } }\"}";

    @Transactional
    void deleteByEmail(String email)
    {
        StaffUserEntity.delete("lower(email) = lower(?1)", email);
    }

    @Transactional
    void seedStaff(String email, boolean resetPassword)
    {
        seedStaff(email, resetPassword, true);
    }

    @Transactional
    void seedStaff(String email, boolean resetPassword, boolean active)
    {
        StaffUserEntity user = new StaffUserEntity();
        user.setEmail(email);
        user.setPasswordHash(BcryptUtil.bcryptHash("Sup3rSecret!"));
        user.setFullName("Test Staff");
        user.setRole(StaffRoleEn.SUPER_ADMIN);
        user.setActive(active);
        user.setResetPassword(resetPassword);
        user.setCreatedAt(LocalDateTime.now());
        StaffUserEntity.persist(user);
    }

    @Transactional
    void setResetPassword(String email, boolean resetPassword)
    {
        StaffUserEntity.findByEmail(email).setResetPassword(resetPassword);
    }

    @Transactional
    void setActive(String email, boolean active)
    {
        StaffUserEntity.findByEmail(email).setActive(active);
    }

    @Transactional
    String queryPasswordHash(String email)
    {
        return StaffUserEntity.findByEmail(email).getPasswordHash();
    }

    @BeforeEach
    void cleanBefore()
    {
        deleteByEmail(FLAGGED_EMAIL);
        deleteByEmail(NORMAL_EMAIL);
        deleteByEmail(FLIP_EMAIL);
        deleteByEmail(GHOST_EMAIL);
        deleteByEmail(DEACTIVATED_EMAIL);
        deleteByEmail(DEACTIVATED_AND_FLAGGED_EMAIL);
        deleteByEmail(REACTIVATE_EMAIL);
    }

    @AfterEach
    void cleanAfter()
    {
        deleteByEmail(FLAGGED_EMAIL);
        deleteByEmail(NORMAL_EMAIL);
        deleteByEmail(FLIP_EMAIL);
        deleteByEmail(GHOST_EMAIL);
        deleteByEmail(DEACTIVATED_EMAIL);
        deleteByEmail(DEACTIVATED_AND_FLAGGED_EMAIL);
        deleteByEmail(REACTIVATE_EMAIL);
    }

    private static String staffJwt(String email, String role)
    {
        return Jwt.issuer("http://localhost:8080")
                .subject(email)
                .groups(role)
                .claim("full_name", "Test Staff")
                .expiresIn(Duration.ofHours(8))
                .sign();
    }

    @Test
    void flaggedStaff_blockedFromProtectedGraphQLQuery()
    {
        seedStaff(FLAGGED_EMAIL, true);

        given()
                .header("Authorization", "Bearer " + staffJwt(FLAGGED_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body(ADMIN_ORDER_LIST_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    void flaggedStaff_blockedFromProtectedRestEndpoint()
    {
        seedStaff(FLAGGED_EMAIL, true);

        given()
                .header("Authorization", "Bearer " + staffJwt(FLAGGED_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(403);
    }

    @Test
    void flaggedStaff_canStillReachAdminMeAndItReportsTheFlag()
    {
        seedStaff(FLAGGED_EMAIL, true);

        given()
                .header("Authorization", "Bearer " + staffJwt(FLAGGED_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/me")
                .then()
                .statusCode(200)
                .body("resetPassword", equalTo(true));
    }

    @Test
    void flaggedStaff_canStillResetOwnPassword()
    {
        seedStaff(FLAGGED_EMAIL, true);
        String originalHash = queryPasswordHash(FLAGGED_EMAIL);

        given()
                .header("Authorization", "Bearer " + staffJwt(FLAGGED_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body("{\"email\":\"" + FLAGGED_EMAIL + "\",\"password\":\"NewPassw0rd!\",\"confirmPassword\":\"NewPassw0rd!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(200);

        assertNotEquals(originalHash, queryPasswordHash(FLAGGED_EMAIL),
                "Self-service reset must still take effect while flagged — that's the whole point of the flag");
    }

    @Test
    void flaggedSuperAdmin_cannotResetAnotherAccountsPassword()
    {
        seedStaff(FLAGGED_EMAIL, true);
        seedStaff(NORMAL_EMAIL, false);
        String targetOriginalHash = queryPasswordHash(NORMAL_EMAIL);

        given()
                .header("Authorization", "Bearer " + staffJwt(FLAGGED_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body("{\"email\":\"" + NORMAL_EMAIL + "\",\"password\":\"NewPassw0rd!\",\"confirmPassword\":\"NewPassw0rd!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(403);

        assertEquals(targetOriginalHash, queryPasswordHash(NORMAL_EMAIL),
                "A flagged SUPER_ADMIN's admin-reset-another-account power must be suspended too, not just their other endpoints");
    }

    @Test
    void nonFlaggedStaff_unaffectedByTheGuard()
    {
        seedStaff(NORMAL_EMAIL, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(NORMAL_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body(ADMIN_ORDER_LIST_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null));

        given()
                .header("Authorization", "Bearer " + staffJwt(NORMAL_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(200);
    }

    @Test
    void unknownStaffEmail_passesThroughUnchanged()
    {
        // No seeded row for GHOST_EMAIL. Deliberately NOT fail-closed here — see
        // ForcedPasswordResetIdentityAugmentor's javadoc: this mirrors the established
        // self-signed-JWT-without-a-DB-row convention used throughout the staff/admin
        // test suite (AuthorizationIT, OrderAdminResourceAuthIT, etc.) for pure
        // role-matrix tests, which failing closed here would otherwise break.
        given()
                .header("Authorization", "Bearer " + staffJwt(GHOST_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body(ADMIN_ORDER_LIST_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null));
    }

    @Test
    void flagClearedMidSession_restoresAccessOnTheNextRequestWithTheSameToken()
    {
        seedStaff(FLIP_EMAIL, true);
        String token = staffJwt(FLIP_EMAIL, "SUPER_ADMIN");

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(403);

        // The flag changes server-side mid-session (e.g. the staff member completed
        // the reset through some other channel) — the SAME already-issued token is
        // reused, proving this isn't a claim baked in at login.
        setResetPassword(FLIP_EMAIL, false);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(200);
    }

    @Test
    void deactivatedStaff_blockedFromProtectedGraphQLQuery()
    {
        seedStaff(DEACTIVATED_EMAIL, false, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(DEACTIVATED_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body(ADMIN_ORDER_LIST_BODY)
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].extensions.code", equalTo("forbidden"));
    }

    @Test
    void deactivatedStaff_blockedFromProtectedRestEndpoint()
    {
        seedStaff(DEACTIVATED_EMAIL, false, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(DEACTIVATED_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(403);
    }

    @Test
    void deactivatedStaff_cannotReachAdminMeEither()
    {
        // Unlike a forced reset, a deactivated account has no self-service endpoint
        // it should still reach — this is the behaviour that actually distinguishes
        // deactivation from a forced reset, so it gets its own explicit case rather
        // than being implied by the two above.
        seedStaff(DEACTIVATED_EMAIL, false, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(DEACTIVATED_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/me")
                .then()
                .statusCode(403);
    }

    @Test
    void deactivatedStaff_cannotResetOwnPasswordEither()
    {
        seedStaff(DEACTIVATED_EMAIL, false, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(DEACTIVATED_EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body("{\"email\":\"" + DEACTIVATED_EMAIL + "\",\"password\":\"NewPassw0rd!\",\"confirmPassword\":\"NewPassw0rd!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(403);
    }

    @Test
    void deactivatedAndFlaggedForReset_deactivationWinsOutright()
    {
        // Both axes true at once: deactivation must take priority over the
        // reset-required sentinel, not downgrade to it — proven end to end via the
        // one endpoint a flagged-but-active account would otherwise still reach.
        seedStaff(DEACTIVATED_AND_FLAGGED_EMAIL, true, false);

        given()
                .header("Authorization", "Bearer " + staffJwt(DEACTIVATED_AND_FLAGGED_EMAIL, "SUPER_ADMIN"))
                .when()
                .get("/api/admin/me")
                .then()
                .statusCode(403);
    }

    @Test
    void reactivatedMidSession_restoresAccessOnTheNextRequestWithTheSameToken()
    {
        seedStaff(REACTIVATE_EMAIL, false, false);
        String token = staffJwt(REACTIVATE_EMAIL, "SUPER_ADMIN");

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(403);

        // Re-activated server-side (e.g. offboarding reversed) — the SAME
        // already-issued token is reused, proving this is re-checked live rather
        // than decided once and cached for the token's life.
        setActive(REACTIVATE_EMAIL, true);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/admin/testimonials")
                .then()
                .statusCode(200);
    }
}
