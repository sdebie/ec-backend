package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.enums.CustomerStatusEn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
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

        CustomerEntity customer = CustomerEntity.findByEmail(email.trim());
        if (customer != null) {
            String token = UUID.randomUUID().toString();
            customer.resetToken = token;
            customer.resetTokenExpiry = LocalDateTime.now().plusMinutes(20);

            // Keep generic caller response; notification transport can be upgraded later.
            passwordResetNotificationService.sendResetLink(customer.email, token);
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

        CustomerEntity customer = CustomerEntity.find("resetToken", token).firstResult();
        if (customer == null || customer.resetTokenExpiry == null || customer.resetTokenExpiry.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Invalid or expired reset token");
        }

        customer.passwordHash = hashPassword(newPassword);
        customer.passwordUpdatedAt = LocalDateTime.now();
        if (customer.status == null || customer.status == CustomerStatusEn.REGISTERING) {
            customer.status = CustomerStatusEn.ACTIVE;
        }
        customer.resetToken = null;
        customer.resetTokenExpiry = null;
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

