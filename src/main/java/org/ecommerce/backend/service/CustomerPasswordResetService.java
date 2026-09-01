package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.exception.InvalidPasswordResetCodeException;
import org.ecommerce.backend.exception.PasswordResetLockedException;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.backend.utils.PasswordStrengthValidator;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.repository.UserRepository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class CustomerPasswordResetService
{
    private static final int LOCKOUT_MINUTES = 15;

    private final Map<String, IpAttemptState> ipAttemptStateMap = new ConcurrentHashMap<>();

    @Inject
    PasswordResetCodePolicy policy;

    @Inject
    PasswordResetNotificationService passwordResetNotificationService;

    @Inject
    UserRepository userRepository;

    private static class IpAttemptState
    {
        int failedAttempts;
        OffsetDateTime lockedUntil;
    }

    @Transactional
    public void initiatePasswordResetCode(String email)
    {
        if (email == null || email.isBlank()) {
            return;
        }

        String normalizedEmail = email.trim();
        UserEntity user = userRepository.findByEmail(normalizedEmail);
        if (user == null || user.getCustomer() == null) {
            return;
        }

        String rawCode = policy.generateCode();
        OffsetDateTime now = OffsetDateTime.now();

        log.debug("Password Reset {}", rawCode);
        user.setPasswordResetCodeHash(policy.fingerprint(rawCode));
        user.setPasswordResetCodeExpiry(now.plusMinutes(policy.ttlMinutes()));
        user.setPasswordResetCodeAttempts(0);
        user.setPasswordResetCodeLockedUntil(null);

        passwordResetNotificationService.sendResetCode(user.getEmail(), rawCode, policy.ttlMinutes());
    }

    // dontRollbackOn is required, not stylistic: both exceptions are thrown AFTER
    // verifyCodeInternal writes attempt-counter/lockout state that must survive — the
    // JTA default is to mark any unchecked exception's transaction rollback-only,
    // which silently discarded that write on every failed attempt, making the
    // 3-attempt lockout structurally unable to ever trigger against real persistence.
    // Invisible to CustomerPasswordResetServiceTest (PanacheMock has no real
    // transaction to roll back); caught by CustomerPasswordResetLockoutIT.
    @Transactional(dontRollbackOn = {InvalidPasswordResetCodeException.class, PasswordResetLockedException.class})
    public void verifyPasswordResetCode(String email, String code, String clientIp)
    {
        verifyCodeInternal(email, code, clientIp);
    }

    @Transactional(dontRollbackOn = {InvalidPasswordResetCodeException.class, PasswordResetLockedException.class})
    public void completePasswordResetWithCode(String email, String code, String newPassword, String clientIp)
    {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }
        PasswordStrengthValidator.validate(newPassword);

        UserEntity user = verifyCodeInternal(email, code, clientIp);
        user.setPasswordHash(CustomerPasswordHashUtil.hash(newPassword));
        user.setLastLogin(OffsetDateTime.now());

        activateCustomerProfile(user.getCustomer());

        user.setPasswordResetCodeHash(null);
        user.setPasswordResetCodeExpiry(null);
        user.setPasswordResetCodeAttempts(0);
        user.setPasswordResetCodeLockedUntil(null);
    }

    /**
     * A completed reset makes the account log-in-capable, so the linked profile must
     * leave PENDING and must not remain GUEST — that tier is anonymous-checkout only.
     * WHOLESALER is never downgraded.
     */
    private static void activateCustomerProfile(CustomerEntity customer)
    {
        if (customer == null) {
            return;
        }
        if (customer.getStatus() == null || customer.getStatus() == CustomerStatusEn.PENDING) {
            customer.setStatus(CustomerStatusEn.ACTIVE);
        }
        if (customer.getShopperType() == null || customer.getShopperType() == CustomerTypeEn.GUEST) {
            customer.setShopperType(CustomerTypeEn.RETAILER);
        }
    }

    private UserEntity verifyCodeInternal(String email, String code, String clientIp)
    {
        String normalizedIp = normalizeIp(clientIp);
        OffsetDateTime now = OffsetDateTime.now();

        ensureIpNotLocked(normalizedIp, now);

        String normalizedEmail = email == null ? "" : email.trim();
        UserEntity user = userRepository.findByEmail(normalizedEmail);
        if (user == null) {
            registerIpFailure(normalizedIp, now);
            throw new InvalidPasswordResetCodeException();
        }

        ensureAccountNotLocked(user, now);

        boolean validCode = !policy.isExpired(user.getPasswordResetCodeExpiry(), now)
                && policy.matches(code, user.getPasswordResetCodeHash());

        if (!validCode) {
            registerFailure(user, normalizedIp, now);
            throw new InvalidPasswordResetCodeException();
        }

        user.setPasswordResetCodeAttempts(0);
        user.setPasswordResetCodeLockedUntil(null);
        clearIpFailure(normalizedIp);
        return user;
    }

    private void ensureAccountNotLocked(UserEntity user, OffsetDateTime now)
    {
        OffsetDateTime lockedUntil = user.getPasswordResetCodeLockedUntil();
        if (lockedUntil == null) {
            return;
        }
        if (policy.isLocked(lockedUntil, now)) {
            throw new PasswordResetLockedException(lockedUntil);
        }
        user.setPasswordResetCodeLockedUntil(null);
        user.setPasswordResetCodeAttempts(0);
    }

    private void ensureIpNotLocked(String ip, OffsetDateTime now)
    {
        IpAttemptState state = ipAttemptStateMap.get(ip);
        if (state == null) {
            return;
        }
        if (policy.isLocked(state.lockedUntil, now)) {
            throw new PasswordResetLockedException(state.lockedUntil);
        }
        if (state.lockedUntil != null) {
            ipAttemptStateMap.remove(ip);
        }
    }

    private void registerFailure(UserEntity user, String ip, OffsetDateTime now)
    {
        int attempts = user.getPasswordResetCodeAttempts() + 1;
        user.setPasswordResetCodeAttempts(attempts);
        if (policy.shouldLock(attempts)) {
            OffsetDateTime lockedUntil = now.plusMinutes(LOCKOUT_MINUTES);
            user.setPasswordResetCodeLockedUntil(lockedUntil);
            user.setPasswordResetCodeAttempts(0);
        }

        registerIpFailure(ip, now);

        if (user.getPasswordResetCodeLockedUntil() != null && user.getPasswordResetCodeLockedUntil().isAfter(now)) {
            throw new PasswordResetLockedException(user.getPasswordResetCodeLockedUntil());
        }
    }

    private void registerIpFailure(String ip, OffsetDateTime now)
    {
        IpAttemptState state = ipAttemptStateMap.computeIfAbsent(ip, ignored -> new IpAttemptState());
        state.failedAttempts = state.failedAttempts + 1;
        if (policy.shouldLock(state.failedAttempts)) {
            state.lockedUntil = now.plusMinutes(LOCKOUT_MINUTES);
            state.failedAttempts = 0;
            throw new PasswordResetLockedException(state.lockedUntil);
        }
    }

    private void clearIpFailure(String ip)
    {
        ipAttemptStateMap.remove(ip);
    }

    private static String normalizeIp(String clientIp)
    {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim();
    }

}
