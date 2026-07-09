package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.WishlistService;
import org.ecommerce.common.dto.WishlistResponseDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@Path("/api/storefront/wishlist")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StorefrontWishlistResource {

    private static final Logger LOG = Logger.getLogger(StorefrontWishlistResource.class);

    @Inject
    WishlistService wishlistService;

    @Inject
    JsonWebToken jwt;

    @GET
    @RolesAllowed("customer")
    public Response getWishlist() {
        UUID customerId = resolveCustomerId();
        if (customerId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Customer not found"))
                    .build();
        }

        WishlistResponseDto dto = new WishlistResponseDto();
        dto.variantIds = wishlistService.getWishlistVariantIds(customerId);
        return Response.ok(dto).build();
    }

    @POST
    @Path("/{variantId}")
    @RolesAllowed("customer")
    public Response addToWishlist(@PathParam("variantId") String variantId) {
        UUID parsedVariantId;
        try {
            parsedVariantId = UUID.fromString(variantId);
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid variant ID format: " + variantId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid variant ID"))
                    .build();
        }

        UUID customerId = resolveCustomerId();
        if (customerId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Customer not found"))
                    .build();
        }

        WishlistService.AddResult result = wishlistService.addToWishlist(customerId, parsedVariantId);

        return switch (result) {
            case CREATED -> Response.status(201).build();
            case ALREADY_EXISTS -> Response.ok().build();
            case VARIANT_NOT_FOUND -> Response.status(404)
                    .entity(Map.of("error", "Variant not found"))
                    .build();
        };
    }

    @DELETE
    @Path("/{variantId}")
    @RolesAllowed("customer")
    public Response removeFromWishlist(@PathParam("variantId") String variantId) {
        UUID parsedVariantId;
        try {
            parsedVariantId = UUID.fromString(variantId);
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid variant ID format: " + variantId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid variant ID"))
                    .build();
        }

        UUID customerId = resolveCustomerId();
        if (customerId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Customer not found"))
                    .build();
        }

        wishlistService.removeFromWishlist(customerId, parsedVariantId);
        return Response.noContent().build();
    }

    private UUID resolveCustomerId() {
        String email = jwt.getSubject();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            LOG.warn("Customer not found for JWT subject: " + email);
            return null;
        }
        return customer.id;
    }
}
