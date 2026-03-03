package org.ecommerce.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.persistance.dto.LoginRequestDto;
import org.ecommerce.persistance.dto.TokenResponseDto;
import org.ecommerce.persistance.entity.StaffUser;
import org.ecommerce.service.AdminAuthService;

@Path("/api/admin/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminAuthResource {

    @Inject
    AdminAuthService authService;

    @POST
    @Path("/login")
    public Response login(@Valid LoginRequestDto loginDto) {
        String token = authService.authenticate(loginDto);

        if (token != null) {
            // Retrieve user again to send extra info to frontend if needed
            StaffUser user = StaffUser.find("username", loginDto.username()).firstResult();
            return Response.ok(new TokenResponseDto(token, user.username, user.role.name())).build();
        }

        return Response.status(Response.Status.UNAUTHORIZED)
                .entity("Invalid username or password")
                .build();
    }
}
