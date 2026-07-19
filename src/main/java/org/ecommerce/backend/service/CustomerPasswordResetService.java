package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.exception.InvalidPasswordResetCodeException;
import org.ecommerce.backend.exception.PasswordResetLockedException;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class CustomerPasswordResetService {

    private static final int RESET_CODE_LENGTH = 6;
    private static final int RESET_CODE_EXPIRY_MINUTES = 10;
    private static final int MAX_INVALID_ATTEMPTS = 3;
    private static final int LOCKOUT_MINUTES = 15;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<String, IpAttemptState> ipAttemptStateMap = new ConcurrentHashMap<>();

    @Inject
    PasswordResetNotificationService passwordResetNotificationService;

    private static class IpAttemptState {
        int failedAttempts;
        OffsetDateTime lockedUntil;
    }

    @Transactional
    public void initiatePasswordReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        UserEntity user = UserEntity.findByEmail(email.trim());
        if (user != null) {
            String token = UUID.randomUUID().toString();
            user.resetToken = token;
            user.resetTokenExpiry = OffsetDateTime.now().plusMinutes(20);

            // Keep generic caller response; notification transport can be upgraded later.
            passwordResetNotificationService.sendResetLink(user.email, token);
        }
    }

    @Transactional
    public void initiatePasswordResetCode(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        String normalizedEmail = email.trim();
        UserEntity user = UserEntity.findByEmail(normalizedEmail);
        if (user == null || user.customer == null) {
            return;
        }

        String rawCode = generateResetCode();
        OffsetDateTime now = OffsetDateTime.now();

        log.debug("Password Reset {}", rawCode);
        user.passwordResetCodeHash = PasswordHashUtil.hash(rawCode);
        user.passwordResetCodeExpiry = now.plusMinutes(RESET_CODE_EXPIRY_MINUTES);
        user.passwordResetCodeAttempts = 0;
        user.passwordResetCodeLockedUntil = null;

        passwordResetNotificationService.sendResetCode(user.email, rawCode, RESET_CODE_EXPIRY_MINUTES);
    }

    @Transactional
    public void verifyPasswordResetCode(String email, String code, String clientIp) {
        verifyCodeInternal(email, code, clientIp);
    }

    @Transactional
    public void completePasswordResetWithCode(String email, String code, String newPassword, String clientIp) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }

        UserEntity user = verifyCodeInternal(email, code, clientIp);
        user.passwordHash = PasswordHashUtil.hash(newPassword);
        user.lastLogin = OffsetDateTime.now();

        activateCustomerProfile(user.customer);

        user.passwordResetCodeHash = null;
        user.passwordResetCodeExpiry = null;
        user.passwordResetCodeAttempts = 0;
        user.passwordResetCodeLockedUntil = null;
    }

    @Transactional
    public void completePasswordReset(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }

        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }

        UserEntity user = UserEntity.findByResetToken(token);
        if (user == null || user.resetTokenExpiry == null || user.resetTokenExpiry.isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Invalid or expired reset token");
        }

        user.passwordHash = PasswordHashUtil.hash(newPassword);
        user.lastLogin = OffsetDateTime.now();

        activateCustomerProfile(user.customer);

        user.resetToken = null;
        user.resetTokenExpiry = null;
    }

    /**
     * A completed reset makes the account log-in-capable, so the linked profile must
     * leave PENDING and must not remain GUEST — that tier is anonymous-checkout only.
     * WHOLESALER is never downgraded.
     */
    private static void activateCustomerProfile(CustomerEntity customer) {
        if (customer == null) {
            return;
        }
        if (customer.status == null || customer.status == CustomerStatusEn.PENDING) {
            customer.status = CustomerStatusEn.ACTIVE;
        }
        if (customer.shopperType == null || customer.shopperType == CustomerTypeEn.GUEST) {
            customer.shopperType = CustomerTypeEn.RETAILER;
        }
    }

    private UserEntity verifyCodeInternal(String email, String code, String clientIp) {
        String normalizedIp = normalizeIp(clientIp);
        OffsetDateTime now = OffsetDateTime.now();

        ensureIpNotLocked(normalizedIp, now);

        String normalizedEmail = email == null ? "" : email.trim();
        UserEntity user = UserEntity.findByEmail(normalizedEmail);
        if (user == null) {
            registerIpFailure(normalizedIp, now);
            throw new InvalidPasswordResetCodeException();
        }

        ensureAccountNotLocked(user, now);

        boolean validCode = isValidCodeFormat(code)
                && user.passwordResetCodeHash != null
                && user.passwordResetCodeExpiry != null
                && !user.passwordResetCodeExpiry.isBefore(now)
                && PasswordHashUtil.hash(code.trim()).equals(user.passwordResetCodeHash);

        if (!validCode) {
            registerFailure(user, normalizedIp, now);
            throw new InvalidPasswordResetCodeException();
        }

        user.passwordResetCodeAttempts = 0;
        user.passwordResetCodeLockedUntil = null;
        clearIpFailure(normalizedIp);
        return user;
    }

    private void ensureAccountNotLocked(UserEntity user, OffsetDateTime now) {
        if (user.passwordResetCodeLockedUntil != null && user.passwordResetCodeLockedUntil.isAfter(now)) {
            throw new PasswordResetLockedException(user.passwordResetCodeLockedUntil);
        }
        if (user.passwordResetCodeLockedUntil != null && !user.passwordResetCodeLockedUntil.isAfter(now)) {
            user.passwordResetCodeLockedUntil = null;
            user.passwordResetCodeAttempts = 0;
        }
    }

    private void ensureIpNotLocked(String ip, OffsetDateTime now) {
        IpAttemptState state = ipAttemptStateMap.get(ip);
        if (state == null) {
            return;
        }
        if (state.lockedUntil != null && state.lockedUntil.isAfter(now)) {
            throw new PasswordResetLockedException(state.lockedUntil);
        }
        if (state.lockedUntil != null && !state.lockedUntil.isAfter(now)) {
            ipAttemptStateMap.remove(ip);
        }
    }

    private void registerFailure(UserEntity user, String ip, OffsetDateTime now) {
        user.passwordResetCodeAttempts = user.passwordResetCodeAttempts + 1;
        if (user.passwordResetCodeAttempts >= MAX_INVALID_ATTEMPTS) {
            OffsetDateTime lockedUntil = now.plusMinutes(LOCKOUT_MINUTES);
            user.passwordResetCodeLockedUntil = lockedUntil;
            user.passwordResetCodeAttempts = 0;
        }

        registerIpFailure(ip, now);

        if (user.passwordResetCodeLockedUntil != null && user.passwordResetCodeLockedUntil.isAfter(now)) {
            throw new PasswordResetLockedException(user.passwordResetCodeLockedUntil);
        }
    }

    private void registerIpFailure(String ip, OffsetDateTime now) {
        IpAttemptState state = ipAttemptStateMap.computeIfAbsent(ip, ignored -> new IpAttemptState());
        state.failedAttempts = state.failedAttempts + 1;
        if (state.failedAttempts >= MAX_INVALID_ATTEMPTS) {
            state.lockedUntil = now.plusMinutes(LOCKOUT_MINUTES);
            state.failedAttempts = 0;
            throw new PasswordResetLockedException(state.lockedUntil);
        }
    }

    private void clearIpFailure(String ip) {
        ipAttemptStateMap.remove(ip);
    }

    private static String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim();
    }

    private static String generateResetCode() {
        int floor = (int) Math.pow(10, RESET_CODE_LENGTH - 1);
        int bound = floor * 9;
        int code = floor + SECURE_RANDOM.nextInt(bound);
        return String.format("%06d", code);
    }

    private static boolean isValidCodeFormat(String code) {
        if (code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.length() != RESET_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

}
