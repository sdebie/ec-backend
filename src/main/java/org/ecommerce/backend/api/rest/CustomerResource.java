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
import org.ecommerce.backend.mapper.CustomerAddressMapper;
import org.ecommerce.backend.service.*;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.backend.utils.PasswordStrengthValidator;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.dto.CustomerLoginResponseDto;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.dto.PasswordChangeRequestDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.repository.UserRepository;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;

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
    CustomerAddressService customerAddressService;

    @Inject
    CustomerAddressMapper customerAddressMapper;

    @Inject
    UserRepository userRepository;

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
    public Response requestPasswordResetCode(PasswordResetRequest req)
    {
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
    public Response login(LoginRequest req)
    {
        if (req == null || req.email == null || req.password == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password required").build();
        }

        UserEntity user = userRepository.findByEmail(req.email.trim());
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
            ok = CustomerPasswordHashUtil.verify(req.password, user.getPasswordHash());
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        // A successful login is the one moment we hold the plaintext for an account that
        // may still carry a pre-migration hash — upgrade it in place so customers migrate
        // without a forced reset.
        if (CustomerPasswordHashUtil.isLegacyHash(user.getPasswordHash())) {
            user.setPasswordHash(CustomerPasswordHashUtil.hash(req.password));
        }

        user.setLastLogin(OffsetDateTime.now());
        user.persist();
        return Response.ok(toLoginResponseDto(ce)).build();
    }

    @POST
    @Path("/login/google")
    @Transactional
    public Response loginWithGoogle(GoogleLoginRequest req)
    {
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

            Boolean emailVerified = payload.getEmailVerified();
            if (!Boolean.TRUE.equals(emailVerified)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("Google email is not verified").build();
            }

            String email = payload.getEmail();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            UserEntity user = userRepository.findByEmail(email);
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
        public AddressDto physicalAddress;
        public AddressDto postalAddress;
    }

    public static class ProfileUpdateRequest
    {
        public String firstName;
        public String lastName;
        public String phone;
        public AddressDto physicalAddress;
        public AddressDto postalAddress;
    }

    /**
     * Public registration endpoint — create-only.
     * Returns 409 if the email already belongs to a claimed account (non-empty passwordHash OR ACTIVE status).
     * Only an unclaimed PENDING/GUEST record may be claimed. Token issued only on genuine creation/claim.
     * If token signing fails, the @Transactional handler rolls back — no account persists without its token.
     * <p>
     * The 409 response is an account-existence oracle — lookup's PII disclosure.
     */
    @POST
    @Path("/register")
    @Transactional
    public Response register(RegisterRequest req)
    {
        // Body-shape validation first (400)
        if (req == null || req.email == null || req.email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email is required").build();
        }
        if (req.password == null || req.password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("password is required").build();
        }
        try {
            PasswordStrengthValidator.validate(req.password);
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
        }

        String email = req.email.trim();

        // ── 409 guards: reject if any method already claims account ──
        UserEntity user = userRepository.findByEmail(email);
        if (user != null && user.getCustomer() != null) {
            CustomerEntity ec = user.getCustomer();
            boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
            boolean alreadyClaimed = hasPassword || ec.getStatus() == CustomerStatusEn.ACTIVE;
            if (alreadyClaimed) {
                return Response.status(Response.Status.CONFLICT).entity("Account already exists").build();
            }
        }


        if (user == null) {
            user = new UserEntity();
            user.setEmail(email);
        }
        user.setPasswordHash(CustomerPasswordHashUtil.hash(req.password));
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

        customerAddressService.upsertAddress(ce, AddressTypeEn.PHYSICAL, req.physicalAddress);
        customerAddressService.upsertAddress(ce, AddressTypeEn.POSTAL, req.postalAddress);

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
        UserEntity user = userRepository.findByEmail(email);
        if (user == null || user.getCustomer() == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Customer not found").build();
        }

        CustomerEntity ce = user.getCustomer();

        if (req.firstName != null) ce.setFirstName(req.firstName);
        if (req.lastName != null) ce.setLastName(req.lastName);
        if (req.phone != null) ce.setPhone(req.phone);
        ce.persist();

        customerAddressService.upsertAddress(ce, AddressTypeEn.PHYSICAL, req.physicalAddress);
        customerAddressService.upsertAddress(ce, AddressTypeEn.POSTAL, req.postalAddress);

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

    private CustomerProfileDto toProfileDto(CustomerEntity ce)
    {
        CustomerProfileDto dto = new CustomerProfileDto();
        dto.setEmail(ce.getUser() != null ? ce.getUser().getEmail() : null);
        dto.setFirstName(ce.getFirstName());
        dto.setLastName(ce.getLastName());
        dto.setPhone(ce.getPhone());

        dto.setPhysicalAddress(customerAddressMapper.toAddressDto(ce.getPhysicalAddress()));
        dto.setPostalAddress(customerAddressMapper.toAddressDto(ce.getPostalAddress()));

        if (ce.getShopperType() != null) dto.setShopperType(ce.getShopperType().name());
        if (ce.getStatus() != null) dto.setStatus(ce.getStatus().name());
        dto.setHasPassword(ce.getUser() != null
                && ce.getUser().getPasswordHash() != null
                && !ce.getUser().getPasswordHash().isBlank());
        return dto;
    }

}
