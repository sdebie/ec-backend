package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.mapper.CustomerProfileMapper;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.backend.utils.PasswordStrengthValidator;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.repository.CustomerRepository;
import org.ecommerce.common.repository.UserRepository;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Service for the Customer Portal — handles profile retrieval and password changes.
 */
@ApplicationScoped
public class CustomerPortalService
{
    @Inject
    CustomerProfileMapper customerProfileMapper;

    @Inject
    CustomerRepository customerRepository;

    @Inject
    UserRepository userRepository;

    private static final Logger LOG = Logger.getLogger(CustomerPortalService.class);

    /**
     * Resolves the customer by email and maps through {@link CustomerProfileMapper}.
     * Portal GET and PATCH {@code /profile} both return this so the shopper never sees
     * two shapes of the same record.
     *
     * @param email the customer's email (from JWT subject)
     * @return the fully mapped portal profile DTO
     * @throws WebApplicationException 404 if customer not found
     */
    public CustomerProfileDto getPortalProfile(String email)
    {
        CustomerEntity customer = customerRepository.findByEmail(email);
        if (customer == null) {
            LOG.warnf("Customer portal profile requested for unknown email: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Customer not found"))
                            .build());
        }

        return customerProfileMapper.toProfileDto(customer);
    }

    /**
     * Changes the customer's password after verifying the current one.
     *
     * @param email           the customer's email (from JWT subject)
     * @param currentPassword the current plain-text password to verify
     * @param newPassword     the new plain-text password to set
     * @throws WebApplicationException on validation or authentication failure
     */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword)
    {
        CustomerEntity customer = customerRepository.findByEmail(email);
        if (customer == null) {
            LOG.warnf("Password change attempted for unknown email: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Customer not found"))
                            .build());
        }

        UserEntity user = customer.getUser();

        // Check if the user has a local password set
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            LOG.warnf("Password change attempted for account with no local password: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "No local password is set for this account"))
                            .build());
        }

        // Verify current password
        if (!CustomerPasswordHashUtil.verify(currentPassword, user.getPasswordHash())) {
            LOG.warnf("Incorrect current password during password change for: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Current password is incorrect"))
                            .build());
        }

        // Validate new password strength
        try {
            PasswordStrengthValidator.validate(newPassword);
        } catch (IllegalArgumentException ex) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", ex.getMessage()))
                            .build());
        }

        // Hash and persist
        user.setPasswordHash(CustomerPasswordHashUtil.hash(newPassword));
        userRepository.persist(user);
    }

}
