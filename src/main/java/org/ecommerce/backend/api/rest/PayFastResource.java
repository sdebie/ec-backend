package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.service.OrderNotificationService;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.backend.service.StatusTransition;
import org.ecommerce.backend.service.TransitionOutcome;
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

    @Inject
    OrderService orderService;

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

        // Invoking the gateway is what moves the order to PENDING_PAYMENT — the status
        // the ITN then claims from. A retry after a declined payment comes back through
        // here from PAYMENT_FAILED, which is why the source is whatever the order is in
        // rather than CREATED. An order already at PENDING_PAYMENT is a shopper who went
        // back and resubmitted, so it stays put rather than erroring.
        OrderStatusEn from = quote.getStatus();
        if (from != OrderStatusEn.PENDING_PAYMENT) {
            TransitionOutcome outcome = orderService.applyTransition(quote,
                    StatusTransition.system(from, OrderStatusEn.PENDING_PAYMENT, "Payment started"));
            if (!outcome.claimed()) {
                LOG.warnf("Could not start payment for order %s: it is %s", orderUuid, quote.getStatus());
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Order can no longer be paid")).build();
            }
        }

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
                        // The order could have been cancelled by the abandoned-order sweep,
                        // or moved by a staff action, between the read above and this write.
                        // Naming PENDING_PAYMENT as the expected status makes that a lost
                        // claim rather than an exception, and the claim itself is atomic —
                        // so a payment can never overwrite a decision another writer made.
                        TransitionOutcome outcome = orderService.applyTransition(order,
                                StatusTransition.system(OrderStatusEn.PENDING_PAYMENT, OrderStatusEn.PAID,
                                        "Payment confirmed by PayFast"));

                        if (outcome.claimed()) {
                            LOG.debug("Updated Order " + orderId + " to PAID");
                            orderNotificationService.sendConfirmationEmail(order);
                        } else {
                            handlePaidButNoLongerPending(orderId, order, amountGross);
                        }
                    }
                } else {
                    LOG.warn("Order not found for m_payment_id=" + orderId + "; no update performed");
                }
            } catch (IllegalArgumentException nfe) {
                LOG.warn("Invalid m_payment_id received: '" + orderIdStr + "'");
            } catch (Exception ex) {
                LOG.error("Failed to update Order status to PAID due to: " + ex.getMessage());
            }
        } else if ("FAILED".equalsIgnoreCase(params.get("payment_status"))) {
            recordFailedPayment(params.get("m_payment_id"));
        }

        return Response.ok().build();
    }

    /**
     * The gateway declined the payment. The order keeps its reservation on purpose —
     * the shopper is told to retry, and releasing their items first would leave nothing
     * to retry against. The abandoned-order sweep reclaims it if the retry never comes,
     * which is why PAYMENT_FAILED is one of the statuses it covers.
     * <p>
     * A decline that arrives for an order which is no longer awaiting payment is not an
     * anomaly worth alerting on: no money changed hands, so the worst case is a stale
     * notification for an order somebody already cancelled.
     */
    private void recordFailedPayment(String orderIdStr)
    {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            LOG.warn("Invalid m_payment_id on a failed-payment ITN: '" + orderIdStr + "'");
            return;
        }

        OrderEntity order = OrderEntity.findById(orderId);
        if (order == null) {
            LOG.warn("Order not found for failed-payment ITN m_payment_id=" + orderId);
            return;
        }

        TransitionOutcome outcome = orderService.applyTransition(order,
                StatusTransition.system(OrderStatusEn.PENDING_PAYMENT, OrderStatusEn.PAYMENT_FAILED,
                        "Payment declined by PayFast"));

        if (outcome.claimed()) {
            LOG.debug("Order " + orderId + " marked PAYMENT_FAILED; its stock stays reserved for a retry");
        } else {
            LOG.debug("Ignoring failed-payment ITN for order " + orderId + ": it is " + order.getStatus());
        }
    }

    /**
     * PayFast has confirmed a real payment, but the order is no longer awaiting one —
     * the atomic claim above lost. Re-reads the current status to tell apart the two
     * possible causes: a concurrent duplicate ITN already won (already PAID — the
     * same harmless replay case the fast-path above usually catches, just arrived
     * out of order) versus anything else (most likely the abandoned-order release
     * job cancelled it and gave its stock back before this payment confirmation
     * arrived). The second case is money received against stock that may no longer
     * be reserved for it — that needs a human to reconcile, not a silent drop.
     */
    private void handlePaidButNoLongerPending(UUID orderId, OrderEntity staleOrder, BigDecimal amountGross)
    {
        OrderEntity current = OrderEntity.findById(orderId);
        OrderStatusEn currentStatus = current != null ? current.getStatus() : null;

        if (currentStatus == OrderStatusEn.PAID) {
            LOG.debug("Order " + orderId + " was concurrently marked PAID; ignoring");
            return;
        }

        LOG.errorf("PayFast confirmed payment for order %s but it is no longer CREATED (current status: %s) — "
                + "payment received, stock may not be reserved. Manual review required.", orderId, currentStatus);
        orderNotificationService.sendPaymentAnomalyAlert(current != null ? current : staleOrder, amountGross);
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
