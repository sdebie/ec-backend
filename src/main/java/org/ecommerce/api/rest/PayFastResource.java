package org.ecommerce.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.HtmlFormField;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.persistance.entity.PaymentLogEntity;
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

    /**
     * Called by React to get the secure signed data for the Onsite Modal.

    @POST
    @Path("/request")
    @Transactional
    public Map<String, String> getPaymentRequest(QuotationEntity quote) {
        Map<String, String> data = new HashMap<>();

        // 0. Try to enrich from DB (to fetch customer email/name)
        QuotationEntity persisted = null;
        if (quote != null && quote.id != null) {
            //persisted = QuotationEntity.findById(quote.id);
            quote = new QuotationEntity();
            quote.id = 1L;
            quote.totalAmount = new BigDecimal("100.00");
            CustomerEntity customer = new CustomerEntity();
            customer.email = "anything123456%40gmail.com";
            customer.firstName = "John";
            customer.lastName = "Doe";
            quote.customerEntity = customer;
            persisted = quote;
        }

        // 1. Prepare the data for PayFast
        // Merchant details will be injected by PayFastService.generateSignature(...)
        // Ensure amount formatting to two decimals as string
        data.put("amount", quote.totalAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        //data.put("item_name", "Order" + quote.id);
        data.put("item_name", "Test Product");
 //       data.put("m_payment_id", quote.id.toString());

        // PayFast Sandbox requires an email_address (and ideally a name)
        String email = null;
        String firstName = null;
        String lastName = null;
        if (persisted != null && persisted.customerEntity != null) {
            email = persisted.customerEntity.email;
            firstName = persisted.customerEntity.firstName;
            lastName = persisted.customerEntity.lastName;
        }
        if (email == null || email.isBlank()) {
            // Fallback email to satisfy PayFast sandbox validation
            email = "anything123456@gmail.com";
        }
        data.put("email_address", email);

//        if (firstName != null && !firstName.isBlank()) data.put("name_first", firstName);
//        if (lastName != null && !lastName.isBlank()) data.put("name_last", lastName);

        // 2. Generate Signature
        String signature = payFastService.generateSignature(data);
        // Grab the ordered base string exactly as used for signature
        String base = payFastService.getLastSignatureBaseString();

        // 3. Request the UUID from PayFast Onsite API using preserved order
        String uuid = fetchPayFastUuid(base, signature);

        System.out.println("DEBUG: Response: " + uuid);

        // 4. Return to React
        Map<String, String> response = new HashMap<>();
        response.put("uuid", uuid);
        return response;
    }

    private String fetchPayFastUuid(String baseStringInExactOrder, String signature) {
        try {
            // Build the body preserving the original key order from baseStringInExactOrder
            // URL-encode only the values for transmission
            // Append signature at the end

            String body = baseStringInExactOrder + '&' +
                    "signature=" +
                    URLEncoder.encode(signature, StandardCharsets.UTF_8);
            System.out.println("FINAL POST BODY: " + body);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://sandbox.payfast.co.za/onsite/process"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            System.out.println("DEBUG: Response status : " + status);

            String respBody = response.body() != null ? response.body().trim() : "";
            if (status < 200 || status >= 300) {
                throw new WebApplicationException("PayFast onsite/process HTTP " + status + " - " + respBody, status);
            }
            // PayFast returns the UUID as a plain string in the body
            if (respBody.isEmpty() || respBody.length() < 10) {
                throw new WebApplicationException("Unexpected PayFast UUID response: '" + respBody + "'", 502);
            }
            return respBody;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch PayFast UUID", e);
        }
    }
*/
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
                    order.status = "PAID";
                    // Panache will auto-dirty-check within @Transactional, but call persist() to be explicit
                    order.persist();
                    System.out.println("DEBUG: Updated Order " + orderId + " to PAID (entity update)");
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