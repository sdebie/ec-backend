package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreEmailDetailsResolver}: the store display name prefers
 * {@code storefront.branding} over {@code storefront.config}, matching the precedence
 * {@code StorefrontConfigResource} already uses for the storefront header/footer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreEmailDetailsResolver — store name precedence")
class StoreEmailDetailsResolverTest
{
    @InjectMocks
    private StoreEmailDetailsResolver resolver;

    @Mock
    private SettingsRepository settingsRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private StoreSettingsEntity setting(String key, String json)
    {
        StoreSettingsEntity entity = new StoreSettingsEntity();
        entity.setKey(key);
        entity.setValue(json);
        return entity;
    }

    @Test
    @DisplayName("resolve() prefers storefront.branding name over storefront.config clientName when they differ")
    void resolveUsesBrandingNameOverConfigClientName()
    {
        when(settingsRepository.findById("storefront.branding"))
                .thenReturn(setting("storefront.branding", "{\"name\": \"Branding Name\"}"));
        when(settingsRepository.findById("storefront.config"))
                .thenReturn(setting("storefront.config", "{\"clientName\": \"Config Name\", \"currency\": \"ZAR\"}"));
        when(settingsRepository.findById("storefront.contact")).thenReturn(null);

        StoreEmailDetails details = resolver.resolve();

        assertEquals("Branding Name", details.name());
    }

    @Test
    @DisplayName("resolveStoreName() returns storefront.branding name when configured")
    void resolveStoreNameUsesBranding()
    {
        when(settingsRepository.findById("storefront.branding"))
                .thenReturn(setting("storefront.branding", "{\"name\": \"Branding Name\"}"));

        assertEquals("Branding Name", resolver.resolveStoreName());
    }

    @Test
    @DisplayName("resolveStoreName() falls back to storefront.config clientName when branding is missing")
    void resolveStoreNameFallsBackToConfigWhenBrandingMissing()
    {
        when(settingsRepository.findById("storefront.branding")).thenReturn(null);
        when(settingsRepository.findById("storefront.config"))
                .thenReturn(setting("storefront.config", "{\"clientName\": \"Config Name\"}"));

        assertEquals("Config Name", resolver.resolveStoreName());
    }

    @Test
    @DisplayName("resolveStoreName() falls back to storefront.config clientName when branding name is blank")
    void resolveStoreNameFallsBackToConfigWhenBrandingNameBlank()
    {
        when(settingsRepository.findById("storefront.branding"))
                .thenReturn(setting("storefront.branding", "{\"name\": \"   \"}"));
        when(settingsRepository.findById("storefront.config"))
                .thenReturn(setting("storefront.config", "{\"clientName\": \"Config Name\"}"));

        assertEquals("Config Name", resolver.resolveStoreName());
    }

    @Test
    @DisplayName("resolveStoreName() falls back to storefront.config clientName when branding name node is null")
    void resolveStoreNameFallsBackToConfigWhenBrandingNameNodeNull()
    {
        when(settingsRepository.findById("storefront.branding"))
                .thenReturn(setting("storefront.branding", "{\"name\": null}"));
        when(settingsRepository.findById("storefront.config"))
                .thenReturn(setting("storefront.config", "{\"clientName\": \"Config Name\"}"));

        assertEquals("Config Name", resolver.resolveStoreName());
    }

    @Test
    @DisplayName("resolveStoreName() falls back to storefront.config clientName when branding JSON is malformed")
    void resolveStoreNameFallsBackToConfigWhenBrandingMalformed()
    {
        when(settingsRepository.findById("storefront.branding"))
                .thenReturn(setting("storefront.branding", "not valid json {{{"));
        when(settingsRepository.findById("storefront.config"))
                .thenReturn(setting("storefront.config", "{\"clientName\": \"Config Name\"}"));

        assertEquals("Config Name", resolver.resolveStoreName());
    }

    @Test
    @DisplayName("resolveStoreName() is null when neither storefront.branding nor storefront.config names the store")
    void resolveStoreNameNullWhenNeitherConfigured()
    {
        when(settingsRepository.findById("storefront.branding")).thenReturn(null);
        when(settingsRepository.findById("storefront.config")).thenReturn(null);

        assertNull(resolver.resolveStoreName());
    }
}
