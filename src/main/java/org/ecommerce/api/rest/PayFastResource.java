package org.ecommerce.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.HtmlFormField;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.persistance.entity.PaymentLogEntity;
import org.ecommerce.service.OrderService;
import org.ecommerce.service.payfast.PayFastService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/payments")
@Produces(MediaType.APPLICATION_JSON)
public class PayFastResource {

    @Inject
    PayFastService payFastService;

    @Inject
    OrderService orderService;

    @POST
    @Path("/checkout")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response checkout(MultivaluedMap<String, String> formParams) {
        System.out.println("DEBUG: Checkout received: " + formParams);

        List<String> orderId = formParams.get("id");
        OrderEntity quote = OrderEntity.findById(Long.parseLong(orderId.getFirst()));
        if (quote == null || quote.customerEntity == null || quote.customerEntity.email == null || quote.customerEntity.email.isBlank()) {
            System.out.println("DEBUG: Invalid Order information");
            return Response.status(Response.Status.EXPECTATION_FAILED)
                    .entity("{\"Error\": \"Request could not be processed. Please contact Admin\"}").build();
        }
        System.out.println("DEBUG: Got Order from DB with ID: " + quote.id);

        List<HtmlFormField> hiddenHTMLFormFields = payFastService.generateHiddenHTMLForm(quote);

        return Response.accepted().entity(hiddenHTMLFormFields).build();
    }

    /**
     * The Webhook (ITN) listener for PayFast to update order status.
     */
    @POST
    @Path("/itn")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response handleITN(MultivaluedMap<String, String> formParams) {
        System.out.println("DEBUG: ITN callback received");

        // Convert to standard Map
        Map<String, String> params = formParams.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst()));

        System.out.println("DEBUG: Received Signature: " + params.get("signature"));

        // 1. Security Check
//        if (!payFastService.verifySignature(params)) {
//            return Response.status(Response.Status.UNAUTHORIZED).build();
//        }

        try{
            // 2. Generic Logging
            PaymentLogEntity log = new PaymentLogEntity();
            log.gatewayName = "PAYFAST";
            log.internalReference = params.get("m_payment_id");
            log.externalReference = params.get("pf_payment_id");
            log.amountGross = new BigDecimal(params.get("amount_gross"));
            log.status = params.get("payment_status");
            // Store raw JSON for auditing
            log.rawResponse = params.toString();
            log.persist();
        }
        catch (Exception e){
            System.out.println("DEBUG: Error logging payment: " + e.getMessage());
        }

        // 3. Logic: If payment is complete, update Order
        if ("COMPLETE".equalsIgnoreCase(params.get("payment_status"))) {
            String orderIdStr = params.get("m_payment_id");
            try {
                Long orderId = Long.parseLong(orderIdStr);
                OrderEntity order = OrderEntity.findById(orderId);
                if (order != null) {
                    order.status = OrderStatusEn.PAID;
                    // Panache will auto-dirty-check within @Transactional, but call persist() to be explicit
                    order.persist();
                    System.out.println("DEBUG: Updated Order " + orderId + " to PAID (entity update)");

                    orderService.sendConfirmationEmail(order);
                } else {
                    System.out.println("DEBUG: Order not found for m_payment_id=" + orderId + "; no update performed");
                }
            } catch (NumberFormatException nfe) {
                System.out.println("DEBUG: Invalid m_payment_id received: '" + orderIdStr + "'" );
            } catch (Exception ex) {
                System.out.println("DEBUG: Failed to update Order status to PAID due to: " + ex.getMessage());
            }
        }

        return Response.ok().build();
    }
}
