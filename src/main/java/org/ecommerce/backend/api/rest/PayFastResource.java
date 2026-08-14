package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.payfast.HtmlFormField;
import org.ecommerce.backend.service.payfast.PayFastService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.PaymentLogEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/payments")
@Produces(MediaType.APPLICATION_JSON)
public class PayFastResource
{
    private static final Logger LOG = Logger.getLogger(PayFastResource.class);
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    @Inject
    PayFastService payFastService;

    @Inject
    OrderNotificationService orderNotificationService;

    @ConfigProperty(name = "payfast.gateway.url")
    String gatewayUrl;

    @POST
    @Path("/checkout")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response checkout(MultivaluedMap<String, String> formParams)
    {
        LOG.debug("Checkout received: " + formParams);

        String orderIdParam = formParams.getFirst("id");
        if (orderIdParam == null || orderIdParam.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Order ID is required")).build();
        }

        UUID orderUuid = UUID.fromString(orderIdParam);
        OrderEntity quote = OrderEntity.findById(orderUuid);
        if (quote == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found")).build();
        }

        // Resolve email: form param takes priority, then fall back to customerEntity.user.email
        String formEmail = formParams.getFirst("email");
        String email = resolveEmail(formEmail, quote);

        if (email == null || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Email is required")).build();
        }

        LOG.debug("Got Order from DB with ID: " + quote.getId());

        List<HtmlFormField> hiddenHTMLFormFields = payFastService.generateHiddenHTMLForm(quote, email);

        return Response.accepted()
                .entity(Map.of("gatewayUrl", gatewayUrl, "fields", hiddenHTMLFormFields))
                .build();
    }

    /**
     * Resolves the email to use for checkout.
     * Prefers the form-param email; falls back to the customer entity user email.
     */
    private String resolveEmail(String formEmail, OrderEntity order)
    {
        if (formEmail != null && !formEmail.isBlank()) {
            return formEmail.trim();
        }
        if (order.getCustomerEntity() != null && order.getCustomerEntity().getUser() != null
                && order.getCustomerEntity().getUser().getEmail() != null && !order.getCustomerEntity().getUser().getEmail().isBlank()) {
            return order.getCustomerEntity().getUser().getEmail();
        }
        return null;
    }

    /**
     * The Webhook (ITN) listener for PayFast to update order status.
     * Takes the raw body because the signature covers the parameters in posted order.
     */
    @POST
    @Path("/itn")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response handleITN(
            String rawBody,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp)
    {
        LOG.debug("ITN callback received");

        // 1. Signature check
        if (!payFastService.verifyItnSignature(rawBody)) {
            LOG.warn("ITN callback rejected: signature verification failed");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // 2. Source check — a leaked passphrase is enough to forge a valid signature
        // offline, so the signature alone cannot prove the request came from PayFast.
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        if (!payFastService.isTrustedSource(clientIp)) {
            LOG.warn("ITN callback rejected: untrusted source IP " + clientIp);
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Map<String, String> params = parseFormBody(rawBody);

        BigDecimal amountGross = null;
        try {
            amountGross = new BigDecimal(params.get("amount_gross"));
        } catch (Exception e) {
            LOG.warn("ITN amount_gross could not be parsed: '" + params.get("amount_gross") + "'");
        }

        try {
            // 3. Generic Logging
            PaymentLogEntity log = new PaymentLogEntity();
            log.setGatewayName("PAYFAST");
            log.setInternalReference(params.get("m_payment_id"));
            log.setExternalReference(params.get("pf_payment_id"));
            log.setAmountGross(amountGross);
            log.setStatus(params.get("payment_status"));
            // Store raw JSON for auditing
            log.setRawResponse(params.toString());
            log.persist();
        } catch (Exception e) {
            LOG.error("Error logging payment: " + e.getMessage());
        }

        // 4. Logic: If payment is complete, validate and update Order
        if ("COMPLETE".equalsIgnoreCase(params.get("payment_status"))) {
            String orderIdStr = params.get("m_payment_id");
            try {
                UUID orderId = UUID.fromString(orderIdStr);
                OrderEntity order = OrderEntity.findById(orderId);
                if (order != null) {
                    if (order.getStatus() == OrderStatusEn.PAID) {
                        // PayFast retries ITNs it doesn't get a definitive ack for;
                        // a replay of an already-processed payment is not an error.
                        LOG.debug("Order " + orderId + " already PAID; ignoring duplicate ITN");
                    } else if (amountGross == null
                            || amountGross.subtract(order.getTotalAmount()).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                        LOG.warn("ITN rejected: amount_gross " + amountGross + " does not match order total "
                                + order.getTotalAmount() + " for order " + orderId);
                        return Response.status(Response.Status.UNAUTHORIZED).build();
                    } else if (!payFastService.confirmWithPayFast(rawBody)) {
                        LOG.warn("ITN rejected: PayFast server confirmation failed for order " + orderId);
                        return Response.status(Response.Status.UNAUTHORIZED).build();
                    } else {
                        order.setStatus(OrderStatusEn.PAID);
                        // Panache will auto-dirty-check within @Transactional, but call persist() to be explicit
                        order.persist();
                        LOG.debug("Updated Order " + orderId + " to PAID (entity update)");

                        orderNotificationService.sendConfirmationEmail(order);
                    }
                } else {
                    LOG.warn("Order not found for m_payment_id=" + orderId + "; no update performed");
                }
            } catch (IllegalArgumentException nfe) {
                LOG.warn("Invalid m_payment_id received: '" + orderIdStr + "'");
            } catch (Exception ex) {
                LOG.error("Failed to update Order status to PAID due to: " + ex.getMessage());
            }
        }

        return Response.ok().build();
    }

    /**
     * Parses a form-urlencoded body into a map, keeping the first value per key.
     */
    private static Map<String, String> parseFormBody(String rawBody)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawBody.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.putIfAbsent(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            );
        }
        return params;
    }
}
