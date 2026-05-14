package org.ecommerce.backend.api.rest;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Optional;

// Minimal REST API to support checkout UX (lookup, login, register/update)
@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource {

    @GET
    @Path("/lookup")
    public Response lookup(@QueryParam("email") String email) {
        if (email == null || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        CustomerEntity ce = CustomerEntity.findByEmail(email.trim());
        if (ce == null) {
            return Response.status(Response.Status.NO_CONTENT).build();
        }
        return Response.ok(toProfileDto(ce)).build();
    }

    public static class LoginRequest {
        public String email;
        public String password;
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest req) {
        if (req == null || req.email == null || req.password == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password required").build();
        }

        UserEntity user = UserEntity.findByEmail(req.email.trim());
        if (user == null || user.passwordHash == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        CustomerEntity ce = user.customer;
        if (ce == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }
        if (ce.status == CustomerStatusEn.PENDING) {
            return Response.status(Response.Status.FORBIDDEN).entity("Customer account is still pending").build();
        }
        if (ce.status == CustomerStatusEn.DISABLED) {
            return Response.status(Response.Status.FORBIDDEN).entity("Customer account is disabled").build();
        }
        if (ce.status == null) {
            ce.status = CustomerStatusEn.PENDING;
        }

        boolean ok;
        try {
            ok = verifyPassword(req.password, user.passwordHash);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        user.lastLogin = OffsetDateTime.now();
        user.persist();
        return Response.ok(toProfileDto(ce)).build();
    }

    public static class RegisterOrUpdateRequest {
        public String email;
        public String password; // optional if only updating profile
        public String firstName;
        public String lastName;
        public String phone;
        public String physicalAddressLine1;
        public String physicalAddressLine2;
        public String physicalSuburb;
        public String physicalCity;
        public String physicalProvince;
        public String physicalPostalCode;
        public String postalAddressLine1;
        public String postalAddressLine2;
        public String postalSuburb;
        public String postalCity;
        public String postalProvince;
        public String postalPostalCode;
    }

    @POST
    @Path("/registerOrUpdate")
    @Transactional
    public Response registerOrUpdate(RegisterOrUpdateRequest req) {
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }

        String email = req.email.trim();

        // ── Upsert UserEntity ─────────────────────────────────────────────
        UserEntity user = UserEntity.findByEmail(email);
        if (user == null) {
            user = new UserEntity();
            user.email = email;
        }

        boolean settingPassword = req.password != null && !req.password.isBlank();
        if (settingPassword) {
            user.passwordHash = hashPassword(req.password);
        } else if (user.passwordHash == null) {
            // Guest: set a dummy non-null hash to satisfy NOT NULL in DB
            user.passwordHash = "";
        }
        UserEntity.persist(user);

        // ── Upsert CustomerEntity ─────────────────────────────────────────
        CustomerEntity ce = user.customer;
        if (ce == null) {
            ce = new CustomerEntity();
            ce.user = user;
            ce.status = CustomerStatusEn.PENDING;
        }

        if (req.firstName != null) ce.firstName = req.firstName;
        if (req.lastName  != null) ce.lastName  = req.lastName;
        if (req.phone     != null) ce.phone      = req.phone;

        if (settingPassword) {
            ce.shopperType = CustomerTypeEn.RETAILER;
        } else if (ce.shopperType == null) {
            ce.shopperType = CustomerTypeEn.GUEST;
        }

        if (ce.status == null) {
            ce.status = CustomerStatusEn.PENDING;
        }
        CustomerEntity.persist(ce);

        // ── Upsert addresses ──────────────────────────────────────────────
        upsertAddress(ce, AddressTypeEn.PHYSICAL,
                req.physicalAddressLine1, req.physicalAddressLine2,
                req.physicalSuburb, req.physicalCity,
                req.physicalProvince, req.physicalPostalCode);

        upsertAddress(ce, AddressTypeEn.POSTAL,
                req.postalAddressLine1, req.postalAddressLine2,
                req.postalSuburb, req.postalCity,
                req.postalProvince, req.postalPostalCode);

        return Response.ok(toProfileDto(ce)).build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Creates or updates a single typed address on the customer.
     * Skips silently if all address fields are null.
     */
    private static void upsertAddress(CustomerEntity ce, AddressTypeEn type,
                                      String line1, String line2,
                                      String suburb, String city,
                                      String province, String postalCode) {
        if (line1 == null && city == null && province == null && postalCode == null) {
            return;
        }

        CustomerAddressEntity addr = ce.addresses.stream()
                .filter(a -> a.addressType == type)
                .findFirst()
                .orElseGet(() -> {
                    CustomerAddressEntity a = new CustomerAddressEntity();
                    a.customer = ce;
                    a.addressType = type;
                    ce.addresses.add(a);
                    return a;
                });

        if (line1     != null) addr.addressLine1 = line1;
        if (line2     != null) addr.addressLine2 = line2;
        if (suburb    != null) addr.suburb       = suburb;
        if (city      != null) addr.city         = city;
        if (province  != null) addr.province     = province;
        if (postalCode != null) addr.postalCode  = postalCode;
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

    private static boolean verifyPassword(String plain, String storedHash) {
        if (plain == null || storedHash == null) return false;
        return hashPassword(plain).equals(storedHash);
    }

    private static CustomerProfileDto toProfileDto(CustomerEntity ce) {
        CustomerProfileDto dto = new CustomerProfileDto();
        dto.setEmail(ce.user != null ? ce.user.email : null);
        dto.setFirstName(ce.firstName);
        dto.setLastName(ce.lastName);
        dto.setPhone(ce.phone);

        // Flatten addresses back to profile DTO fields
        Optional<CustomerAddressEntity> physical = ce.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.PHYSICAL).findFirst();
        physical.ifPresent(a -> {
            dto.setPhysicalAddressLine1(a.addressLine1);
            dto.setPhysicalAddressLine2(a.addressLine2);
            dto.setPhysicalSuburb(a.suburb);
            dto.setPhysicalCity(a.city);
            dto.setPhysicalProvince(a.province);
            dto.setPhysicalPostalCode(a.postalCode);
        });

        Optional<CustomerAddressEntity> postal = ce.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.POSTAL).findFirst();
        postal.ifPresent(a -> {
            dto.setPostalAddressLine1(a.addressLine1);
            dto.setPostalAddressLine2(a.addressLine2);
            dto.setPostalSuburb(a.suburb);
            dto.setPostalCity(a.city);
            dto.setPostalProvince(a.province);
            dto.setPostalPostalCode(a.postalCode);
        });

        if (ce.shopperType != null) dto.setShopperType(ce.shopperType.name());
        if (ce.status      != null) dto.setStatus(ce.status.name());
        dto.setHasPassword(ce.user != null
                && ce.user.passwordHash != null
                && !ce.user.passwordHash.isBlank());
        return dto;
    }
}
