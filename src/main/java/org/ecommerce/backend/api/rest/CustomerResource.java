package org.ecommerce.backend.api.rest;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.backend.util.JsonConverter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

// Minimal REST API to support checkout UX (lookup, login, register/update)
@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource
{
    @GET
    @Path("/lookup")
    public Response lookup(@QueryParam("email") String email)
    {
        if (email == null || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        CustomerEntity ce = CustomerEntity.findByEmail(email.trim());
        if (ce == null) {
            return Response.status(Response.Status.NO_CONTENT).build();
        }
        return Response.ok(toProfileDto(ce)).build();
    }

    public static class LoginRequest
    {
        public String email;
        public String password;
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest req)
    {
        if (req == null || req.email == null || req.password == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password required").build();
        }
        CustomerEntity ce = CustomerEntity.findByEmail(req.email.trim());
        if (ce == null || ce.passwordHash == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }
        if (ce.status == CustomerStatusEn.DISABLED) {
            return Response.status(Response.Status.FORBIDDEN).entity("Customer account is disabled").build();
        }
        if (ce.status == null) {
            ce.status = CustomerStatusEn.REGISTERING;
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

    public static class RegisterOrUpdateRequest
    {
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
        public String additionalInfo;
    }

    @POST
    @Path("/registerOrUpdate")
    @Transactional
    public Response registerOrUpdate(RegisterOrUpdateRequest req)
    {
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        String email = req.email.trim();
        CustomerEntity ce = CustomerEntity.findByEmail(email);
        if (ce == null) {
            ce = new CustomerEntity();
            ce.email = email;
            ce.status = CustomerStatusEn.REGISTERING;
        }

        if (req.firstName != null) ce.firstName = req.firstName;
        if (req.lastName != null) ce.lastName = req.lastName;
        if (req.phone != null) ce.phone = req.phone;
        if (req.physicalAddressLine1 != null) ce.physicalAddressLine1 = req.physicalAddressLine1;
        if (req.physicalAddressLine2 != null) ce.physicalAddressLine2 = req.physicalAddressLine2;
        if (req.physicalSuburb != null) ce.physicalSuburb = req.physicalSuburb;
        if (req.physicalCity != null) ce.physicalCity = req.physicalCity;
        if (req.physicalProvince != null) ce.physicalProvince = req.physicalProvince;
        if (req.physicalPostalCode != null) ce.physicalPostalCode = req.physicalPostalCode;

        if (req.postalAddressLine1 != null) ce.postalAddressLine1 = req.postalAddressLine1;
        if (req.postalAddressLine2 != null) ce.postalAddressLine2 = req.postalAddressLine2;
        if (req.postalSuburb != null) ce.postalSuburb = req.postalSuburb;
        if (req.postalCity != null) ce.postalCity = req.postalCity;
        if (req.postalProvince != null) ce.postalProvince = req.postalProvince;
        if (req.postalPostalCode != null) ce.postalPostalCode = req.postalPostalCode;
        if (req.additionalInfo != null) ce.additionalInfo = JsonConverter.toJsonString(req.additionalInfo);

        if (req.password != null && !req.password.isBlank()) {
            ce.passwordHash = hashPassword(req.password);
            ce.shopperType = CustomerTypeEn.RETAILER;
            ce.passwordUpdatedAt = LocalDateTime.now();
        } else if (ce.shopperType == null) {
            ce.shopperType = CustomerTypeEn.GUEST;
        }

        if (ce.status == null) {
            ce.status = CustomerStatusEn.REGISTERING;
        }

        CustomerEntity.persist(ce);
        return Response.ok(toProfileDto(ce)).build();
    }

    private static String hashPassword(String password)
    {
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

    private static boolean verifyPassword(String plain, String storedHash)
    {
        if (plain == null || storedHash == null) return false;
        return hashPassword(plain).equals(storedHash);
    }

    private static CustomerProfileDto toProfileDto(CustomerEntity ce)
    {
        CustomerProfileDto dto = new CustomerProfileDto();
        dto.setEmail(ce.email);
        dto.setFirstName(ce.firstName);
        dto.setLastName(ce.lastName);
        dto.setPhone(ce.phone);
        dto.setPhysicalAddressLine1(ce.physicalAddressLine1);
        dto.setPhysicalAddressLine2(ce.physicalAddressLine2);
        dto.setPhysicalSuburb(ce.physicalSuburb);
        dto.setPhysicalCity(ce.physicalCity);
        dto.setPhysicalProvince(ce.physicalProvince);
        dto.setPhysicalPostalCode(ce.physicalPostalCode);
        dto.setPostalAddressLine1(ce.postalAddressLine1);
        dto.setPostalAddressLine2(ce.postalAddressLine2);
        dto.setPostalSuburb(ce.postalSuburb);
        dto.setPostalCity(ce.postalCity);
        dto.setPostalProvince(ce.postalProvince);
        dto.setPostalPostalCode(ce.postalPostalCode);
        if (ce.shopperType != null) {
            dto.setShopperType(ce.shopperType.name());
        }
        if (ce.status != null) {
            dto.setStatus(ce.status.name());
        }
        dto.setAdditionalInfo(ce.additionalInfo);
        dto.setHasPassword(ce.passwordHash != null && !ce.passwordHash.isBlank());
        return dto;
    }
}
