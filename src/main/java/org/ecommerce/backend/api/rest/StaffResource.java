package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.dto.LoginRequestDto;
import org.ecommerce.common.dto.TokenResponseDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.backend.service.AdminAuthService;
import org.ecommerce.backend.service.StaffService;

@Path("/api/admin/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StaffResource
{
    public record ResetPasswordRequest(String email, String password, String confirmPassword) {}

    @Inject
    AdminAuthService authService;

    @Inject
    StaffService staffService;

    @POST
    @Path("/login")
    public Response login(@Valid LoginRequestDto loginDto)
    {
        String token = authService.authenticate(loginDto);

        if (token != null) {
            // Retrieve user again to send extra info to frontend if needed
            StaffUserEntity user = StaffUserEntity.findByEmail(loginDto.email());
            return Response.ok(new TokenResponseDto(token, user.email, user.role.name(), user.resetPassword)).build();
        }

        StaffUserEntity user = StaffUserEntity.findByEmail(loginDto.email());
        if (user != null && !user.isActive) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Access denied")
                    .build();
        }

        return Response.status(Response.Status.UNAUTHORIZED)
                .entity("Invalid email or password")
                .build();
    }

    @POST
    @Path("/reset-password")
    public Response resetPassword(@Valid ResetPasswordRequest req)
    {
        if (req == null || req.email() == null || req.email().isBlank() || req.password() == null || req.password().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("email and password are required").build();
        }

        if (req.confirmPassword() == null || !req.password().equals(req.confirmPassword())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match").build();
        }

        staffService.resetStaffPassword(req.email(), req.password());
        return Response.ok().build();
    }
}
