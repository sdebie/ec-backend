package org.ecommerce.backend.api;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.service.PasswordResetNotificationService;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.StaffRoleEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * End-to-end coverage of the staff self-service OTP reset endpoints
 * ({@code /admin/auth/password-reset/initiate}, {@code /complete}), plus the
 * self-vs-other behaviour on the pre-existing {@code /admin/auth/reset-password} for
 * genuine non-SUPER_ADMIN roles (Requirement 6.4-6.6), which had no coverage of its
 * own beyond a flagged-SUPER_ADMIN edge case in {@code ForcedPasswordResetEnforcementIT}.
 * Real DB rows, tracked-ID cleanup (shared local database — KNOWN-LIMITATIONS §5).
 * The mail transport is mocked (matching {@code CustomerPasswordResetServiceTest}'s
 * pattern) so the real code can be captured and used to drive the completion step —
 * OTPs are one-way hashed at rest, so there is no other way to recover one in a test.
 */
@QuarkusTest
class StaffPasswordResetIT
{
    private static final String EMAIL = "otp-reset-staffer@test.com";
    private static final String OTHER_EMAIL = "otp-reset-other-staffer@test.com";
    private static final String INITIATE_PATH = "/api/admin/auth/password-reset/initiate";
    private static final String COMPLETE_PATH = "/api/admin/auth/password-reset/complete";

    @InjectMock
    PasswordResetNotificationService passwordResetNotificationService;

    @BeforeEach
    void setUp()
    {
        doNothing().when(passwordResetNotificationService).sendResetCode(anyString(), anyString(), anyInt());
        deleteByEmail(EMAIL);
        deleteByEmail(OTHER_EMAIL);
    }

    @AfterEach
    void tearDown()
    {
        deleteByEmail(EMAIL);
        deleteByEmail(OTHER_EMAIL);
    }

    @Transactional
    void deleteByEmail(String email)
    {
        StaffUserEntity.delete("lower(email) = lower(?1)", email);
    }

    @Transactional
    StaffUserEntity seedStaff(String email, StaffRoleEn role)
    {
        StaffUserEntity user = new StaffUserEntity();
        user.setEmail(email);
        user.setPasswordHash(BcryptUtil.bcryptHash("OriginalPassw0rd!"));
        user.setFullName("OTP Test Staffer");
        user.setRole(role);
        user.setActive(true);
        user.setResetPassword(false);
        user.setCreatedAt(LocalDateTime.now());
        StaffUserEntity.persist(user);
        return user;
    }

