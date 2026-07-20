package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.enums.CustomerTypeEn;

import java.util.Map;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrderService orderService;

    @Inject
    JsonWebToken jwt;

    @POST
    @Transactional
    public Response createOrder(OrderCreationRequestDto request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Request body is required").build();
        }

        CustomerTypeEn customerTier = resolveCustomerTier();

        try {
            OrderCheckoutResponseDto response = orderService.createOrderFromCart(request, customerTier);
            return Response.status(201).entity(response).build();
        } catch (UnavailableVariantsException e) {
            return Response.status(422)
                    .entity(Map.of("unavailableVariantIds", e.getUnavailableVariantIds()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the customer tier from the signature-verified JWT.
     * Quarkus rejects requests carrying an invalid or forged Bearer token before
     * the endpoint runs, so the claim can be trusted; no token means guest checkout.
     */
    private CustomerTypeEn resolveCustomerTier() {
        if (jwt == null || jwt.getRawToken() == null) {
            return CustomerTypeEn.GUEST;
        }

        Object claim = jwt.getClaim("shopperType");
        if (claim == null) {
            return CustomerTypeEn.GUEST;
        }

        String shopperType = claim instanceof JsonString js ? js.getString() : claim.toString();
        return "WHOLESALER".equals(shopperType) ? CustomerTypeEn.WHOLESALER : CustomerTypeEn.RETAILER;
    }
}
