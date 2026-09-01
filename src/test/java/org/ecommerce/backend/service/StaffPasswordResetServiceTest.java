package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.exception.InvalidPasswordResetCodeException;
import org.ecommerce.backend.exception.PasswordResetLockedException;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.StaffRoleEn;
import org.ecommerce.common.repository.StaffRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class StaffPasswordResetServiceTest
{
    private static final String EMAIL = "staffer@example.com";
    private static final String RESET_CODE = "654321";
    private static final String CLIENT_IP = "203.0.113.20";

    @Inject
    StaffPasswordResetService staffPasswordResetService;

    @Inject
    PasswordResetCodePolicy policy;

    @InjectMock
    PasswordResetNotificationService passwordResetNotificationService;

    @InjectMock
    StaffRepository staffRepository;

    private static StaffUserEntity activeStaff()
    {
        StaffUserEntity user = new StaffUserEntity();
        user.setEmail(EMAIL);
        user.setPasswordHash("$2a$10$originalHash");
        user.setFullName("Test Staffer");
        user.setRole(StaffRoleEn.VIEWER);
        user.setActive(true);
        user.setResetPassword(false);
        return user;
    }

    private StaffUserEntity staffWithValidResetCode()
    {
        StaffUserEntity user = activeStaff();
        user.setPasswordResetCodeHash(policy.fingerprint(RESET_CODE));
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().plusMinutes(5));
        return user;
    }

    // ── initiateReset ────────────────────────────────────────────────────────

    @Test
    void initiateReset_activeStaff_sendsCodeAndStoresFingerprint()
    {
        StaffUserEntity user = activeStaff();
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        staffPasswordResetService.initiateReset(EMAIL);

        assertNotNull(user.getPasswordResetCodeHash());
        assertNotNull(user.getPasswordResetCodeExpiry());
        assertEquals(0, user.getPasswordResetCodeAttempts());
        assertNull(user.getPasswordResetCodeLockedUntil());
        verify(passwordResetNotificationService).sendResetCode(eq(EMAIL), matches("\\d{6}"), eq(5));
    }

    @Test
    void initiateReset_unknownEmail_isSilentNoOp()
    {
        when(staffRepository.findByEmail(EMAIL)).thenReturn(null);

        assertDoesNotThrow(() -> staffPasswordResetService.initiateReset(EMAIL));

        verifyNoInteractions(passwordResetNotificationService);
    }

    @Test
    void initiateReset_inactiveStaff_isSilentNoOp()
    {
        StaffUserEntity user = activeStaff();
        user.setActive(false);
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        staffPasswordResetService.initiateReset(EMAIL);

        assertNull(user.getPasswordResetCodeHash(), "an inactive account must never receive a code");
        verifyNoInteractions(passwordResetNotificationService);
    }

    @Test
    void initiateReset_unexpiredCodeAlreadyExists_cooldownSuppressesResend()
    {
        StaffUserEntity user = staffWithValidResetCode();
        String originalHash = user.getPasswordResetCodeHash();
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        staffPasswordResetService.initiateReset(EMAIL);

        assertEquals(originalHash, user.getPasswordResetCodeHash(), "a live code must not be replaced");
        verifyNoInteractions(passwordResetNotificationService);
    }

    @Test
    void initiateReset_expiredCode_issuesFreshCode()
    {
        StaffUserEntity user = activeStaff();
        String staleHash = policy.fingerprint("111111");
        user.setPasswordResetCodeHash(staleHash);
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().minusMinutes(1));
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        staffPasswordResetService.initiateReset(EMAIL);

        assertNotEquals(staleHash, user.getPasswordResetCodeHash());
        verify(passwordResetNotificationService).sendResetCode(eq(EMAIL), matches("\\d{6}"), eq(5));
    }

    @Test
    void initiateReset_blankEmail_isSilentNoOp()
    {
        assertDoesNotThrow(() -> staffPasswordResetService.initiateReset("  "));
        verifyNoInteractions(passwordResetNotificationService);
    }

    // ── completeReset ────────────────────────────────────────────────────────

    @Test
    void completeReset_validCode_setsPasswordAndClearsOtpAndForcedResetFlag()
    {
        StaffUserEntity user = staffWithValidResetCode();
        user.setResetPassword(true);
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        staffPasswordResetService.completeReset(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP);

        assertTrue(user.getPasswordHash().startsWith("$2"), "must write a BCrypt hash");
        assertTrue(CustomerPasswordHashUtil.verify("newPassword1!", user.getPasswordHash()));
        assertFalse(user.isResetPassword(), "completing a reset clears the forced-change flag too");
        assertNull(user.getPasswordResetCodeHash());
        assertNull(user.getPasswordResetCodeExpiry());
        assertEquals(0, user.getPasswordResetCodeAttempts());
        assertNull(user.getPasswordResetCodeLockedUntil());
    }

    @Test
    void completeReset_wrongCode_throwsAndDoesNotChangePassword()
    {
        StaffUserEntity user = staffWithValidResetCode();
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);
        String originalHash = user.getPasswordHash();

        assertThrows(InvalidPasswordResetCodeException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, "000000", "newPassword1!", CLIENT_IP));

        assertEquals(originalHash, user.getPasswordHash());
        assertEquals(1, user.getPasswordResetCodeAttempts());
    }

    @Test
    void completeReset_expiredCode_throwsAndDoesNotChangePassword()
    {
        StaffUserEntity user = activeStaff();
        user.setPasswordResetCodeHash(policy.fingerprint(RESET_CODE));
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().minusSeconds(1));
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);
        String originalHash = user.getPasswordHash();

        assertThrows(InvalidPasswordResetCodeException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP));

        assertEquals(originalHash, user.getPasswordHash());
    }

    @Test
    void completeReset_thirdWrongAttempt_locksAccountFor15MinutesAndReportsTheLockoutItself()
    {
        StaffUserEntity user = staffWithValidResetCode();
        user.setPasswordResetCodeAttempts(2);
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);

        // The triggering attempt reports the lockout itself, not a generic invalid-code
        // error — mirrors CustomerPasswordResetService's registerFailure exactly.
        assertThrows(PasswordResetLockedException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, "000000", "newPassword1!", CLIENT_IP));

        assertNotNull(user.getPasswordResetCodeLockedUntil());
        assertTrue(user.getPasswordResetCodeLockedUntil().isAfter(OffsetDateTime.now().plusMinutes(14)));
        assertEquals(0, user.getPasswordResetCodeAttempts(), "attempt counter resets once locked");
    }

    @Test
    void completeReset_lockedAccount_rejectsWithoutComparingCode()
    {
        StaffUserEntity user = staffWithValidResetCode();
        user.setPasswordResetCodeLockedUntil(OffsetDateTime.now().plusMinutes(10));
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);
        String originalHash = user.getPasswordHash();
        int attemptsBefore = user.getPasswordResetCodeAttempts();

        assertThrows(PasswordResetLockedException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP));

        assertEquals(originalHash, user.getPasswordHash(), "locked-out even with the CORRECT code must not succeed");
        assertEquals(attemptsBefore, user.getPasswordResetCodeAttempts(), "a locked-out attempt must not itself count");
    }

    @Test
    void completeReset_unknownEmail_throwsSameExceptionAsWrongCode()
    {
        when(staffRepository.findByEmail(EMAIL)).thenReturn(null);

        assertThrows(InvalidPasswordResetCodeException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP));
    }

    @Test
    void completeReset_weakPassword_throwsAndDoesNotConsumeCode()
    {
        StaffUserEntity user = staffWithValidResetCode();
        when(staffRepository.findByEmail(EMAIL)).thenReturn(user);
        String storedHash = user.getPasswordResetCodeHash();

        assertThrows(IllegalArgumentException.class,
                () -> staffPasswordResetService.completeReset(EMAIL, RESET_CODE, "short", CLIENT_IP));

        assertEquals(storedHash, user.getPasswordResetCodeHash(), "a rejected weak password must not burn the code");
        assertEquals("$2a$10$originalHash", user.getPasswordHash());
    }
}
