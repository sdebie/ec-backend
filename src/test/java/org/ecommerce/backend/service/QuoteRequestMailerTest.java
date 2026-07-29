package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import io.smallrye.mutiny.Uni;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.ecommerce.common.repository.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuoteRequestMailer}: recipient resolution from
 * {@code storefront.contact.enquiryEmail} and fire-and-log behaviour
 * (missing recipient = log + skip, no exception).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteRequestMailer — recipient resolution & fire-and-log behaviour")
class QuoteRequestMailerTest
{
    @InjectMocks
    private QuoteRequestMailer mailer;

    @Mock
    private SettingsRepository settingsRepository;

    @Mock
    private MailTemplate quote_request;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONFIGURED_FROM = "no-reply@store.co.za";

    @BeforeEach
    void setUp() throws Exception
    {
        var fromField = QuoteRequestMailer.class.getDeclaredField("mailerFrom");
        fromField.setAccessible(true);
        fromField.set(mailer, CONFIGURED_FROM);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private StoreSettingsEntity settingWith(String jsonValue)
    {
        StoreSettingsEntity entity = new StoreSettingsEntity();
        entity.setKey("storefront.contact");
        entity.setValue(jsonValue);
        return entity;
    }

    private QuoteRequestSubmittedEvent sampleEvent()
    {
        QuoteRequestItemDto item = new QuoteRequestItemDto();
        item.setProductNameSnapshot("Widget Pro");
        item.setVariantSkuSnapshot("WGT-001");
        item.setQuantity(5);

        QuoteRequestDetailsDto dto = new QuoteRequestDetailsDto();
        dto.setId(UUID.randomUUID());
        dto.setName("John Smith");
        dto.setEmail("john@company.co.za");
        dto.setPhone("0821234567");
        dto.setCompany("Smith Corp");
        dto.setMessage("Need bulk pricing please.");
        dto.setCreatedAt(Instant.now());
        dto.setStatus(QuoteRequestStatusEn.NEW);
        dto.setItems(List.of(item));

        return new QuoteRequestSubmittedEvent(dto.getId(), dto);
    }

    private MailTemplate.MailTemplateInstance stubMailTemplateChain()
    {
        MailTemplate.MailTemplateInstance instance = mock(MailTemplate.MailTemplateInstance.class);
        when(quote_request.to(anyString())).thenReturn(instance);
        when(instance.from(anyString())).thenReturn(instance);
        when(instance.replyTo(anyString())).thenReturn(instance);
        when(instance.subject(anyString())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(instance.send()).thenReturn(Uni.createFrom().voidItem());
        return instance;
    }

    // ── Recipient resolution tests ──────────────────────────────────────────

    @Nested
    @DisplayName("resolveRecipient — reads enquiryEmail from storefront.contact config")
    class RecipientResolutionTests
    {

        @Test
        @DisplayName("resolves enquiryEmail when present in the storefront.contact JSON")
        void resolvesFromConfig()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"quotes@store.co.za\"}"));

            String recipient = mailer.resolveRecipient();

            assertEquals("quotes@store.co.za", recipient);
        }

        @Test
        @DisplayName("returns null when setting row is missing")
        void returnsNullWhenSettingMissing()
        {
            when(settingsRepository.findById("storefront.contact")).thenReturn(null);

            assertNull(mailer.resolveRecipient());
        }

        @Test
        @DisplayName("returns null when setting value is blank")
        void returnsNullWhenValueBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("   "));

            assertNull(mailer.resolveRecipient());
        }

        @Test
        @DisplayName("returns null when enquiryEmail field is blank")
        void returnsNullWhenEnquiryEmailBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"   \"}"));

            assertNull(mailer.resolveRecipient());
        }

        @Test
        @DisplayName("returns null when enquiryEmail field is null in JSON")
        void returnsNullWhenEnquiryEmailNull()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":null}"));

            assertNull(mailer.resolveRecipient());
        }

        @Test
        @DisplayName("returns null when enquiryEmail field is absent")
        void returnsNullWhenFieldAbsent()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"phone\":\"0211234567\"}"));

            assertNull(mailer.resolveRecipient());
        }

        @Test
        @DisplayName("returns null when JSON is malformed")
        void returnsNullWhenJsonMalformed()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("not valid json {{{"));

            assertNull(mailer.resolveRecipient());
        }
    }

    // ── onSubmitted behaviour tests ─────────────────────────────────────────

    @Nested
    @DisplayName("onSubmitted — fire-and-log, missing-recipient skip")
    class OnSubmittedTests
    {

        @Test
        @DisplayName("sends mail to resolved recipient with correct to/from/replyTo/subject")
        void sendsMailWhenRecipientConfigured()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"quotes@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.onSubmitted(sampleEvent());

            verify(quote_request).to("quotes@store.co.za");
            verify(instance).from(CONFIGURED_FROM);
            verify(instance).replyTo("john@company.co.za");
            verify(instance).subject("New quote request from John Smith");
            verify(instance).send();
        }

        @Test
        @DisplayName("passes contact fields and items as template data")
        void passesTemplateData()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"quotes@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            QuoteRequestSubmittedEvent event = sampleEvent();
            mailer.onSubmitted(event);

            verify(instance).data("name", "John Smith");
            verify(instance).data("email", "john@company.co.za");
            verify(instance).data("phone", "0821234567");
            verify(instance).data("company", "Smith Corp");
            verify(instance).data("message", "Need bulk pricing please.");
            verify(instance).data("items", event.request().getItems());
        }

        @Test
        @DisplayName("skips sending when no recipient configured — no exception thrown")
        void skipsWhenNoRecipient()
        {
            when(settingsRepository.findById("storefront.contact")).thenReturn(null);

            // Must not throw
            assertDoesNotThrow(() -> mailer.onSubmitted(sampleEvent()));

            // Mail template should never be touched
            verifyNoInteractions(quote_request);
        }

        @Test
        @DisplayName("skips sending when enquiryEmail is blank — no exception thrown")
        void skipsWhenEnquiryEmailBlank()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"\"}"));

            assertDoesNotThrow(() -> mailer.onSubmitted(sampleEvent()));
            verifyNoInteractions(quote_request);
        }

        @Test
        @DisplayName("skips sending when JSON is malformed — no exception thrown")
        void skipsWhenJsonMalformed()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("broken{json"));

            assertDoesNotThrow(() -> mailer.onSubmitted(sampleEvent()));
            verifyNoInteractions(quote_request);
        }
    }
}
