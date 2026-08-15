package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EnquiryRecipientResolver}, the single owner of the
 * {@code storefront.contact} → {@code enquiryEmail} lookup.
 * <p>
 * Both failure policies are pinned here because callers depend on the difference:
 * {@code require()} must fail loudly for request-scoped sends, {@code find()} must
 * degrade quietly for post-commit notifications.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnquiryRecipientResolver — storefront.contact.enquiryEmail lookup")
class EnquiryRecipientResolverTest
{
    @InjectMocks
    private EnquiryRecipientResolver resolver;

    @Mock
    private SettingsRepository settingsRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private StoreSettingsEntity settingWith(String jsonValue)
    {
        StoreSettingsEntity entity = new StoreSettingsEntity();
        entity.setKey("storefront.contact");
        entity.setValue(jsonValue);
        return entity;
    }

    @Nested
    @DisplayName("require — throws when the mailbox is not configured")
    class RequireTests
    {
        @Test
        @DisplayName("resolves enquiryEmail when present in the storefront.contact JSON")
        void resolvesFromConfig()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"info@store.co.za\",\"emails\":[\"accounts@store.co.za\"]}"));

            assertEquals("info@store.co.za", resolver.require());
        }

        @Test
        @DisplayName("recipient is never taken from the emails array — only enquiryEmail")
        void neverFallsBackToEmailsList()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"emails\":[\"info@store.co.za\",\"support@store.co.za\"]}"));

            assertThrows(RecipientNotConfiguredException.class, () -> resolver.require());
        }

        @Test
        @DisplayName("throws RecipientNotConfiguredException when setting row is null")
        void throwsWhenSettingRowMissing()
        {
            when(settingsRepository.findById("storefront.contact")).thenReturn(null);

            RecipientNotConfiguredException ex = assertThrows(
                    RecipientNotConfiguredException.class,
                    () -> resolver.require()
            );
            assertTrue(ex.getMessage().contains("missing or empty"));
        }

        @Test
        @DisplayName("throws RecipientNotConfiguredException when setting value is blank")
        void throwsWhenSettingValueBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("   "));

            assertThrows(RecipientNotConfiguredException.class, () -> resolver.require());
        }

        @Test
        @DisplayName("throws RecipientNotConfiguredException when enquiryEmail field is blank")
        void throwsWhenEnquiryEmailBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"   \"}"));

            RecipientNotConfiguredException ex = assertThrows(
                    RecipientNotConfiguredException.class,
                    () -> resolver.require()
            );
            assertTrue(ex.getMessage().contains("absent or blank"));
        }

        @Test
        @DisplayName("throws RecipientNotConfiguredException when enquiryEmail field is null in JSON")
        void throwsWhenEnquiryEmailNull()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":null}"));

            assertThrows(RecipientNotConfiguredException.class, () -> resolver.require());
        }

        @Test
        @DisplayName("throws RecipientNotConfiguredException when JSON is malformed")
        void throwsWhenJsonMalformed()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("not valid json {{{"));

            RecipientNotConfiguredException ex = assertThrows(
                    RecipientNotConfiguredException.class,
                    () -> resolver.require()
            );
            assertTrue(ex.getMessage().contains("Failed to parse"));
        }
    }

    @Nested
    @DisplayName("find — returns empty instead of throwing")
    class FindTests
    {
        @Test
        @DisplayName("returns the recipient when configured")
        void resolvesFromConfig()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"quotes@store.co.za\"}"));

            assertEquals("quotes@store.co.za", resolver.find().orElseThrow());
        }

        @Test
        @DisplayName("returns empty when setting row is missing")
        void emptyWhenSettingRowMissing()
        {
            when(settingsRepository.findById("storefront.contact")).thenReturn(null);

            assertTrue(resolver.find().isEmpty());
        }

        @Test
        @DisplayName("returns empty when setting value is blank")
        void emptyWhenSettingValueBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("   "));

            assertTrue(resolver.find().isEmpty());
        }

        @Test
        @DisplayName("returns empty when enquiryEmail field is blank")
        void emptyWhenEnquiryEmailBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"\"}"));

            assertTrue(resolver.find().isEmpty());
        }

        @Test
        @DisplayName("returns empty when enquiryEmail field is null in JSON")
        void emptyWhenEnquiryEmailNull()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":null}"));

            assertTrue(resolver.find().isEmpty());
        }

        @Test
        @DisplayName("returns empty when enquiryEmail field is absent")
        void emptyWhenEnquiryEmailAbsent()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"emails\":[\"info@store.co.za\"]}"));

            assertTrue(resolver.find().isEmpty());
        }

        @Test
        @DisplayName("returns empty when JSON is malformed")
        void emptyWhenJsonMalformed()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("broken{json"));

            assertTrue(resolver.find().isEmpty());
        }
    }
}
