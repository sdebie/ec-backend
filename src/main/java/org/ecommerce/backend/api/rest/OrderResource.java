package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.enums.CustomerTypeEn;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrderService orderService;

    @POST
    @Transactional
    public Response createOrder(
            OrderCreationRequestDto request,
            @HeaderParam("Authorization") String authorizationHeader
    ) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Request body is required").build();
        }

        CustomerTypeEn customerTier = resolveCustomerTier(authorizationHeader);

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
     * Extracts the customer tier from the JWT Bearer token in the Authorization header.
     * Reads the "shopperType" claim: "WHOLESALER" maps to WHOLESALER, anything else to RETAILER.
     * Returns GUEST if no valid JWT is present.
     */
    private static CustomerTypeEn resolveCustomerTier(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return CustomerTypeEn.GUEST;
        }

        String token = authorizationHeader.substring("Bearer ".length()).trim();
        String shopperType = extractClaimFromJwt(token, "shopperType");

        if ("WHOLESALER".equals(shopperType)) {
            return CustomerTypeEn.WHOLESALER;
        }
        if (shopperType != null) {
            return CustomerTypeEn.RETAILER;
        }
        return CustomerTypeEn.GUEST;
    }

    /**
     * Decodes the JWT payload (second segment, Base64url) and extracts the named claim.
     * Returns null if the token is malformed or the claim is absent.
     */
    private static String extractClaimFromJwt(String token, String claimName) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            // Simple claim extraction: find "claimName":"value" in the JSON string
            String search = "\"" + claimName + "\"";
            int idx = payload.indexOf(search);
            if (idx < 0) {
                return null;
            }
            int colon = payload.indexOf(':', idx + search.length());
            if (colon < 0) {
                return null;
            }
            // Skip whitespace after colon
            int valueStart = colon + 1;
            while (valueStart < payload.length() && Character.isWhitespace(payload.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= payload.length()) {
                return null;
            }
            if (payload.charAt(valueStart) == '"') {
                // String value
                int valueEnd = payload.indexOf('"', valueStart + 1);
                if (valueEnd < 0) {
                    return null;
                }
                return payload.substring(valueStart + 1, valueEnd);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String padBase64(String base64url) {
        int padding = (4 - base64url.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(base64url);
        for (int i = 0; i < padding; i++) {
            sb.append('=');
        }
        return sb.toString();
    }
}
