package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.CustomerPasswordResetService;
import org.ecommerce.backend.service.CustomerPortalService;
import org.ecommerce.common.dto.CustomerLoginResponseDto;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.dto.PasswordChangeRequestDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.ecommerce.backend.utils.PasswordHashUtil;

// Minimal REST API to support checkout UX (lookup, login, register/update)
@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource {

    @Inject
    CustomerAuthService customerAuthService;

    @Inject
    CustomerPasswordResetService customerPasswordResetService;

    @Inject
    CustomerPortalService customerPortalService;

    @Inject
    JsonWebToken jwt;

    @ConfigProperty(name = "google.client.id", defaultValue = "5598643375-sooimltbseub586f1pucut2fut95dnbl.apps.googleusercontent.com")
    String googleClientId;

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

    public static class PasswordResetRequest {
        public String email;
    }

    public static class PasswordResetVerifyRequest {
        public String email;
        public String code;
    }

    public static class PasswordResetCompleteRequest {
        public String email;
        public String code;
        public String newPassword;
        public String confirmPassword;
    }

    public static class GoogleLoginRequest {
        public String idToken;
    }

    @POST
    @Path("/password-reset/request")
    @Transactional
    public Response requestPasswordResetCode(PasswordResetRequest req) {
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }

        customerPasswordResetService.initiatePasswordResetCode(req.email);
        return Response.ok("If an account exists, a reset code has been sent.").build();
    }

    @POST
    @Path("/password-reset/verify")
    @Transactional
    public Response verifyPasswordResetCode(
            PasswordResetVerifyRequest req,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        if (req == null || req.email == null || req.email.isBlank() || req.code == null || req.code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and code are required").build();
        }

        try {
            customerPasswordResetService.verifyPasswordResetCode(req.email, req.code, resolveClientIp(xForwardedFor, xRealIp));
            return Response.ok("Code verified.").build();
        } catch (CustomerPasswordResetService.PasswordResetLockedException ex) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Too many incorrect attempts. Locked for 15 minutes.")
                    .build();
        } catch (CustomerPasswordResetService.InvalidPasswordResetCodeException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid or expired reset code").build();
        }
    }

    @POST
    @Path("/password-reset/complete")
    @Transactional
    public Response completePasswordReset(
            PasswordResetCompleteRequest req,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        if (req == null || req.email == null || req.email.isBlank() || req.code == null || req.code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and code are required").build();
        }
        if (req.newPassword == null || req.newPassword.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("newPassword is required").build();
        }
        if (req.confirmPassword == null || !req.newPassword.equals(req.confirmPassword)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match").build();
        }

        try {
            customerPasswordResetService.completePasswordResetWithCode(
                    req.email,
                    req.code,
                    req.newPassword,
                    resolveClientIp(xForwardedFor, xRealIp)
            );
            return Response.ok("Password reset complete.").build();
        } catch (CustomerPasswordResetService.PasswordResetLockedException ex) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Too many incorrect attempts. Locked for 15 minutes.")
                    .build();
        } catch (CustomerPasswordResetService.InvalidPasswordResetCodeException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid or expired reset code").build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
        }
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
            ok = PasswordHashUtil.verify(req.password, user.passwordHash);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        user.lastLogin = OffsetDateTime.now();
        user.persist();
        return Response.ok(toLoginResponseDto(ce)).build();
    }

    @POST
    @Path("/login/google")
    @Transactional
    public Response loginWithGoogle(GoogleLoginRequest req) {
        if (req == null || req.idToken == null || req.idToken.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("idToken is required").build();
        }

        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(java.util.Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(req.idToken);
            if (idToken == null) {
                return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid Google ID Token").build();
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            UserEntity user = UserEntity.findByEmail(email);
            if (user == null) {
                user = new UserEntity();
                user.email = email;
                user.passwordHash = ""; // Google users might not have a local password initially
                user.persist();
            }

            CustomerEntity ce = user.customer;
            if (ce == null) {
                ce = new CustomerEntity();
                ce.user = user;
                ce.firstName = firstName;
                ce.lastName = lastName;
                ce.status = CustomerStatusEn.ACTIVE; // Auto-activate Google users
                ce.shopperType = CustomerTypeEn.RETAILER;
                ce.persist();
            } else {
                if (ce.status == CustomerStatusEn.DISABLED) {
                    return Response.status(Response.Status.FORBIDDEN).entity("Customer account is disabled").build();
                }
                // Update names if missing
                if (ce.firstName == null) ce.firstName = firstName;
                if (ce.lastName == null) ce.lastName = lastName;
                if (ce.status == CustomerStatusEn.PENDING) ce.status = CustomerStatusEn.ACTIVE;
                ce.persist();
            }

            user.lastLogin = OffsetDateTime.now();
            user.persist();

            return Response.ok(toLoginResponseDto(ce)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Google authentication failed: " + e.getMessage()).build();
        }
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
            user.passwordHash = PasswordHashUtil.hash(req.password);
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
            // Password-based registration: activate immediately so the customer can log in
            if (ce.status == null || ce.status == CustomerStatusEn.PENDING) {
                ce.status = CustomerStatusEn.ACTIVE;
            }
        } else {
            if (ce.shopperType == null) ce.shopperType = CustomerTypeEn.GUEST;
            if (ce.status == null) ce.status = CustomerStatusEn.PENDING;
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

        return Response.ok(toLoginResponseDto(ce)).build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    @PATCH
    @Path("/password")
    @RolesAllowed("customer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(PasswordChangeRequestDto request) {
        String email = jwt.getSubject();
        customerPortalService.changePassword(email, request.currentPassword, request.newPassword);
        return Response.ok().build();
    }

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

    private CustomerLoginResponseDto toLoginResponseDto(CustomerEntity ce) {
        CustomerLoginResponseDto dto = new CustomerLoginResponseDto();
        dto.token = customerAuthService.generateToken(ce);
        dto.email = ce.user != null ? ce.user.email : null;
        dto.firstName = ce.firstName;
        dto.lastName = ce.lastName;
        dto.shopperType = ce.shopperType != null ? ce.shopperType.name() : null;
        dto.status = ce.status != null ? ce.status.name() : null;
        return dto;
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

    private static String resolveClientIp(String xForwardedFor, String xRealIp) {
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.indexOf(',');
            if (commaIndex > -1) {
                return xForwardedFor.substring(0, commaIndex).trim();
            }
            return xForwardedFor.trim();
        }
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return "unknown";
    }
}
