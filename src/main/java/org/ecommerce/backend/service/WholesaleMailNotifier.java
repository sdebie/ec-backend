package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.repository.SettingsRepository;
import org.jboss.logging.Logger;

/**
 * Observes wholesale application lifecycle events after their transactions commit:
 * <ul>
 *   <li>{@link WholesaleApplicationSubmittedEvent} — notifies the admin enquiry inbox
 *       (resolved via {@link ContactEnquiryMailer#resolveRecipient()}, i.e. the same
 *       {@code storefront.contact.enquiryEmail} address that receives contact enquiries).</li>
 *   <li>{@link WholesaleDecisionEvent} — sends a status notification email to the applicant.</li>
 * </ul>
 * Mirrors the fire-and-log pattern of {@link ContactEnquiryMailer}: the mail send is
 * reactive and does not block the caller. A delivery failure is logged but never
 * propagated — the submission/decision is already persisted.
 */
@ApplicationScoped
public class WholesaleMailNotifier {

    private static final Logger LOG = Logger.getLogger(WholesaleMailNotifier.class);

    private static final String BRANDING_SETTING_KEY = "storefront.branding";

    @Inject
    MailTemplate wholesale_status;

    @Inject
    MailTemplate wholesale_application_received;

    @Inject
    ContactEnquiryMailer contactEnquiryMailer;

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

    /**
     * Fires after a new application's submission transaction commits successfully.
     * Notifies the admin enquiry inbox ({@code storefront.contact.enquiryEmail}) that a
     * new wholesale application is awaiting review. If no enquiry recipient is
     * configured, the notification is skipped with a warning — the application itself
     * is already persisted and visible in the admin list.
     */
    public void onSubmitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) WholesaleApplicationSubmittedEvent event) {
        // Guard: blank from-address — defensive (config validation prevents it)
        if (mailerFrom == null || mailerFrom.isBlank()) {
            LOG.errorf("[WholesaleMailNotifier] from-address is blank — skipping admin notification for application %s",
                    event.applicationId());
            return;
        }

        String enquiryEmail;
        try {
            enquiryEmail = contactEnquiryMailer.resolveRecipient();
        } catch (RecipientNotConfiguredException e) {
            LOG.warnf("[WholesaleMailNotifier] admin notification skipped for application %s: %s",
                    event.applicationId(), e.getMessage());
            return;
        }

        String applicantName = event.lastName() != null && !event.lastName().isBlank()
                ? event.firstName() + " " + event.lastName()
                : event.firstName();

        var mail = wholesale_application_received.to(enquiryEmail)
                .from(mailerFrom)
                .subject("New wholesale application from " + event.companyName())
                .data("applicationId", event.applicationId())
                .data("applicantName", applicantName)
                .data("applicantEmail", event.applicantEmail())
                .data("phone", event.phone())
                .data("companyName", event.companyName())
                .data("tradingName", event.tradingName())
                .data("vatNumber", event.vatNumber());

        if (event.applicantEmail() != null && !event.applicantEmail().isBlank()) {
            mail = mail.replyTo(event.applicantEmail());
        }

        mail.send()
                .subscribe().with(
                        success -> LOG.infof("[WholesaleMailNotifier] admin notification delivered for application %s to %s",
                                event.applicationId(), enquiryEmail),
                        failure -> LOG.errorf(failure, "[WholesaleMailNotifier] admin notification failed for application %s to %s",
                                event.applicationId(), enquiryEmail)
                );
    }

    /**
     * Fires after the decision transaction commits successfully. Composes and sends a
     * wholesale status notification email to the applicant.
     */
    public void onDecision(@Observes(during = TransactionPhase.AFTER_SUCCESS) WholesaleDecisionEvent event) {
        // Guard: blank from-address — defensive (config validation prevents it)
        if (mailerFrom == null || mailerFrom.isBlank()) {
            LOG.errorf("[WholesaleMailNotifier] from-address is blank — skipping send for application %s to %s",
                    event.applicationId(), event.recipientEmail());
            return;
        }

        // Guard: missing/invalid recipient
        if (event.recipientEmail() == null || event.recipientEmail().isBlank()) {
            LOG.warnf("[WholesaleMailNotifier] skipped: no valid recipient for application %s",
                    event.applicationId());
            return;
        }

        // Resolve store name from storefront.branding setting
        String storeName = resolveStoreName(event.applicationId());

        wholesale_status.to(event.recipientEmail())
                .from(mailerFrom)
                .subject("Wholesale Application Update")
                .data("firstName", event.firstName())
                .data("storeName", storeName)
                .data("approved", event.decision() == WholesaleApplicationStatusEn.APPROVED)
                .data("rejectionReason", event.rejectionReason())
                .send()
                .subscribe().with(
                        success -> LOG.infof("[WholesaleMailNotifier] delivered for application %s to %s",
                                event.applicationId(), event.recipientEmail()),
                        failure -> LOG.errorf(failure, "[WholesaleMailNotifier] delivery failed for application %s to %s",
                                event.applicationId(), event.recipientEmail())
                );
    }

    /**
     * Resolves the store display name from the {@code storefront.branding} setting.
     * Returns the bare from-address as fallback if the setting is missing or the name is blank.
     */
    private String resolveStoreName(java.util.UUID applicationId) {
        try {
            StoreSettingsEntity setting = settingsRepository.findById(BRANDING_SETTING_KEY);
            if (setting == null || setting.value == null || setting.value.isBlank()) {
                LOG.warnf("[WholesaleMailNotifier] storefront.branding setting missing — using bare from-address for application %s",
                        applicationId);
                return mailerFrom;
            }

            JsonNode brandingNode = objectMapper.readTree(setting.value);
            JsonNode nameNode = brandingNode.get("name");

            if (nameNode == null || nameNode.isNull() || nameNode.asText().isBlank()) {
                LOG.warnf("[WholesaleMailNotifier] storefront.branding name is blank — using bare from-address for application %s",
                        applicationId);
                return mailerFrom;
            }

            return nameNode.asText();
        } catch (Exception e) {
            LOG.warnf(e, "[WholesaleMailNotifier] failed to parse storefront.branding — using bare from-address for application %s",
                    applicationId);
            return mailerFrom;
        }
    }
}
