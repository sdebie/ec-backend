package org.ecommerce.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import io.smallrye.mutiny.Uni;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;
import org.ecommerce.common.entity.StoreSettingsEntity;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContactEnquiryMailer}: recipient resolution from
 * {@code storefront.contact.enquiryEmail} and mail composition with correct
 * {@code to}/{@code from}/{@code replyTo}.
 * <p>
 * The real service logic runs; only the transport (MailTemplate fluent send chain)
 * is mocked so we can verify the parameters passed to the mailer without sending
 * actual SMTP traffic.
 * <p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContactEnquiryMailer — recipient resolution & mail composition")
class ContactEnquiryMailerTest
{
    @InjectMocks
    private ContactEnquiryMailer mailer;

    @Mock
    private SettingsRepository settingsRepository;

    @Mock
    private MailTemplate contact_enquiry;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONFIGURED_FROM = "no-reply@store.co.za";

    @BeforeEach
    void setUp() throws Exception
    {
        // Reflectively set the @ConfigProperty field since @InjectMocks won't inject it
        var fromField = ContactEnquiryMailer.class.getDeclaredField("mailerFrom");
        fromField.setAccessible(true);
        fromField.set(mailer, CONFIGURED_FROM);

        // Real resolver over the mocked settings repository — recipient resolution
        // stays end-to-end here, so a wiring break fails these tests too.
        EnquiryRecipientResolver resolver = new EnquiryRecipientResolver();
        resolver.settingsRepository = settingsRepository;
        resolver.objectMapper = objectMapper;
        mailer.enquiryRecipientResolver = resolver;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private StoreSettingsEntity settingWith(String jsonValue)
    {
        StoreSettingsEntity entity = new StoreSettingsEntity();
        entity.setKey("storefront.contact");
        entity.setValue(jsonValue);
        return entity;
    }

    private ContactEnquiryRequestDto validDto()
    {
        return new ContactEnquiryRequestDto(
                "Jane Doe",
                "jane@visitor.com",
                "0821234567",
                "ACME Corp",
                "I'd like to enquire about your services.",
                null
        );
    }

    /**
     * Stubs the MailTemplate fluent API chain so .to().from().replyTo().subject().data()...send()
     * returns a completed Uni, allowing send() to complete without error.
     */
    private MailTemplate.MailTemplateInstance stubMailTemplateChain()
    {
        MailTemplate.MailTemplateInstance instance = mock(MailTemplate.MailTemplateInstance.class);
        when(contact_enquiry.to(anyString())).thenReturn(instance);
        when(instance.from(anyString())).thenReturn(instance);
        when(instance.replyTo(anyString())).thenReturn(instance);
        when(instance.subject(anyString())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(instance.send()).thenReturn(Uni.createFrom().voidItem());
        return instance;
    }


    // ── Mail composition tests (Req 2.2, 2.3) ──────────────────────────────

    @Nested
    @DisplayName("send — mail built with correct to/from/replyTo")
    class MailCompositionTests
    {

        @Test
        @DisplayName("to is set to the resolved enquiryEmail, not the submitter's email")
        void toIsEnquiryEmail()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(contact_enquiry).to("enquiries@store.co.za");
        }

        @Test
        @DisplayName("from is set to quarkus.mailer.from config value, not submitter")
        void fromIsMailerConfig()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(instance).from(CONFIGURED_FROM);
        }

        @Test
        @DisplayName("replyTo is set to the submitter's email for direct staff replies")
        void replyToIsSubmitterEmail()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(instance).replyTo("jane@visitor.com");
        }

        @Test
        @DisplayName("subject includes the submitter's name")
        void subjectIncludesName()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(instance).subject("New enquiry from Jane Doe");
        }

        @Test
        @DisplayName("template data includes all DTO fields")
        void templateDataIncludesAllFields()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(instance).data("name", "Jane Doe");
            verify(instance).data("email", "jane@visitor.com");
            verify(instance).data("phone", "0821234567");
            verify(instance).data("company", "ACME Corp");
            verify(instance).data("message", "I'd like to enquire about your services.");
        }

        @Test
        @DisplayName("send is invoked on the template instance (real mailer path triggered)")
        void sendIsInvoked()
        {
            when(settingsRepository.findById("storefront.contact"))
                    .thenReturn(settingWith("{\"enquiryEmail\":\"enquiries@store.co.za\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            mailer.send(validDto());

            verify(instance).send();
        }

        @Test
        @DisplayName("send throws RecipientNotConfiguredException before reaching MailTemplate when no recipient")
        void noRecipientPreventsMailSend()
        {
            when(settingsRepository.findById("storefront.contact")).thenReturn(null);

            assertThrows(RecipientNotConfiguredException.class, () -> mailer.send(validDto()));

            // MailTemplate should never be touched when recipient resolution fails
            verifyNoInteractions(contact_enquiry);
        }
    }
}
