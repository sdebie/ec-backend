package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.security.ForcedPasswordResetIdentityAugmentor;
import org.ecommerce.backend.service.AdminAuthService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.service.StaffService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.common.dto.LoginRequestDto;
import org.ecommerce.common.dto.TokenResponseDto;
import org.ecommerce.common.entity.StaffUserEntity;

@Path("/api/admin/auth")
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StaffResource
{
    public record ResetPasswordRequest(String email, String password, String confirmPassword)
    {
    }

    @Inject
    AdminAuthService authService;

    @Inject
    StaffService staffService;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @POST
    @Path("/login")
    public Response login(
            @Valid LoginRequestDto loginDto,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp)
    {
        log.debug("Login Request received");

        // Body-shape validation (400) stays ahead of the limiter — @Valid ensures email/password non-blank.
        // If we reach here, loginDto.email() is guaranteed non-null.

        // Chained-check semantics: IP limiter first; if denied, return 429 immediately
        // (email counter NOT incremented).
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        RateLimitDecision ipDecision = rateLimiterService.check("admin-login", clientIp, 10, 900);
        if (!ipDecision.allowed()) {
            return Response.status(429).header("Retry-After", ipDecision.retryAfterSeconds()).build();
        }

        // IP passed — now consult the per-email limiter.
        String emailKey = loginDto.email().toLowerCase().trim();
        RateLimitDecision emailDecision = rateLimiterService.check("admin-login-email", emailKey, 5, 900);
        if (!emailDecision.allowed()) {
            return Response.status(429).header("Retry-After", emailDecision.retryAfterSeconds()).build();
        }

        // Rate limits passed — evaluate credentials.
        String token = authService.authenticate(loginDto);

        if (token != null) {
            // Retrieve user again to send extra info to frontend if needed
            StaffUserEntity user = StaffUserEntity.findByEmail(loginDto.email());
            return Response.ok(new TokenResponseDto(token, user.getEmail(), user.getRole().name(), user.isResetPassword())).build();
        }

        StaffUserEntity user = StaffUserEntity.findByEmail(loginDto.email());
        if (user != null && !user.isActive()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Access denied")
                    .build();
        }

        return Response.status(Response.Status.UNAUTHORIZED)
                .entity("Invalid email or password")
                .build();
    }

    /**
     * Two distinct capabilities share this endpoint:
     * <ul>
     *   <li><b>Self-service</b> — any authenticated staff member may reset <i>their own</i>
     *       password, whatever their role — including one flagged for a forced reset, which
     *       is what makes that flow completable: {@link ForcedPasswordResetIdentityAugmentor}
     *       strips a flagged account down to {@code PASSWORD_RESET_REQUIRED}, and this
     *       endpoint's {@code @RolesAllowed} names that role specifically so self-service
     *       still reaches it.</li>
     *   <li><b>Administrative</b> — a SUPER_ADMIN may reset <i>another</i> account's password —
     *       but not while flagged themselves: the check below reads the augmented
     *       {@link SecurityIdentity}, not the raw JWT, so a flagged SUPER_ADMIN's stripped
     *       identity fails it exactly like any other flagged account targeting someone else.</li>
     * </ul>
     * The role check therefore cannot live in {@code @RolesAllowed}, which cannot see the
     * request body. It is enforced below by comparing the target against {@code jwt.getName()}.
     */
    @POST
    @Path("/reset-password")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER",
            ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE})
    public Response resetPassword(@Valid ResetPasswordRequest req)
    {
        if (req == null || req.email() == null || req.email().isBlank() || req.password() == null || req.password().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password are required").build();
        }

        if (req.confirmPassword() == null || !req.password().equals(req.confirmPassword())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match").build();
        }

        String callerEmail = jwt.getName();
        boolean targetsSelf = callerEmail != null && callerEmail.equalsIgnoreCase(req.email().trim());

        if (!targetsSelf && !securityIdentity.hasRole("SUPER_ADMIN")) {
            log.warn("Staff user attempted to reset another account's password without SUPER_ADMIN");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("You may only reset your own password")
                    .build();
        }

        staffService.resetStaffPassword(req.email(), req.password());
        return Response.ok().build();
    }
}
