package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Single owner of the staff enquiry mailbox lookup — {@code storefront.contact} →
 * {@code enquiryEmail}.
 * <p>
 * Every staff-facing notification resolves its recipient here, so the setting key and the
 * JSON shape are stated in one place. Never falls back to the {@code emails} list — that
 * would restore the positional defect this lookup replaces.
 * <p>
 * Callers differ on what an unconfigured mailbox means, so both policies are offered
 * explicitly rather than folded into one: {@link #require()} for callers that must fail
 * loudly, {@link #find()} for post-commit notifications that log and skip because the
 * originating work is already persisted.
 */
@ApplicationScoped
public class EnquiryRecipientResolver
{
    private static final Logger LOG = Logger.getLogger(EnquiryRecipientResolver.class);

    private static final String CONTACT_SETTING_KEY = "storefront.contact";

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Resolves the enquiry recipient, failing loudly when it is not configured.
     *
     * @return the configured enquiry recipient email
     * @throws RecipientNotConfiguredException if the setting is missing, unparseable, or
     *                                         {@code enquiryEmail} is absent/blank
     */
    public String require()
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

    /**
     * Resolves the enquiry recipient, treating "not configured" as an ordinary absent value.
     *
     * @return the recipient, or empty when it is missing, blank, or unparseable
     */
    public Optional<String> find()
    {
        try {
            return Optional.of(require());
        } catch (RecipientNotConfiguredException e) {
            LOG.warnf("[EnquiryRecipient] not configured: %s", e.getMessage());
            return Optional.empty();
        }
    }
}
