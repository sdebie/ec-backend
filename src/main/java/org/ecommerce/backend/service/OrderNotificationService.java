package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.entity.OrderEntity;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Dedicated CDI component for order-related email notifications.
 * Extracted from {@link OrderService} to separate I/O concerns from business logic.
 * <p>
 * The sender address is read from configuration ({@code quarkus.mailer.from}) —
 * never hardcoded — aligning with the wholesale-application-review-workflow mail pattern.
 */
@ApplicationScoped
public class OrderNotificationService
{
    private static final Logger LOG = Logger.getLogger(OrderNotificationService.class);

    @Inject
    MailTemplate order_confirmation;

    @Inject
    MailTemplate order_payment_anomaly;

    @Inject
    ContactEnquiryMailer contactEnquiryMailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String senderAddress;

    /**
     * Sends an order confirmation email to the order's recipient (linked customer
     * or guest checkout contact — see {@link #resolveRecipient}).
     * Uses the Qute {@code order_confirmation} template.
     *
     * @param order the persisted order entity
     */
    public void sendConfirmationEmail(OrderEntity order)
    {
        String firstName = resolveDisplayName(order);
        String customerEmail = resolveRecipient(order);

        order_confirmation.to(customerEmail)
                .from(senderAddress)
                .subject("Your Order #" + order.getId())
                .data("order", order)
                .data("orderItems", order.getItems())
                .data("customerName", firstName)
                .send()
                .subscribe().with(
                        success -> LOG.info("Order confirmation email sent to: " + customerEmail),
                        failure -> LOG.error("Order confirmation email failed for: " + customerEmail, failure)
                );
    }

    /**
     * Alerts staff that PayFast confirmed a payment for an order that is no longer
     * CREATED — the order's stock may no longer be reserved for it (most likely the
     * abandoned-order release job already restored it, or a staff member cancelled
     * the order). Real money moved and the order disagrees; this needs a human to
     * reconcile, so it is never a silent drop.
     * <p>
     * Recipient reuses {@link ContactEnquiryMailer#resolveRecipient()} — the same
     * admin enquiry mailbox {@link WholesaleMailNotifier} alerts staff through — since
     * that is the only staff-facing notification channel this backend has.
     */
    public void sendPaymentAnomalyAlert(OrderEntity order, BigDecimal amountReceived)
    {
        String recipient;
        try {
            recipient = contactEnquiryMailer.resolveRecipient();
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
