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
 *       (resolved via {@link EnquiryRecipientResolver}, i.e. the same
 *       {@code storefront.contact.enquiryEmail} address that receives contact enquiries).</li>
 *   <li>{@link WholesaleDecisionEvent} — sends a status notification email to the applicant.</li>
 * </ul>
 * Fire-and-log: the mail send is reactive and does not block the caller. A delivery
 * failure is logged but never propagated — the submission/decision is already persisted.
 */
@ApplicationScoped
public class WholesaleMailNotifier
{
    private static final Logger LOG = Logger.getLogger(WholesaleMailNotifier.class);

    private static final String BRANDING_SETTING_KEY = "storefront.branding";

    @Inject
    MailTemplate wholesale_status;

    @Inject
    MailTemplate wholesale_application_received;

    @Inject
    MailTemplate wholesale_registration;

    @Inject
    EnquiryRecipientResolver enquiryRecipientResolver;

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

    @ConfigProperty(name = "frontend.base-url")
    String frontendBaseUrl;

    /**
     * Fires after a new application's submission transaction commits successfully.
     * Sends two independent emails:
     * <ul>
     *   <li>Admin notification to the enquiry inbox ({@code storefront.contact.enquiryEmail}).
     *       If no enquiry recipient is configured, this is skipped with a warning — the
     *       application itself is already persisted and visible in the admin list.</li>
     *   <li>Applicant confirmation ("we received your application") echoing the full
     *       submitted details back to {@code applicantEmail}.</li>
     * </ul>
     * A skip or failure of one never blocks the other.
     */
    public void onSubmitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) WholesaleApplicationSubmittedEvent event)
    {
        // Guard: blank from-address — defensive (config validation prevents it)
        if (mailerFrom == null || mailerFrom.isBlank()) {
            LOG.errorf("[WholesaleMailNotifier] from-address is blank — skipping submission emails for application %s", event.applicationId());
            return;
        }

        sendAdminNotification(event);
        sendApplicantConfirmation(event);
    }

    private void sendAdminNotification(WholesaleApplicationSubmittedEvent event)
    {
        String enquiryEmail;
        try {
            enquiryEmail = enquiryRecipientResolver.require();
        } catch (RecipientNotConfiguredException e) {
            LOG.warnf("[WholesaleMailNotifier] admin notification skipped for application %s: %s", event.applicationId(), e.getMessage());
            return;
        }

        String applicantEmail = event.application().getApplicantEmail();

        var mail = wholesale_application_received.to(enquiryEmail)
                .from(mailerFrom)
                .subject("New wholesale application from " + event.application().getCompanyName())
                .data("applicationId", event.applicationId())
                .data("app", event.application());

        if (applicantEmail != null && !applicantEmail.isBlank()) {
            mail = mail.replyTo(applicantEmail);
        }

        mail.send().subscribe().with(
                success -> LOG.infof("[WholesaleMailNotifier] admin notification delivered for application %s to %s", event.applicationId(), enquiryEmail),
                failure -> LOG.errorf(failure, "[WholesaleMailNotifier] admin notification failed for application %s to %s", event.applicationId(), enquiryEmail)
        );
    }

    private void sendApplicantConfirmation(WholesaleApplicationSubmittedEvent event)
    {
        String applicantEmail = event.application().getApplicantEmail();
        if (applicantEmail == null || applicantEmail.isBlank()) {
            LOG.warnf("[WholesaleMailNotifier] applicant confirmation skipped: no valid applicantEmail for application %s", event.applicationId());
            return;
        }

        String storeName = resolveStoreName(event.applicationId());

        wholesale_registration.to(applicantEmail)
                .from(mailerFrom)
                .subject("Wholesale Application Received")
                .data("applicationId", event.applicationId())
                .data("storeName", storeName)
                .data("app", event.application())
                .send()
                .subscribe().with(
                        success -> LOG.infof("[WholesaleMailNotifier] applicant confirmation delivered for application %s to %s", event.applicationId(), applicantEmail),
                        failure -> LOG.errorf(failure, "[WholesaleMailNotifier] applicant confirmation failed for application %s to %s", event.applicationId(), applicantEmail)
                );
    }

    /**
     * Fires after the decision transaction commits successfully. Composes and sends a
     * wholesale status notification email to the applicant.
     */
    public void onDecision(@Observes(during = TransactionPhase.AFTER_SUCCESS) WholesaleDecisionEvent event)
    {
        // Guard: blank from-address — defensive (config validation prevents it)
        if (mailerFrom == null || mailerFrom.isBlank()) {
            LOG.errorf("[WholesaleMailNotifier] from-address is blank — skipping send for application %s to %s", event.applicationId(), event.recipientEmail());
            return;
        }

        // Guard: missing/invalid recipient
        if (event.recipientEmail() == null || event.recipientEmail().isBlank()) {
            LOG.warnf("[WholesaleMailNotifier] skipped: no valid recipient for application %s", event.applicationId());
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
                .data("newAccountCreated", event.newAccountCreated())
                .data("forgotPasswordUrl", frontendBaseUrl + "/account/forgot-password")
                .send()
                .subscribe().with(
                        success -> LOG.infof("[WholesaleMailNotifier] delivered for application %s to %s", event.applicationId(), event.recipientEmail()),
                        failure -> LOG.errorf(failure, "[WholesaleMailNotifier] delivery failed for application %s to %s", event.applicationId(), event.recipientEmail())
                );
    }

    /**
     * Resolves the store display name from the {@code storefront.branding} setting.
     * Returns the bare from-address as fallback if the setting is missing or the name is blank.
     */
    private String resolveStoreName(java.util.UUID applicationId)
    {
        try {
            StoreSettingsEntity setting = settingsRepository.findById(BRANDING_SETTING_KEY);
            if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
                LOG.warnf("[WholesaleMailNotifier] storefront.branding setting missing — using bare from-address for application %s", applicationId);
                return mailerFrom;
            }

            JsonNode brandingNode = objectMapper.readTree(setting.getValue());
            JsonNode nameNode = brandingNode.get("name");

            if (nameNode == null || nameNode.isNull() || nameNode.asText().isBlank()) {
                LOG.warnf("[WholesaleMailNotifier] storefront.branding name is blank — using bare from-address for application %s", applicationId);
                return mailerFrom;
            }

            return nameNode.asText();
        } catch (Exception e) {
            LOG.warnf(e, "[WholesaleMailNotifier] failed to parse storefront.branding — using bare from-address for application %s", applicationId);
            return mailerFrom;
        }
    }
}
