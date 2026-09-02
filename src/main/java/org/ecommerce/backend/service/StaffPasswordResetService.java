package org.ecommerce.backend.service;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.exception.InvalidPasswordResetCodeException;
import org.ecommerce.backend.exception.PasswordResetLockedException;
import org.ecommerce.backend.utils.PasswordStrengthValidator;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.repository.StaffRepository;

import java.time.OffsetDateTime;

/**
 * Self-service, email-OTP password reset for staff accounts — the flow a staff member
 * reaches from the sign-in screen when they cannot log in at all, distinct from the
 * forced-change flow ({@code staff_users.reset_password}), which only applies to an
 * account that can still authenticate. Mirrors {@link CustomerPasswordResetService}'s
 * shape via the shared {@link PasswordResetCodePolicy}; unlike that service, this class
 * has no in-memory IP-lockout map — it only tracks the DB-persisted account-level
 * attempt counter and lockout.
 */
@Slf4j
@ApplicationScoped
public class StaffPasswordResetService
{
    private static final int LOCKOUT_MINUTES = 15;

    @Inject
    PasswordResetCodePolicy policy;

    @Inject
    PasswordResetNotificationService passwordResetNotificationService;

    @Inject
    StaffRepository staffRepository;

    @Transactional
    public void initiateReset(String email)
    {
        if (email == null || email.isBlank()) {
            return;
        }

        String normalizedEmail = email.trim();
        StaffUserEntity user = staffRepository.findByEmail(normalizedEmail);
        if (user == null || !user.isActive()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (!policy.isExpired(user.getPasswordResetCodeExpiry(), now)) {
            // An unexpired code already exists — the cooldown silently suppresses a
            // resend rather than answering "already sent", so it cannot be used to
            // confirm the account exists (Requirement 2.2).
            return;
        }

        String rawCode = policy.generateCode();
        user.setPasswordResetCodeHash(policy.fingerprint(rawCode));
        user.setPasswordResetCodeExpiry(now.plusMinutes(policy.ttlMinutes()));
        user.setPasswordResetCodeAttempts(0);
        user.setPasswordResetCodeLockedUntil(null);

        passwordResetNotificationService.sendResetCode(user.getEmail(), rawCode, policy.ttlMinutes());
    }

    // dontRollbackOn is required, not stylistic: both exceptions are thrown AFTER this
    // method writes attempt-counter/lockout state that must survive — the JTA default
    // is to mark any unchecked exception's transaction rollback-only, which would
    // silently discard that write on every single failed attempt, making the 3-attempt
    // lockout structurally unable to ever trigger against real persistence (found via
    // StaffPasswordResetIT; PanacheMock-based unit tests cannot see this, since they
    // have no real transaction to roll back).
    @Transactional(dontRollbackOn = {InvalidPasswordResetCodeException.class, PasswordResetLockedException.class})
    public void completeReset(String email, String code, String newPassword, String clientIp)
    {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }
        PasswordStrengthValidator.validate(newPassword);

        String normalizedEmail = email == null ? "" : email.trim();
        StaffUserEntity user = staffRepository.findByEmail(normalizedEmail);
        if (user == null) {
            log.warn("Staff password reset attempted for unknown email={}, ip={}",
                    maskKey(normalizedEmail), clientIp);
            throw new InvalidPasswordResetCodeException();
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (policy.isLocked(user.getPasswordResetCodeLockedUntil(), now)) {
            throw new PasswordResetLockedException(user.getPasswordResetCodeLockedUntil());
        }

        boolean validCode = !policy.isExpired(user.getPasswordResetCodeExpiry(), now)
                && policy.matches(code, user.getPasswordResetCodeHash());

        if (!validCode) {
            int attempts = user.getPasswordResetCodeAttempts() + 1;
            user.setPasswordResetCodeAttempts(attempts);
            if (policy.shouldLock(attempts)) {
                user.setPasswordResetCodeLockedUntil(now.plusMinutes(LOCKOUT_MINUTES));
                user.setPasswordResetCodeAttempts(0);
            }
            log.warn("Staff password reset failed: invalid or expired code, email={}, ip={}",
                    maskKey(normalizedEmail), clientIp);

            // Mirrors CustomerPasswordResetService: the attempt that TRIGGERS the lock
            // reports the lockout itself, not a generic invalid-code error — the caller
            // learns why every subsequent attempt will also fail.
            if (policy.isLocked(user.getPasswordResetCodeLockedUntil(), now)) {
                throw new PasswordResetLockedException(user.getPasswordResetCodeLockedUntil());
            }
            throw new InvalidPasswordResetCodeException();
        }

        user.setPasswordHash(BcryptUtil.bcryptHash(newPassword));
        user.setResetPassword(false);
        user.setPasswordResetCodeHash(null);
        user.setPasswordResetCodeExpiry(null);
        user.setPasswordResetCodeAttempts(0);
        user.setPasswordResetCodeLockedUntil(null);
    }

    /**
     * Masks email-shaped keys for logging so denial logs carry no plaintext PII:
     * the local part is reduced to its first character (e.g. {@code j***@example.com}).
     * Non-email keys pass through unchanged.
     */
    private static String maskKey(String key)
    {
        if (key == null) {
            return null;
        }
        int at = key.indexOf('@');
        if (at < 0) {
            return key;
        }
        String maskedLocal = at == 0 ? "***" : key.charAt(0) + "***";
        return maskedLocal + key.substring(at);
    }
}
