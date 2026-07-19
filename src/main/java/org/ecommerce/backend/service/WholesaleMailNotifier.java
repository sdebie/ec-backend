package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.repository.SettingsRepository;
import org.jboss.logging.Logger;

/**
 * Observes {@link WholesaleDecisionEvent} after the decision transaction commits and
 * sends a status notification email to the applicant.
 * <p>
 * Mirrors the fire-and-log pattern of {@link ContactEnquiryMailer}: the mail send is
 * reactive and does not block the caller. A delivery failure is logged but never
 * propagated — the decision is already persisted.
 */
@ApplicationScoped
public class WholesaleMailNotifier {

    private static final Logger LOG = Logger.getLogger(WholesaleMailNotifier.class);

    private static final String BRANDING_SETTING_KEY = "storefront.branding";

    @Inject
    MailTemplate wholesale_status;

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

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
