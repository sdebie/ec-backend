package org.ecommerce.backend.api.rest;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.dto.OrderContactRequestDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api/orders/{orderId}/contact")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderContactResource {

    private static final Logger LOG = Logger.getLogger(OrderContactResource.class);

    @PATCH
    @Transactional
    public Response updateContact(
            @PathParam("orderId") UUID orderId,
            OrderContactRequestDto request
    ) {
        // 1. Find order by ID → 404 if missing
        OrderEntity order = OrderEntity.findOrderInfoById(orderId);
        if (order == null) {
            LOG.debugf("Order not found: %s", orderId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        // 2. Validate shippingMethodId if present → 422 if invalid/inactive
        ShippingMethodEntity shippingMethod = null;
        if (request.shippingMethodId != null && !request.shippingMethodId.isBlank()) {
            UUID shippingMethodUuid;
            try {
                shippingMethodUuid = UUID.fromString(request.shippingMethodId);
            } catch (IllegalArgumentException e) {
                return Response.status(422)
                        .entity(Map.of("error", "Invalid shipping method ID format"))
                        .build();
            }

            shippingMethod = ShippingMethodEntity.findById(shippingMethodUuid);
            if (shippingMethod == null || !shippingMethod.isActive) {
                LOG.debugf("Invalid or inactive shipping method: %s", request.shippingMethodId);
                return Response.status(422)
                        .entity(Map.of("error", "Shipping method not found or inactive"))
                        .build();
            }
        }

        // 3. Persist contact + address fields
        order.contactEmail = request.email;
        order.contactFirstName = request.firstName;
        order.contactLastName = request.lastName;

        if (shippingMethod != null) {
            order.shippingMethod = shippingMethod;
        }

        if (request.streetAddress != null) {
            order.streetAddress = request.streetAddress;
        }
        if (request.city != null) {
            order.city = request.city;
        }
        if (request.province != null) {
            order.province = request.province;
        }
        if (request.postalCode != null) {
            order.postalCode = request.postalCode;
        }

        // No explicit persist needed — entity is managed within @Transactional
        // Hibernate dirty-checking will flush changes at commit

        // 4. Return 200 with updated order summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", order.id.toString());
        summary.put("contactEmail", order.contactEmail);
        summary.put("contactFirstName", order.contactFirstName);
        summary.put("contactLastName", order.contactLastName);
        summary.put("totalAmount", order.totalAmount);
        summary.put("status", order.status.name());

        if (order.shippingMethod != null) {
            summary.put("shippingMethodId", order.shippingMethod.id.toString());
            summary.put("shippingMethodName", order.shippingMethod.name);
        }

        if (order.streetAddress != null) {
            summary.put("streetAddress", order.streetAddress);
        }
        if (order.city != null) {
            summary.put("city", order.city);
        }
        if (order.province != null) {
            summary.put("province", order.province);
        }
        if (order.postalCode != null) {
            summary.put("postalCode", order.postalCode);
        }

        LOG.infof("Updated contact for order %s (email: %s)", orderId, order.contactEmail);

        return Response.ok(summary).build();
    }
}
