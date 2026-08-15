package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.CustomerNotification;
import org.ecommerce.common.enums.OrderStatusEn;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Order emails.
 * <p>
 * <b>Nothing here decides whether to send.</b> That is
 * {@link OrderStatusEn#customerNotification()}, and only {@code applyTransition} acts on
 * it. Before that, one confirmation email was sent from three separate call sites that
 * each decided for themselves — so a new writer sent nothing and nothing caught it, the
 * same defect class the stock rules already had.
 * <p>
 * A send never fails a transition. The order has already moved and the change is already
 * committed by the time anyone would notice a bounce; failing the operation because an
 * SMTP host was unreachable would undo real work to report a delivery problem. Failures
 * are logged and the transition stands.
 * <p>
 * Sender address comes from configuration ({@code quarkus.mailer.from}), never hardcoded.
 */
@ApplicationScoped
public class OrderNotificationService
{
    private static final Logger LOG = Logger.getLogger(OrderNotificationService.class);

    // One injection per template; the field name is the template file name.
    @Inject
    MailTemplate order_action_required;

    @Inject
    MailTemplate order_confirmed;

    @Inject
    MailTemplate order_ready_for_collection;

    @Inject
    MailTemplate order_in_transit;

    @Inject
    MailTemplate order_completed;

    @Inject
    MailTemplate order_delivery_problem;

    @Inject
    MailTemplate order_ended;

    @Inject
    MailTemplate order_refunded;

    @Inject
    MailTemplate order_payment_anomaly;

    @Inject
    EnquiryRecipientResolver enquiryRecipientResolver;

    @Inject
    StoreEmailDetailsResolver storeEmailDetailsResolver;

    @ConfigProperty(name = "quarkus.mailer.from")
    String senderAddress;

    /**
     * How long an unpaid order keeps its stock, quoted to the shopper so a deadline in an
     * email is the deadline the sweep actually enforces rather than a number written into
     * a template and left to rot.
     */
    @ConfigProperty(name = "order.abandoned.hold-minutes", defaultValue = "30")
    int holdMinutes;

    /**
     * Sends whatever email this status calls for, or none.
     * <p>
     * Called only from {@code OrderService.applyTransition}, after the status claim is
     * won — so an email is never sent for a transition that lost a race, and every writer
     * gets the same behaviour without asking for it.
     */
    public void sendStatusNotification(OrderEntity order, OrderStatusEn status)
    {
        CustomerNotification notification = status.customerNotification();
        if (notification == CustomerNotification.NONE) {
            return;
        }

        String recipient = resolveRecipient(order);
        if (recipient == null || recipient.isBlank()) {
            LOG.warnf("Order %s reached %s with no contactable email address; no notification sent",
                    order.getId(), status);
            return;
        }

        StoreEmailDetails store = storeEmailDetailsResolver.resolve();
        MailTemplate.MailTemplateInstance mail = templateFor(notification)
                .to(recipient)
                .from(senderAddress)
                .subject(subjectFor(notification, status, order))
                .data("order", order)
                .data("status", status)
                .data("store", store)
                .data("customerName", resolveDisplayName(order));

        // Only the templates that ask for these declare them, but Qute needs every
        // referenced key present — supplying them for all is cheaper than branching.
        mail = mail.data("orderItems", order.getItems())
                .data("holdMinutes", holdMinutes);

        send(mail, recipient, order, status);
    }

    private MailTemplate templateFor(CustomerNotification notification)
    {
        return switch (notification) {
            case ACTION_REQUIRED -> order_action_required;
            case CONFIRMED -> order_confirmed;
            case READY_FOR_COLLECTION -> order_ready_for_collection;
            case IN_TRANSIT -> order_in_transit;
            case COMPLETED -> order_completed;
            case DELIVERY_PROBLEM -> order_delivery_problem;
            case ENDED -> order_ended;
            case REFUNDED -> order_refunded;
            case NONE -> throw new IllegalStateException("NONE is filtered before reaching here");
        };
    }

    /**
     * Subjects say what happened and name the order, because that is what a shopper scans
     * for in a crowded inbox. Where one template serves several statuses the subject still
     * distinguishes them — "cancelled" and "expired" are not the same news.
     */
    private String subjectFor(CustomerNotification notification, OrderStatusEn status, OrderEntity order)
    {
        String reference = order.getReference();
        return switch (notification) {
            case ACTION_REQUIRED -> switch (status) {
                case PAYMENT_FAILED -> "Payment failed for order " + reference;
                case IN_STORE_PAYMENT -> "Order " + reference + " is reserved for collection";
                default -> "Waiting for payment on order " + reference;
            };
            case CONFIRMED -> "Payment received for order " + reference;
            case READY_FOR_COLLECTION -> "Order " + reference + " is ready to collect";
            case IN_TRANSIT -> "Order " + reference + " is on its way";
            case COMPLETED -> status == OrderStatusEn.COLLECTED
                    ? "Thanks for collecting order " + reference
                    : "Order " + reference + " has been delivered";
            case DELIVERY_PROBLEM -> status == OrderStatusEn.RETURNED_TO_ORIGIN
                    ? "Order " + reference + " has been returned to us"
                    : "We couldn't deliver order " + reference;
            case ENDED -> status == OrderStatusEn.SYSTEM_CANCELED
                    ? "Order " + reference + " expired before payment"
                    : "Order " + reference + " has been cancelled";
            case REFUNDED -> status == OrderStatusEn.PARTIALLY_REFUNDED
                    ? "Partial refund for order " + reference
                    : "Refund for order " + reference;
            case NONE -> throw new IllegalStateException("NONE is filtered before reaching here");
        };
    }

    private void send(MailTemplate.MailTemplateInstance mail, String recipient,
                      OrderEntity order, OrderStatusEn status)
    {
        try {
            mail.send().subscribe().with(
                    success -> LOG.debugf("Sent %s notification for order %s to %s",
                            status, order.getId(), recipient),
                    failure -> LOG.errorf(failure, "Could not send %s notification for order %s to %s",
                            status, order.getId(), recipient));
        } catch (RuntimeException e) {
            // Template resolution and rendering happen synchronously, so a broken template
            // throws here rather than reaching the subscriber. It must not take the
            // transition down with it.
            LOG.errorf(e, "Could not build the %s notification for order %s", status, order.getId());
        }
    }

    /**
     * Alerts staff that PayFast confirmed a payment for an order that is no longer
     * awaiting one — the order's stock may no longer be reserved for it (most likely the
     * abandoned-order sweep already restored it, or a staff member cancelled the order).
     * Real money moved and the order disagrees; this needs a human to reconcile, so it is
     * never a silent drop.
     * <p>
     * Recipient is the shared admin enquiry mailbox ({@link EnquiryRecipientResolver}),
     * the same one {@link WholesaleMailNotifier} alerts staff through — that is the only
     * staff-facing notification channel this backend has.
     */
    public void sendPaymentAnomalyAlert(OrderEntity order, BigDecimal amountReceived)
    {
        String recipient;
        try {
            recipient = enquiryRecipientResolver.require();
        } catch (RecipientNotConfiguredException e) {
            LOG.errorf("Payment anomaly alert for order %s could not be sent — no recipient configured: %s",
                    order.getId(), e.getMessage());
            return;
        }

        order_payment_anomaly.to(recipient)
                .from(senderAddress)
                .subject("Payment received for order #" + order.getId() + " that is no longer active")
                .data("orderId", order.getId())
                .data("orderStatus", order.getStatus())
                .data("amountReceived", amountReceived)
                .data("orderTotal", order.getTotalAmount())
                .send()
                .subscribe().with(
                        success -> LOG.infof("Payment anomaly alert delivered for order %s to %s", order.getId(), recipient),
                        failure -> LOG.errorf(failure, "Payment anomaly alert failed for order %s to %s", order.getId(), recipient)
                );
    }

    /**
     * Display name for the greeting: customer account name first, then the
     * checkout contact name (guests), then "Guest" when neither is usable.
     */
    String resolveDisplayName(OrderEntity order)
    {
        String firstName = order.getCustomerEntity() != null ? order.getCustomerEntity().getFirstName() : null;
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        String contactFirstName = order.getContactFirstName();
        return (contactFirstName != null && !contactFirstName.isBlank()) ? contactFirstName : "Guest";
    }

    /**
     * Recipient email: the order's linked customer→user first, then the
     * checkout contact email (guests never have a customerEntity), or null
     * when neither is available.
     */
    String resolveRecipient(OrderEntity order)
    {
        if (order.getCustomerEntity() != null && order.getCustomerEntity().getUser() != null
                && order.getCustomerEntity().getUser().getEmail() != null
                && !order.getCustomerEntity().getUser().getEmail().isBlank()) {
            return order.getCustomerEntity().getUser().getEmail();
        }
        return order.getContactEmail();
    }
}