    @Transactional
    void seedExpiredCode(String email, String fingerprint)
    {
        StaffUserEntity user = StaffUserEntity.findByEmail(email);
        user.setPasswordResetCodeHash(fingerprint);
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().minusSeconds(1));
    }

    @Transactional
    void seedLiveCode(String email, String fingerprint, int attempts)
    {
        StaffUserEntity user = StaffUserEntity.findByEmail(email);
        user.setPasswordResetCodeHash(fingerprint);
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().plusMinutes(5));
        user.setPasswordResetCodeAttempts(attempts);
    }

    @Transactional
    String queryPasswordHash(String email)
    {
        return StaffUserEntity.findByEmail(email).getPasswordHash();
    }

    @Transactional
    Boolean queryResetPasswordFlag(String email)
    {
        return StaffUserEntity.findByEmail(email).isResetPassword();
    }

    private String captureIssuedCode(String email)
    {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordResetNotificationService).sendResetCode(eq(email), codeCaptor.capture(), anyInt());
        return codeCaptor.getValue();
    }

    // ── initiate: uniform response, real send ───────────────────────────────

    @Test
    void initiate_knownActiveStaff_sendsCodeAndReturns202()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\"}")
                .when()
                .post(INITIATE_PATH)
                .then()
                .statusCode(202)
                .body(blankOrNullString());

        assertNotNull(captureIssuedCode(EMAIL));
    }

    @Test
    void initiate_unknownEmail_returnsByteIdenticalResponseAndSendsNothing()
    {
        given()
                .contentType("application/json")
                .body("{\"email\":\"nobody-" + EMAIL + "\"}")
                .when()
                .post(INITIATE_PATH)
                .then()
                .statusCode(202)
                .body(blankOrNullString());

        verifyNoInteractions(passwordResetNotificationService);
    }

    @Test
    void initiate_inactiveStaff_returnsByteIdenticalResponseAndSendsNothing()
    {
        StaffUserEntity user = seedStaff(EMAIL, StaffRoleEn.VIEWER);
        setInactive(user.getEmail());

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\"}")
                .when()
                .post(INITIATE_PATH)
                .then()
                .statusCode(202)
                .body(blankOrNullString());

        verifyNoInteractions(passwordResetNotificationService);
    }

    @Transactional
    void setInactive(String email)
    {
        StaffUserEntity.findByEmail(email).setActive(false);
    }

    @Test
    void initiate_unexpiredCodeAlreadyExists_cooldownSuppressesResendButStillReturns202()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);

        given().contentType("application/json").body("{\"email\":\"" + EMAIL + "\"}")
                .when().post(INITIATE_PATH).then().statusCode(202);
        String firstCode = captureIssuedCode(EMAIL);

        given().contentType("application/json").body("{\"email\":\"" + EMAIL + "\"}")
                .when().post(INITIATE_PATH)
                .then().statusCode(202).body(blankOrNullString());

        // Still exactly one send — the second request was silently suppressed.
        verify(passwordResetNotificationService, org.mockito.Mockito.times(1))
                .sendResetCode(eq(EMAIL), anyString(), anyInt());
        assertNotNull(firstCode);
    }

    // ── complete: happy path + rejections ───────────────────────────────────

    @Test
    void completeReset_endToEnd_setsNewPasswordAndClearsForcedResetFlag()
    {
        StaffUserEntity user = seedStaff(EMAIL, StaffRoleEn.CATALOG_MANAGER);
        setForcedReset(user.getEmail(), true);
        String originalHash = queryPasswordHash(EMAIL);

        given().contentType("application/json").body("{\"email\":\"" + EMAIL + "\"}")
                .when().post(INITIATE_PATH).then().statusCode(202);
        String code = captureIssuedCode(EMAIL);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\",\"code\":\"" + code
                        + "\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(204);

        assertNotEquals(originalHash, queryPasswordHash(EMAIL));
        assertFalse(Boolean.TRUE.equals(queryResetPasswordFlag(EMAIL)),
                "a self-service reset must also clear the unrelated forced-change flag");
    }

    @Transactional
    void setForcedReset(String email, boolean value)
    {
        StaffUserEntity.findByEmail(email).setResetPassword(value);
    }

    @Test
    void completeReset_wrongCode_returns400AndDoesNotChangePassword()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);
        String originalHash = queryPasswordHash(EMAIL);

        given().contentType("application/json").body("{\"email\":\"" + EMAIL + "\"}")
                .when().post(INITIATE_PATH).then().statusCode(202);
        captureIssuedCode(EMAIL);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL
                        + "\",\"code\":\"000000\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(400);

        assertEquals(originalHash, queryPasswordHash(EMAIL));
    }

    @Test
    void completeReset_expiredCode_returns400()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);
        seedExpiredCode(EMAIL, sha("482913"));

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL
                        + "\",\"code\":\"482913\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(400);
    }

    @Test
    void completeReset_crossAccount_codeIssuedToOneEmailDoesNotResetAnother()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);
        seedStaff(OTHER_EMAIL, StaffRoleEn.VIEWER);
        String otherOriginalHash = queryPasswordHash(OTHER_EMAIL);

        given().contentType("application/json").body("{\"email\":\"" + EMAIL + "\"}")
                .when().post(INITIATE_PATH).then().statusCode(202);
        String codeIssuedToEmail = captureIssuedCode(EMAIL);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + OTHER_EMAIL + "\",\"code\":\"" + codeIssuedToEmail
                        + "\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(400);

        assertEquals(otherOriginalHash, queryPasswordHash(OTHER_EMAIL));
    }

    @Test
    void completeReset_thirdWrongAttempt_locksAccount()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);
        seedLiveCode(EMAIL, sha("482913"), 2);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL
                        + "\",\"code\":\"000000\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(429);

        // The account is now locked — even the CORRECT code is rejected.
        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL
                        + "\",\"code\":\"482913\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"BrandNewPassw0rd!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(429);
    }

    @Test
    void completeReset_passwordMismatch_returns400()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL
                        + "\",\"code\":\"482913\",\"newPassword\":\"BrandNewPassw0rd!\",\"confirmPassword\":\"Different!\"}")
                .when()
                .post(COMPLETE_PATH)
                .then()
                .statusCode(400);
    }

    // Fingerprints a code the same way PasswordResetCodePolicy does, without
    // depending on it directly — keeps this IT decoupled from the policy's
    // internal algorithm while still producing a value the real service accepts.
    // Uses the test-profile secret configured in application.properties.
    private static String sha(String rawCode)
    {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "test-only-hmac-secret-not-for-production".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(rawCode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── /admin/auth/reset-password: self-vs-other for GENUINE non-SUPER_ADMIN roles ──
    // (the existing ForcedPasswordResetEnforcementIT only covers a flagged SUPER_ADMIN;
    // no test previously exercised a real CATALOG_MANAGER/ORDER_MANAGER/VIEWER role.)

    @Test
    void resetPasswordEndpoint_nonSuperAdminRole_canResetOwnPassword()
    {
        seedStaff(EMAIL, StaffRoleEn.CATALOG_MANAGER);
        String originalHash = queryPasswordHash(EMAIL);

        given()
                .header("Authorization", "Bearer " + staffJwt(EMAIL, "CATALOG_MANAGER"))
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\",\"password\":\"NewOwnPassw0rd!\",\"confirmPassword\":\"NewOwnPassw0rd!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(200);

        assertNotEquals(originalHash, queryPasswordHash(EMAIL));
    }

    @Test
    void resetPasswordEndpoint_nonSuperAdminRole_cannotResetAnotherAccount()
    {
        seedStaff(EMAIL, StaffRoleEn.ORDER_MANAGER);
        seedStaff(OTHER_EMAIL, StaffRoleEn.VIEWER);
        String otherOriginalHash = queryPasswordHash(OTHER_EMAIL);

        given()
                .header("Authorization", "Bearer " + staffJwt(EMAIL, "ORDER_MANAGER"))
                .contentType("application/json")
                .body("{\"email\":\"" + OTHER_EMAIL + "\",\"password\":\"Hacked123!\",\"confirmPassword\":\"Hacked123!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(403);

        assertEquals(otherOriginalHash, queryPasswordHash(OTHER_EMAIL));
    }

    @Test
    void resetPasswordEndpoint_genuineSuperAdmin_canResetAnotherAccount()
    {
        seedStaff(EMAIL, StaffRoleEn.SUPER_ADMIN);
        seedStaff(OTHER_EMAIL, StaffRoleEn.VIEWER);
        String otherOriginalHash = queryPasswordHash(OTHER_EMAIL);

        given()
                .header("Authorization", "Bearer " + staffJwt(EMAIL, "SUPER_ADMIN"))
                .contentType("application/json")
                .body("{\"email\":\"" + OTHER_EMAIL + "\",\"password\":\"AdminSet123!\",\"confirmPassword\":\"AdminSet123!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(200);

        assertNotEquals(otherOriginalHash, queryPasswordHash(OTHER_EMAIL));
    }

    @Test
    void resetPasswordEndpoint_noAuthorizationHeader_isRejected()
    {
        seedStaff(EMAIL, StaffRoleEn.VIEWER);

        given()
                .contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\",\"password\":\"NoAuth123!\",\"confirmPassword\":\"NoAuth123!\"}")
                .when()
                .post("/api/admin/auth/reset-password")
                .then()
                .statusCode(anyOf401Or403());
    }

    private static org.hamcrest.Matcher<Integer> anyOf401Or403()
    {
        return org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403));
    }

    private static String staffJwt(String email, String role)
    {
        return io.smallrye.jwt.build.Jwt.issuer("http://localhost:8080")
                .subject(email)
                .groups(role)
                .claim("full_name", "Test Staff")
                .expiresIn(java.time.Duration.ofHours(8))
                .sign();
    }
}
