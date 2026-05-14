package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

@ApplicationScoped
public class CustomerPasswordResetService {

    @Inject
    PasswordResetNotificationService passwordResetNotificationService;

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

        user.passwordHash = hashPassword(newPassword);
        user.lastLogin = OffsetDateTime.now();

        // Activate the linked customer profile if still in REGISTERING state
        CustomerEntity customer = user.customer;
        if (customer != null && (customer.status == null || customer.status == CustomerStatusEn.PENDING)) {
            customer.status = CustomerStatusEn.ACTIVE;
        }

        user.resetToken = null;
        user.resetTokenExpiry = null;
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
}
