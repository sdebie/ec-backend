package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.OrderEntity;
import org.jboss.logging.Logger;

/**
 * Dedicated CDI component for order-related email notifications.
 * Extracted from {@link OrderService} to separate I/O concerns from business logic.
 * <p>
 * The sender address is read from configuration ({@code quarkus.mailer.from}) —
 * never hardcoded — aligning with the wholesale-application-review-workflow mail pattern.
 */
@ApplicationScoped
public class OrderNotificationService {

    private static final Logger LOG = Logger.getLogger(OrderNotificationService.class);

    @Inject
    MailTemplate order_confirmation;

    @ConfigProperty(name = "quarkus.mailer.from")
    String senderAddress;

    /**
     * Sends an order confirmation email to the customer associated with the given order.
     * Uses the Qute {@code order_confirmation} template.
     *
     * @param order the persisted order entity (must have customerEntity populated)
     */
    public void sendConfirmationEmail(OrderEntity order) {
        String firstName = resolveDisplayName(order);
        String customerEmail = resolveRecipient(order);

        order_confirmation.to(customerEmail)
                .from(senderAddress)
                .subject("Your Order #" + order.id)
                .data("order", order)
                .data("orderItems", order.items)
                .data("customerName", firstName)
                .send()
                .subscribe().with(
                        success -> LOG.info("Order confirmation email sent to: " + customerEmail),
                        failure -> LOG.error("Order confirmation email failed for: " + customerEmail, failure)
                );
    }

    /** Display name for the greeting; falls back to "Guest" when no usable first name. */
    String resolveDisplayName(OrderEntity order) {
        String firstName = order.customerEntity != null ? order.customerEntity.firstName : null;
        return (firstName != null && !firstName.isBlank()) ? firstName : "Guest";
    }

    /** Recipient email resolved from the order's customer→user, or null when unavailable. */
    String resolveRecipient(OrderEntity order) {
        if (order.customerEntity == null || order.customerEntity.user == null) {
            return null;
        }
        return order.customerEntity.user.email;
    }
}
