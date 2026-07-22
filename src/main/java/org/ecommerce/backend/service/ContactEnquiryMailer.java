package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.jboss.logging.Logger;

/**
 * Resolves the enquiry recipient from {@code storefront.contact.enquiryEmail} and
 * delivers enquiry emails using the Qute {@code contact_enquiry} template.
 * <p>
 * Mirrors the fire-and-log pattern of {@link OrderNotificationService}: the mail
 * send is reactive and does not block the caller. A delivery failure is logged with
 * the {@code [ContactEnquiry]} marker but is not propagated back to the HTTP response
 * (the resource has already returned {@code 202}).
 */
@ApplicationScoped
public class ContactEnquiryMailer
{
    private static final Logger LOG = Logger.getLogger(ContactEnquiryMailer.class);

    private static final String CONTACT_SETTING_KEY = "storefront.contact";

    @Inject
    MailTemplate contact_enquiry;

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

    /**
     * Resolves the enquiry recipient and sends the enquiry email.
     *
     * @param dto the validated enquiry submission
     * @throws RecipientNotConfiguredException if {@code enquiryEmail} is absent or blank
     */
    public void send(ContactEnquiryRequestDto dto)
    {
        String enquiryEmail = resolveRecipient();

        contact_enquiry.to(enquiryEmail)
                .from(mailerFrom)
                .replyTo(dto.email())
                .subject("New enquiry from " + dto.name())
                .data("name", dto.name())
                .data("email", dto.email())
                .data("phone", dto.phone())
                .data("company", dto.company())
                .data("message", dto.message())
                .send()
                .subscribe().with(
                        success -> LOG.infof("[ContactEnquiry] delivered to %s (from: %s)", enquiryEmail, dto.email()),
                        failure -> LOG.errorf(failure, "[ContactEnquiry] delivery failed for enquiry from %s", dto.email())
                );
    }

    /**
     * Resolves the enquiry recipient from the {@code storefront.contact} setting.
     * <p>
     * Reads the setting row, parses its JSON value, and extracts {@code enquiryEmail}.
     * Never falls back to the {@code emails} list — that would restore the positional
     * defect this feature replaces.
     *
     * @return the configured enquiry recipient email
     * @throws RecipientNotConfiguredException if the setting is missing, unparseable,
     *                                         or {@code enquiryEmail} is absent/blank
     */
    String resolveRecipient()
    {
        StoreSettingsEntity setting = settingsRepository.findById(CONTACT_SETTING_KEY);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
            throw new RecipientNotConfiguredException(
                    "storefront.contact setting row is missing or empty");
        }

        try {
            JsonNode contactNode = objectMapper.readTree(setting.getValue());
            JsonNode emailNode = contactNode.get("enquiryEmail");

            if (emailNode == null || emailNode.isNull() || emailNode.asText().isBlank()) {
                throw new RecipientNotConfiguredException(
                        "enquiryEmail is absent or blank in storefront.contact");
            }

            return emailNode.asText();
        } catch (RecipientNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new RecipientNotConfiguredException(
                    "Failed to parse storefront.contact JSON: " + e.getMessage());
        }
    }
}
