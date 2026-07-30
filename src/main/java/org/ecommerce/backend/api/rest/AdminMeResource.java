package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.common.entity.StaffUserEntity;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminMeResource {

    private static final Logger LOG = Logger.getLogger(AdminMeResource.class);

    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/me")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER"})
    public Response me() {
        Set<String> groups = jwt.getGroups();
        String role = groups != null && !groups.isEmpty() ? groups.iterator().next() : "VIEWER";
        String email = jwt.getName();

        UUID id = null;
        // Defaults to true so an unresolvable user cannot be admitted to the portal:
        // the client treats "must reset" as the locked state, so this fails closed.
        boolean resetPassword = true;
        StaffUserEntity user = StaffUserEntity.findByEmail(email);
        if (user != null) {
            id = user.getId();
            resetPassword = user.isResetPassword();
        } else {
            LOG.warnv("Staff user not found for email: {0}", email);
        }

        return Response.ok(new AdminMeDto(id, role, List.of(role), email, email, resetPassword)).build();
    }

    /**
     * `resetPassword` carries the forced-password-change flag. It must be served here,
     * not only from the login response: the client deliberately does not persist it, so
     * on a page reload this endpoint is the only thing that can re-establish the state.
     * Without it the guard would rehydrate role and authority, see nothing forcing a
     * reset, and admit a flagged user — a bypass by reload.
     */
    public record AdminMeDto(UUID id, String role, List<String> authority, String userName, String email,
                             boolean resetPassword) {}
}
