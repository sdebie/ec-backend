package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.OrderTotals;
import org.ecommerce.common.dto.OrderContactRequestDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api/orders/{orderId}/contact")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderContactResource
{
    private static final Logger LOG = Logger.getLogger(OrderContactResource.class);

    @Inject
    OrderService orderService;

    @PATCH
    @Transactional
    public Response updateContact(
            @PathParam("orderId") UUID orderId,
            OrderContactRequestDto request
    )
    {
        // 1. Find order by ID → 404 if missing
        OrderEntity order = OrderEntity.findOrderInfoById(orderId);
        if (order == null) {
            LOG.debugf("Order not found: %s", orderId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }

        // 1b. Contact/address/shipping are only mutable while the order is still
        // being assembled at checkout. Once it has left CREATED (paid, fulfilled,
        // cancelled, ...) this must not be able to rewrite delivery details or
        // force a reprice on a total the shopper already paid.
        if (order.getStatus() != OrderStatusEn.CREATED) {
            LOG.warnf("Rejected contact update for order %s in status %s", orderId, order.getStatus());
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Order can no longer be modified"))
                    .build();
        }

        // 2. Validate shippingMethodId if present → 422 if invalid/inactive
        ShippingMethodEntity shippingMethod = null;
        if (request.getShippingMethodId() != null && !request.getShippingMethodId().isBlank()) {
            UUID shippingMethodUuid;
            try {
                shippingMethodUuid = UUID.fromString(request.getShippingMethodId());
            } catch (IllegalArgumentException e) {
                return Response.status(422)
                        .entity(Map.of("error", "Invalid shipping method ID format"))
                        .build();
            }

            shippingMethod = ShippingMethodEntity.findById(shippingMethodUuid);
            if (shippingMethod == null || !shippingMethod.isActive()) {
                LOG.debugf("Invalid or inactive shipping method: %s", request.getShippingMethodId());
                return Response.status(422)
                        .entity(Map.of("error", "Shipping method not found or inactive"))
                        .build();
            }
        }

        // 3. Persist contact + address fields
        order.setContactEmail(request.getEmail());
        order.setContactFirstName(request.getFirstName());
        order.setContactLastName(request.getLastName());

        if (shippingMethod != null) {
            order.setShippingMethod(shippingMethod);
        }

        if (request.getStreetAddress() != null) {
            order.setStreetAddress(request.getStreetAddress());
        }
        if (request.getCity() != null) {
            order.setCity(request.getCity());
        }
        if (request.getProvince() != null) {
            order.setProvince(request.getProvince());
        }
        if (request.getPostalCode() != null) {
            order.setPostalCode(request.getPostalCode());
        }

        // No explicit persist needed — entity is managed within @Transactional
        // Hibernate dirty-checking will flush changes at commit

        // 4. Reprice. The order was created before a delivery method existed, so
        // its total carried the DEFAULT estimate. Now that one is selected the
        // total has to follow it — otherwise the checkout summary shows, and the
        // payment gateway charges, a figure that excludes the chosen delivery.
        OrderTotals totals = orderService.repriceOrder(order);

        // 5. Return 200 with the updated order summary, including the breakdown
        // the checkout page needs to show a total that matches what will be charged.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", order.getId().toString());
        summary.put("contactEmail", order.getContactEmail());
        summary.put("contactFirstName", order.getContactFirstName());
        summary.put("contactLastName", order.getContactLastName());
        summary.put("subtotal", totals.subtotal());
        summary.put("vatAmount", totals.vatAmount());
        summary.put("shippingEstimate", totals.shippingEstimate());
        summary.put("grandTotal", totals.grandTotal());
        summary.put("totalAmount", order.getTotalAmount());
        summary.put("status", order.getStatus().name());

        if (order.getShippingMethod() != null) {
            summary.put("shippingMethodId", order.getShippingMethod().getId().toString());
            summary.put("shippingMethodName", order.getShippingMethod().getName());
        }

        if (order.getStreetAddress() != null) {
            summary.put("streetAddress", order.getStreetAddress());
        }
        if (order.getCity() != null) {
            summary.put("city", order.getCity());
        }
        if (order.getProvince() != null) {
            summary.put("province", order.getProvince());
        }
        if (order.getPostalCode() != null) {
            summary.put("postalCode", order.getPostalCode());
        }

        LOG.infof("Updated contact for order %s (email: %s)", orderId, order.getContactEmail());

        return Response.ok(summary).build();
    }
}
