package org.ecommerce.api.rest;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.persistance.dto.CustomerProfileDto;
import org.ecommerce.persistance.entity.CustomerEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

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
        CustomerEntity ce = CustomerEntity.findByEmail(req.email.trim());
        if (ce == null || ce.passwordHash == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }
        boolean ok;
        try {
            ok = verifyPassword(req.password, ce.passwordHash);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }
        ce.passwordUpdatedAt = LocalDateTime.now();
        ce.persist();
        return Response.ok(toProfileDto(ce)).build();
    }

    public static class RegisterOrUpdateRequest {
        public String email;
        public String password; // optional if only updating profile
        public String firstName;
        public String lastName;
        public String phone;
        public String addressLine1;
        public String addressLine2;
        public String city;
        public String province;
        public String postalCode;
    }

    @POST
    @Path("/registerOrUpdate")
    @Transactional
    public Response registerOrUpdate(RegisterOrUpdateRequest req) {
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        String email = req.email.trim();
        CustomerEntity ce = CustomerEntity.findByEmail(email);
        if (ce == null) {
            ce = new CustomerEntity();
            ce.email = email;
        }

        if (req.firstName != null) ce.firstName = req.firstName;
        if (req.lastName != null) ce.lastName = req.lastName;
        if (req.phone != null) ce.phone = req.phone;
        if (req.addressLine1 != null) ce.addressLine1 = req.addressLine1;
        if (req.addressLine2 != null) ce.addressLine2 = req.addressLine2;
        if (req.city != null) ce.city = req.city;
        if (req.province != null) ce.province = req.province;
        if (req.postalCode != null) ce.postalCode = req.postalCode;

        if (req.password != null && !req.password.isBlank()) {
            ce.passwordHash = hashPassword(req.password);
            ce.shopperType = CustomerTypeEn.REGISTERED;
            ce.passwordUpdatedAt = LocalDateTime.now();
        } else if (ce.shopperType == null) {
            ce.shopperType = CustomerTypeEn.GUEST;
        }

        CustomerEntity.persist(ce);
        return Response.ok(toProfileDto(ce)).build();
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
        dto.setEmail(ce.email);
        dto.setFirstName(ce.firstName);
        dto.setLastName(ce.lastName);
        dto.setPhone(ce.phone);
        dto.setAddressLine1(ce.addressLine1);
        dto.setAddressLine2(ce.addressLine2);
        dto.setCity(ce.city);
        dto.setProvince(ce.province);
        dto.setPostalCode(ce.postalCode);
        if (ce.shopperType != null) {
            dto.setShopperType(ce.shopperType.name());
        }
        dto.setHasPassword(ce.passwordHash != null && !ce.passwordHash.isBlank());
        return dto;
    }
}
