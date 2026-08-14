package org.ecommerce.backend.api.rest;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.exception.InvalidPasswordResetCodeException;
import org.ecommerce.backend.exception.PasswordResetLockedException;
import org.ecommerce.backend.service.*;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.ecommerce.common.dto.CustomerLoginResponseDto;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.dto.PasswordChangeRequestDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.Optional;

// Minimal REST API to support checkout UX (lookup, login, register, profile)
@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource
{

    private static final Logger LOG = Logger.getLogger(CustomerResource.class);

    @Inject
    CustomerAuthService customerAuthService;

    @Inject
    CustomerPasswordResetService customerPasswordResetService;

    @Inject
    CustomerPortalService customerPortalService;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    JsonWebToken jwt;

    @ConfigProperty(name = "google.client.id", defaultValue = "5598643375-sooimltbseub586f1pucut2fut95dnbl.apps.googleusercontent.com")
    String googleClientId;

    public static class LoginRequest
    {
        public String email;
        public String password;
    }

    public static class PasswordResetRequest
    {
        public String email;
    }

    public static class PasswordResetVerifyRequest
    {
        public String email;
        public String code;
    }

    public static class PasswordResetCompleteRequest
    {
        public String email;
        public String code;
        public String newPassword;
        public String confirmPassword;
    }

    public static class GoogleLoginRequest
    {
        public String idToken;
    }

    @POST
    @Path("/password-reset/request")
    @Transactional
    public Response requestPasswordResetCode(
            PasswordResetRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }

        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);

        // Chained-check: IP limiter first; if denied, email counter is NOT incremented.
        // Silent denial — return the identical generic response to prevent enumeration.
        RateLimitDecision ipDecision = rateLimiterService.check("password-reset-request", clientIp, 5, 3600);
        if (!ipDecision.allowed()) {
            return Response.ok("If an account exists, a reset code has been sent.").build();
        }

        // Email limiter second (IP passed)
        String emailKey = req.email.toLowerCase().trim();
        RateLimitDecision emailDecision = rateLimiterService.check("password-reset-request-email", emailKey, 3, 3600);
        if (!emailDecision.allowed()) {
            return Response.ok("If an account exists, a reset code has been sent.").build();
        }

        customerPasswordResetService.initiatePasswordResetCode(req.email);
        return Response.ok("If an account exists, a reset code has been sent.").build();
    }

    @POST
    @Path("/password-reset/verify")
    @Transactional
    public Response verifyPasswordResetCode(
            PasswordResetVerifyRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        if (req == null || req.email == null || req.email.isBlank() || req.code == null || req.code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and code are required").build();
        }

        try {
            customerPasswordResetService.verifyPasswordResetCode(req.email, req.code, ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp));
            return Response.ok("Code verified.").build();
        } catch (PasswordResetLockedException ex) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Too many incorrect attempts. Locked for 15 minutes.")
                    .build();
        } catch (InvalidPasswordResetCodeException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid or expired reset code").build();
        }
    }

    @POST
    @Path("/password-reset/complete")
    @Transactional
    public Response completePasswordReset(
            PasswordResetCompleteRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        if (req == null || req.email == null || req.email.isBlank() || req.code == null || req.code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and code are required").build();
        }
        if (req.newPassword == null || req.newPassword.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("newPassword is required").build();
        }
        if (!req.newPassword.equals(req.confirmPassword)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match").build();
        }

        try {
            customerPasswordResetService.completePasswordResetWithCode(
                    req.email,
                    req.code,
                    req.newPassword,
                    ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp)
            );
            return Response.ok("Password reset complete.").build();
        } catch (PasswordResetLockedException ex) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Too many incorrect attempts. Locked for 15 minutes.")
                    .build();
        } catch (InvalidPasswordResetCodeException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid or expired reset code").build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
        }
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(
            LoginRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        // Body-shape validation stays ahead of the limiter — guarantees email key is non-null
        if (req == null || req.email == null || req.password == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password required").build();
        }

        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);

        // Chained-check: IP limiter first; if denied, email counter is NOT incremented
        RateLimitDecision ipDecision = rateLimiterService.check("customer-login", clientIp, 10, 900);
        if (!ipDecision.allowed()) {
            return Response.status(429).header("Retry-After", ipDecision.retryAfterSeconds()).build();
        }

        // Email limiter second (IP passed)
        String emailKey = req.email.toLowerCase().trim();
        RateLimitDecision emailDecision = rateLimiterService.check("customer-login-email", emailKey, 5, 900);
        if (!emailDecision.allowed()) {
            return Response.status(429).header("Retry-After", emailDecision.retryAfterSeconds()).build();
        }

        UserEntity user = UserEntity.findByEmail(req.email.trim());
        if (user == null || user.getPasswordHash() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        CustomerEntity ce = user.getCustomer();
        if (ce == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }
        if (ce.getStatus() == CustomerStatusEn.PENDING) {
            return Response.status(Response.Status.FORBIDDEN).entity("Customer account is still pending").build();
        }
        if (ce.getStatus() == CustomerStatusEn.DISABLED) {
            return Response.status(Response.Status.FORBIDDEN).entity("Customer account is disabled").build();
        }
        if (ce.getStatus() == null) {
            ce.setStatus(CustomerStatusEn.PENDING);
        }

        boolean ok;
        try {
            ok = PasswordHashUtil.verify(req.password, user.getPasswordHash());
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        user.setLastLogin(OffsetDateTime.now());
        user.persist();
        return Response.ok(toLoginResponseDto(ce)).build();
    }

    @POST
    @Path("/login/google")
    @Transactional
    public Response loginWithGoogle(
            GoogleLoginRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        if (req == null || req.idToken == null || req.idToken.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("idToken is required").build();
        }

        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);

        // Rate limit by IP — no per-email key because email is only known after token verification
        RateLimitDecision ipDecision = rateLimiterService.check("google-login", clientIp, 10, 900);
        if (!ipDecision.allowed()) {
            return Response.status(429).header("Retry-After", ipDecision.retryAfterSeconds()).build();
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

            Boolean emailVerified = payload.getEmailVerified();
            if (!Boolean.TRUE.equals(emailVerified)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("Google email is not verified").build();
            }

            String email = payload.getEmail();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            UserEntity user = UserEntity.findByEmail(email);
            if (user == null) {
                user = new UserEntity();
                user.setEmail(email);
                user.setPasswordHash(""); // Google users might not have a local password initially
                user.persist();
            }

            CustomerEntity ce = user.getCustomer();
            if (ce == null) {
                ce = new CustomerEntity();
                ce.setUser(user);
                ce.setFirstName(firstName);
                ce.setLastName(lastName);
                ce.setStatus(CustomerStatusEn.ACTIVE); // Auto-activate Google users
                ce.setShopperType(CustomerTypeEn.RETAILER);
                ce.persist();
            } else {
                if (ce.getStatus() == CustomerStatusEn.DISABLED) {
                    return Response.status(Response.Status.FORBIDDEN).entity("Customer account is disabled").build();
                }
                // Update names if missing
                if (ce.getFirstName() == null) ce.setFirstName(firstName);
                if (ce.getLastName() == null) ce.setLastName(lastName);
                if (ce.getStatus() == CustomerStatusEn.PENDING) ce.setStatus(CustomerStatusEn.ACTIVE);
                if (ce.getShopperType() == null || ce.getShopperType() == CustomerTypeEn.GUEST) {
                    ce.setShopperType(CustomerTypeEn.RETAILER);
                }
                ce.persist();
            }

            user.setLastLogin(OffsetDateTime.now());
            user.persist();

            return Response.ok(toLoginResponseDto(ce)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Google authentication failed: " + e.getMessage()).build();
        }
    }

    public static class RegisterRequest
    {
        public String email;
        public String password;
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

    public static class ProfileUpdateRequest
    {
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

    /**
     * Public registration endpoint — create-only.
     * Returns 409 if the email already belongs to a claimed account (non-empty passwordHash OR ACTIVE status).
     * Only an unclaimed PENDING/GUEST record may be claimed. Token issued only on genuine creation/claim.
     * If token signing fails, the @Transactional handler rolls back — no account persists without its token.
     * <p>
     * The 409 response is an account-existence oracle; rate limiting slows enumeration/mass signup
     * but is not a fix for lookup's PII disclosure.
     */
    @POST
    @Path("/register")
    @Transactional
    public Response register(
            RegisterRequest req,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    )
    {
        // Body-shape validation first (400) — before rate limiter to avoid consuming budget on malformed requests
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        if (req.password == null || req.password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("password is required").build();
        }

        // Rate limit check — runs after body-shape validation, before the 409 claimed-account guard
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        RateLimitDecision decision = rateLimiterService.check("register", clientIp, 10, 3600);
        if (!decision.allowed()) {
            return Response.status(429).header("Retry-After", decision.retryAfterSeconds()).build();
        }

        String email = req.email.trim();

        // ── 409 guard: reject if account is already claimed by any method ──
        UserEntity user = UserEntity.findByEmail(email);
        if (user != null && user.getCustomer() != null) {
            CustomerEntity ec = user.getCustomer();
            boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
            boolean alreadyClaimed = hasPassword || ec.getStatus() == CustomerStatusEn.ACTIVE;
            if (alreadyClaimed) {
                return Response.status(Response.Status.CONFLICT).entity("Account already exists").build();
            }
        }

        // ── Create or claim the account ───────────────────────────────────
        if (user == null) {
            user = new UserEntity();
            user.setEmail(email);
        }
        user.setPasswordHash(PasswordHashUtil.hash(req.password));
        UserEntity.persist(user);

        CustomerEntity ce = user.getCustomer();
        if (ce == null) {
            ce = new CustomerEntity();
            ce.setUser(user);
        }

        if (req.firstName != null) ce.setFirstName(req.firstName);
        if (req.lastName != null) ce.setLastName(req.lastName);
        if (req.phone != null) ce.setPhone(req.phone);

        ce.setShopperType(CustomerTypeEn.RETAILER);
        ce.setStatus(CustomerStatusEn.ACTIVE);
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

        // ── Generate token — if this throws, the transaction rolls back ───
        try {
            CustomerLoginResponseDto dto = toLoginResponseDto(ce);
            return Response.ok(dto).build();
        } catch (Exception e) {
            LOG.error("Token generation failed during registration for " + email, e);
            // Let the RuntimeException propagate so @Transactional rolls back
            throw new RuntimeException("Registration failed: token generation error", e);
        }
    }

    /**
     * Authenticated profile update — resolves user from jwt.getSubject().
     * Updates name/phone/addresses only. Ignores any password or email in the body.
     */
    @PATCH
    @Path("/profile")
    @RolesAllowed("customer")
    @Transactional
    public Response updateProfile(ProfileUpdateRequest req)
    {
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("request body is required").build();
        }

        String email = jwt.getSubject();
        UserEntity user = UserEntity.findByEmail(email);
        if (user == null || user.getCustomer() == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Customer not found").build();
        }

        CustomerEntity ce = user.getCustomer();

        if (req.firstName != null) ce.setFirstName(req.firstName);
        if (req.lastName != null) ce.setLastName(req.lastName);
        if (req.phone != null) ce.setPhone(req.phone);
        ce.persist();

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

    @PATCH
    @Path("/password")
    @RolesAllowed("customer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(PasswordChangeRequestDto request)
    {
        String email = jwt.getSubject();
        customerPortalService.changePassword(email, request.getCurrentPassword(), request.getNewPassword());
        return Response.ok().build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Creates or updates a single typed address on the customer.
     * Skips silently if all address fields are null.
     */
    private static void upsertAddress(CustomerEntity ce, AddressTypeEn type,
                                      String line1, String line2,
                                      String suburb, String city,
                                      String province, String postalCode)
    {
        if (line1 == null && city == null && province == null && postalCode == null) {
            return;
        }

        CustomerAddressEntity addr = ce.getAddresses().stream()
                .filter(a -> a.getAddressType() == type)
                .findFirst()
                .orElseGet(() -> {
                    CustomerAddressEntity a = new CustomerAddressEntity();
                    a.setCustomer(ce);
                    a.setAddressType(type);
                    ce.getAddresses().add(a);
                    return a;
                });

        if (line1 != null) {
            addr.setAddressLine1(line1);
        }
        if (line2 != null) {
            addr.setAddressLine2(line2);
        }
        if (suburb != null) {
            addr.setSuburb(suburb);
        }
        if (city != null) {
            addr.setCity(city);
        }
        if (province != null) {
            addr.setProvince(province);
        }
        if (postalCode != null) {
            addr.setPostalCode(postalCode);
        }
    }

    private CustomerLoginResponseDto toLoginResponseDto(CustomerEntity ce)
    {
        CustomerLoginResponseDto dto = new CustomerLoginResponseDto();
        dto.setToken(customerAuthService.generateToken(ce));
        dto.setEmail(ce.getUser() != null ? ce.getUser().getEmail() : null);
        dto.setFirstName(ce.getFirstName());
        dto.setLastName(ce.getLastName());
        dto.setShopperType(ce.getShopperType() != null ? ce.getShopperType().name() : null);
        dto.setStatus(ce.getStatus() != null ? ce.getStatus().name() : null);
        return dto;
    }

    private static CustomerProfileDto toProfileDto(CustomerEntity ce)
    {
        CustomerProfileDto dto = new CustomerProfileDto();
        dto.setEmail(ce.getUser() != null ? ce.getUser().getEmail() : null);
        dto.setFirstName(ce.getFirstName());
        dto.setLastName(ce.getLastName());
        dto.setPhone(ce.getPhone());

        // Flatten addresses back to profile DTO fields
        Optional<CustomerAddressEntity> physical = ce.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressTypeEn.PHYSICAL).findFirst();
        physical.ifPresent(a -> {
            dto.setPhysicalAddressLine1(a.getAddressLine1());
            dto.setPhysicalAddressLine2(a.getAddressLine2());
            dto.setPhysicalSuburb(a.getSuburb());
            dto.setPhysicalCity(a.getCity());
            dto.setPhysicalProvince(a.getProvince());
            dto.setPhysicalPostalCode(a.getPostalCode());
        });

        Optional<CustomerAddressEntity> postal = ce.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressTypeEn.POSTAL).findFirst();
        postal.ifPresent(a -> {
            dto.setPostalAddressLine1(a.getAddressLine1());
            dto.setPostalAddressLine2(a.getAddressLine2());
            dto.setPostalSuburb(a.getSuburb());
            dto.setPostalCity(a.getCity());
            dto.setPostalProvince(a.getProvince());
            dto.setPostalPostalCode(a.getPostalCode());
        });

        if (ce.getShopperType() != null) dto.setShopperType(ce.getShopperType().name());
        if (ce.getStatus() != null) dto.setStatus(ce.getStatus().name());
        dto.setHasPassword(ce.getUser() != null
                && ce.getUser().getPasswordHash() != null
                && !ce.getUser().getPasswordHash().isBlank());
        return dto;
    }

}
