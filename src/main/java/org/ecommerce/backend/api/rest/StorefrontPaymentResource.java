package org.ecommerce.backend.api.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.ecommerce.backend.service.SettingsService;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Public storefront endpoint returning the allowed payment methods.
 * Reads the 'payment_methods_allowed' key from store_settings and parses
 * the JSON array value into a List of strings.
 */
@Path("/api/storefront/payment-methods")
@Produces(MediaType.APPLICATION_JSON)
public class StorefrontPaymentResource
{
    private static final Logger LOG = Logger.getLogger(StorefrontPaymentResource.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SettingsService settingsService;

    @GET
    public List<String> getAllowedPaymentMethods() {
        String rawValue = settingsService.getStoreSettingValue("payment_methods_allowed");
        if (rawValue == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawValue, new TypeReference<List<String>>()
            {
            });
        } catch (Exception e) {
            LOG.error("Failed to parse payment_methods_allowed setting value", e);
            return List.of();
        }
    }
}
