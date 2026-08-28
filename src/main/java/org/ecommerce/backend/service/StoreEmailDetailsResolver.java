package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.jboss.logging.Logger;

import java.util.Currency;
import java.util.Locale;

/**
 * Assembles the store's own details for a customer email, from seed configuration,
 * never literals — the wrong shop's address on a shopper's receipt is exactly
 * the failure that would cause.
 * <p>
 * Reads the customer-facing half of {@code storefront.contact};
 * {@link EnquiryRecipientResolver} owns the staff mailbox field in the same key and is
 * deliberately left alone, because the two answer different questions.
 * <p>
 * The display name prefers {@code storefront.branding} &rarr; {@code name}, falling back
 * to {@code storefront.config} &rarr; {@code clientName} — the same precedence
 * {@code StorefrontConfigResource} uses for the storefront header/footer, so an email
 * names the store the same way the site the shopper just left did. Any other class
 * needing the store's display name for an email calls {@link #resolveStoreName()} rather
 * than reading either setting independently.
 * <p>
 * Never throws. A missing setting costs the email a line of its footer, not its delivery
 * — the order has already happened, and failing to tell the shopper about it because
 * nobody filled in a phone number would be the worse outcome.
 */
@ApplicationScoped
public class StoreEmailDetailsResolver
{
    private static final Logger LOG = Logger.getLogger(StoreEmailDetailsResolver.class);

    private static final String CONTACT_KEY = "storefront.contact";
    private static final String CONFIG_KEY = "storefront.config";
    private static final String BRANDING_KEY = "storefront.branding";

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    public StoreEmailDetails resolve()
    {
        JsonNode contact = read(CONTACT_KEY);
        JsonNode config = read(CONFIG_KEY);
        JsonNode branding = read(BRANDING_KEY);

        return new StoreEmailDetails(
                storeName(branding, config),
                currencySymbol(text(config, "currency")),
                text(contact, "physicalAddress"),
                text(contact, "businessHours"),
                text(contact, "enquiryEmail"),
                phone(contact));
    }

    /**
     * The store's display name: {@code storefront.branding} &rarr; {@code name}, falling
     * back to {@code storefront.config} &rarr; {@code clientName}. Null if neither is
     * configured.
     */
    public String resolveStoreName()
    {
        return storeName(read(BRANDING_KEY), read(CONFIG_KEY));
    }

    private static String storeName(JsonNode branding, JsonNode config)
    {
        String brandingName = text(branding, "name");
        return brandingName != null ? brandingName : text(config, "clientName");
    }

    private JsonNode read(String key)
    {
        StoreSettingsEntity setting = settingsRepository.findById(key);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
            LOG.debugf("%s is not configured; customer emails will omit the details it carries", key);
            return null;
        }
        try {
            return objectMapper.readTree(setting.getValue());
        } catch (Exception e) {
            LOG.warnf(e, "%s could not be parsed; customer emails will omit the details it carries", key);
            return null;
        }
    }

    private static String text(JsonNode node, String field)
    {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /** The first listed phone, falling back to the landline. */
    private static String phone(JsonNode contact)
    {
        if (contact == null) {
            return null;
        }
        JsonNode phones = contact.get("phones");
        if (phones != null && phones.isArray() && !phones.isEmpty()) {
            String first = phones.get(0).asText();
            if (!first.isBlank()) {
                return first;
            }
        }
        return text(contact, "landline");
    }

    /**
     * The code is what is configured; the symbol is what a shopper reads. An unknown code
     * comes back as itself — "ZAR 320.00" is clumsy but never claims the wrong currency,
     * which a hardcoded symbol would.
     */
    private static String currencySymbol(String currencyCode)
    {
        if (currencyCode == null) {
            return null;
        }
        try {
            return Currency.getInstance(currencyCode).getSymbol(Locale.getDefault());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Unknown currency code '%s' in %s; showing the code itself", currencyCode, CONFIG_KEY);
            return currencyCode;
        }
    }
}
