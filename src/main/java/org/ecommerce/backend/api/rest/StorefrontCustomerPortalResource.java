package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.CustomerPortalService;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;

/**
 * REST endpoint for the authenticated Customer Portal profile.
 * Returns the full customer profile including addresses and password status.
 */
@Path("/api/storefront/customer-portal")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StorefrontCustomerPortalResource {

    @Inject
    CustomerPortalService customerPortalService;

    @Inject
    JsonWebToken jwt;

    @GET
    @RolesAllowed("customer")
    public Response getCustomerPortal() {
        String email = jwt.getSubject();
        StorefrontCustomerPortalDto profile = customerPortalService.getPortalProfile(email);
        return Response.ok(profile).build();
    }
}
