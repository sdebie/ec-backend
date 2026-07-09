package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Service for the Customer Portal — handles profile retrieval and password changes.
 */
@ApplicationScoped
public class CustomerPortalService {

    private static final Logger LOG = Logger.getLogger(CustomerPortalService.class);

    /**
     * Resolves the customer by email and maps to a StorefrontCustomerPortalDto.
     *
     * @param email the customer's email (from JWT subject)
     * @return the fully mapped portal profile DTO
     * @throws WebApplicationException 404 if customer not found
     */
    public StorefrontCustomerPortalDto getPortalProfile(String email) {
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            LOG.warnf("Customer portal profile requested for unknown email: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Customer not found"))
                            .build());
        }

        StorefrontCustomerPortalDto dto = new StorefrontCustomerPortalDto();
        dto.email = customer.user != null ? customer.user.email : null;
        dto.firstName = customer.firstName;
        dto.lastName = customer.lastName;
        dto.phone = customer.phone;

        // Map shopper type
        dto.shopperType = customer.shopperType != null ? customer.shopperType.name() : "GUEST";

        // Map addresses
        dto.physicalAddress = mapAddress(customer, AddressTypeEn.PHYSICAL);
        dto.postalAddress = mapAddress(customer, AddressTypeEn.POSTAL);

        // Determine hasPassword
        dto.hasPassword = customer.user != null
                && customer.user.passwordHash != null
                && !customer.user.passwordHash.isEmpty();

        return dto;
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
    public void changePassword(String email, String currentPassword, String newPassword) {
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            LOG.warnf("Password change attempted for unknown email: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Customer not found"))
                            .build());
        }

        UserEntity user = customer.user;

        // Check if the user has a local password set
        if (user.passwordHash == null || user.passwordHash.isEmpty()) {
            LOG.warnf("Password change attempted for account with no local password: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "No local password is set for this account"))
                            .build());
        }

        // Verify current password
        if (!PasswordHashUtil.verify(currentPassword, user.passwordHash)) {
            LOG.warnf("Incorrect current password during password change for: %s", email);
            throw new WebApplicationException(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Current password is incorrect"))
                            .build());
        }

        // Validate new password length
        if (newPassword == null || newPassword.length() < 8) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "Password must be at least 8 characters"))
                            .build());
        }

        // Hash and persist
        user.passwordHash = PasswordHashUtil.hash(newPassword);
        user.persist();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private StorefrontCustomerPortalDto.AddressDto mapAddress(CustomerEntity customer, AddressTypeEn type) {
        return customer.addresses.stream()
                .filter(a -> a.addressType == type)
                .findFirst()
                .map(this::toAddressDto)
                .orElse(null);
    }

    private StorefrontCustomerPortalDto.AddressDto toAddressDto(CustomerAddressEntity entity) {
        StorefrontCustomerPortalDto.AddressDto dto = new StorefrontCustomerPortalDto.AddressDto();
        dto.line1 = entity.addressLine1;
        dto.line2 = entity.addressLine2;
        dto.suburb = entity.suburb;
        dto.city = entity.city;
        dto.province = entity.province;
        dto.postalCode = entity.postalCode;
        return dto;
    }
}
