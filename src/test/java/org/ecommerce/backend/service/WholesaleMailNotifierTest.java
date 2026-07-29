package org.ecommerce.backend.service;

// Feature: wholesale-application-review-workflow, Property 4: No hardcoded sender or client identity
// Validates: Requirements 4.3, 4.5, 5.1, 5.3

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MailTemplate;
import io.smallrye.mutiny.Uni;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WholesaleMailNotifier}: store name resolution from
 * {@code storefront.branding}, from-address guards, and recipient guards.
 * <p>
 * The real notifier logic runs; only the transport (MailTemplate fluent send chain)
 * is mocked so we can verify parameters without sending actual SMTP traffic.
 * <p>
 * Feature: wholesale-application-review-workflow, Property 4: No hardcoded sender or client identity
 * Validates: Requirements 4.3, 4.5, 5.1, 5.3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WholesaleMailNotifier — store name resolution, from-address & recipient guards")
class WholesaleMailNotifierTest
{
    @InjectMocks
    private WholesaleMailNotifier notifier;

    @Mock
    private SettingsRepository settingsRepository;

    @Mock
    private MailTemplate wholesale_status;

    @Mock
    private MailTemplate wholesale_application_received;

    @Mock
    private MailTemplate wholesale_registration;

    @Mock
    private ContactEnquiryMailer contactEnquiryMailer;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONFIGURED_FROM = "no-reply@store.co.za";

    @BeforeEach
    void setUp() throws Exception
    {
        // Reflectively set the @ConfigProperty field since @InjectMocks won't inject it
        var fromField = WholesaleMailNotifier.class.getDeclaredField("mailerFrom");
        fromField.setAccessible(true);
        fromField.set(notifier, CONFIGURED_FROM);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private StoreSettingsEntity brandingSetting(String jsonValue)
    {
        StoreSettingsEntity entity = new StoreSettingsEntity();
        entity.setKey("storefront.branding");
        entity.setValue(jsonValue);
        return entity;
    }

    private WholesaleDecisionEvent approvalEvent(String recipientEmail)
    {
        return new WholesaleDecisionEvent(
                UUID.randomUUID(),
                WholesaleApplicationStatusEn.APPROVED,
                recipientEmail,
                "Jane",
                "ACME Corp",
                null
        );
    }

    private WholesaleDecisionEvent rejectionEvent(String recipientEmail)
    {
        return new WholesaleDecisionEvent(
                UUID.randomUUID(),
                WholesaleApplicationStatusEn.REJECTED,
                recipientEmail,
                "Bob",
                "Beta Inc",
                "Insufficient documentation"
        );
    }

    /**
     * Stubs the MailTemplate fluent API chain so .to().from().subject().data()...send()
     * returns a completed Uni, allowing send() to complete without error.
     */
    private MailTemplate.MailTemplateInstance stubMailTemplateChain()
    {
        MailTemplate.MailTemplateInstance instance = mock(MailTemplate.MailTemplateInstance.class);
        when(wholesale_status.to(anyString())).thenReturn(instance);
        when(instance.from(anyString())).thenReturn(instance);
        when(instance.subject(anyString())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(instance.send()).thenReturn(Uni.createFrom().voidItem());
        return instance;
    }

    // ── Store name resolution (Property 4) ──────────────────────────────────

    @Nested
    @DisplayName("store name resolution from storefront.branding")
    class StoreNameResolutionTests
    {

        @Test
        @DisplayName("resolves store name from storefront.branding JSON name field")
        void resolvesStoreNameFromBranding()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            // storeName template data should be "My Store"
            verify(instance).data("storeName", "My Store");
        }

        @Test
        @DisplayName("missing branding setting → sends with bare from-address as store name + logs warning")
        void missingBrandingSendsWithBareAddress()
        {
            when(settingsRepository.findById("storefront.branding")).thenReturn(null);
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            // Should still send, using the mailerFrom as the store name fallback
            verify(instance).data("storeName", CONFIGURED_FROM);
            verify(instance).send();
        }

        @Test
        @DisplayName("branding with blank name → sends with bare from-address as store name + logs warning")
        void blankBrandingNameSendsWithBareAddress()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"   \"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            verify(instance).data("storeName", CONFIGURED_FROM);
            verify(instance).send();
        }

        @Test
        @DisplayName("branding with null name node → sends with bare from-address as store name")
        void nullNameNodeSendsWithBareAddress()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": null}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            verify(instance).data("storeName", CONFIGURED_FROM);
            verify(instance).send();
        }

        @Test
        @DisplayName("malformed branding JSON → sends with bare from-address as store name")
        void malformedJsonSendsWithBareAddress()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("not valid json {{{"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            verify(instance).data("storeName", CONFIGURED_FROM);
            verify(instance).send();
        }
    }

    // ── From-address guard (Property 4) ─────────────────────────────────────

    @Nested
    @DisplayName("blank from-address → sends nothing + logs error")
    class BlankFromAddressTests
    {

        @Test
        @DisplayName("blank from-address skips send entirely")
        void blankFromSkipsSend() throws Exception
        {
            // Set mailerFrom to blank
            var fromField = WholesaleMailNotifier.class.getDeclaredField("mailerFrom");
            fromField.setAccessible(true);
            fromField.set(notifier, "   ");

            notifier.onDecision(approvalEvent("applicant@test.com"));

            // MailTemplate should never be touched
            verifyNoInteractions(wholesale_status);
        }

        @Test
        @DisplayName("null from-address skips send entirely")
        void nullFromSkipsSend() throws Exception
        {
            var fromField = WholesaleMailNotifier.class.getDeclaredField("mailerFrom");
            fromField.setAccessible(true);
            fromField.set(notifier, null);

            notifier.onDecision(approvalEvent("applicant@test.com"));

            verifyNoInteractions(wholesale_status);
        }
    }

    // ── Recipient guard (Property 5) ────────────────────────────────────────

    @Nested
    @DisplayName("missing/blank recipient → skip send + log")
    class RecipientGuardTests
    {

        @Test
        @DisplayName("null recipient skips send")
        void nullRecipientSkipsSend()
        {
            notifier.onDecision(approvalEvent(null));

            verifyNoInteractions(wholesale_status);
            verifyNoInteractions(settingsRepository);
        }

        @Test
        @DisplayName("blank recipient skips send")
        void blankRecipientSkipsSend()
        {
            notifier.onDecision(approvalEvent("   "));

            verifyNoInteractions(wholesale_status);
            verifyNoInteractions(settingsRepository);
        }
    }

    // ── Admin notification on submission ────────────────────────────────────

    @Nested
    @DisplayName("onSubmitted — admin notification + applicant confirmation")
    class SubmittedNotificationTests
    {

        private WholesaleCustomerDto submittedDto()
        {
            WholesaleCustomerDto dto = new WholesaleCustomerDto();
            dto.setApplicantEmail("applicant@test.com");
            dto.setFirstName("Jane");
            dto.setLastName("Doe");
            dto.setCompanyName("ACME Corp");
            dto.setTradingName("ACME Trading");
            dto.setPhone("0821234567");
            dto.setVatNumber("VAT123");
            return dto;
        }

        private WholesaleApplicationSubmittedEvent submittedEvent(WholesaleCustomerDto dto)
        {
            return new WholesaleApplicationSubmittedEvent(UUID.randomUUID(), dto);
        }

        /**
         * Stubs a MailTemplate fluent chain. replyTo is stubbed leniently — only the
         * admin notification uses it, and only when an applicant email is present.
         */
        private MailTemplate.MailTemplateInstance stubChain(MailTemplate template)
        {
            MailTemplate.MailTemplateInstance instance = mock(MailTemplate.MailTemplateInstance.class);
            when(template.to(anyString())).thenReturn(instance);
            when(instance.from(anyString())).thenReturn(instance);
            when(instance.subject(anyString())).thenReturn(instance);
            when(instance.data(anyString(), any())).thenReturn(instance);
            lenient().when(instance.replyTo(anyString())).thenReturn(instance);
            when(instance.send()).thenReturn(Uni.createFrom().voidItem());
            return instance;
        }

        @Test
        @DisplayName("admin notification delivered to the resolved enquiryEmail with full snapshot + replyTo")
        void adminNotificationDelivered()
        {
            when(contactEnquiryMailer.resolveRecipient()).thenReturn("enquiries@store.co.za");
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            MailTemplate.MailTemplateInstance admin = stubChain(wholesale_application_received);
            stubChain(wholesale_registration);
            WholesaleCustomerDto dto = submittedDto();

            notifier.onSubmitted(submittedEvent(dto));

            verify(wholesale_application_received).to("enquiries@store.co.za");
            verify(admin).from(CONFIGURED_FROM);
            verify(admin).subject("New wholesale application from ACME Corp");
            verify(admin).replyTo("applicant@test.com");
            verify(admin).data("app", dto);
            verify(admin).send();
        }

        @Test
        @DisplayName("applicant confirmation delivered to applicantEmail with store name + full snapshot")
        void applicantConfirmationDelivered()
        {
            when(contactEnquiryMailer.resolveRecipient()).thenReturn("enquiries@store.co.za");
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            stubChain(wholesale_application_received);
            MailTemplate.MailTemplateInstance applicant = stubChain(wholesale_registration);
            WholesaleCustomerDto dto = submittedDto();

            notifier.onSubmitted(submittedEvent(dto));

            verify(wholesale_registration).to("applicant@test.com");
            verify(applicant).from(CONFIGURED_FROM);
            verify(applicant).subject("Wholesale Application Received");
            verify(applicant).data("storeName", "My Store");
            verify(applicant).data("app", dto);
            verify(applicant).send();
        }

        @Test
        @DisplayName("enquiryEmail not configured → admin skipped, applicant confirmation still delivered")
        void recipientNotConfiguredStillSendsApplicantConfirmation()
        {
            when(contactEnquiryMailer.resolveRecipient())
                    .thenThrow(new RecipientNotConfiguredException("enquiryEmail is absent or blank in storefront.contact"));
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            MailTemplate.MailTemplateInstance applicant = stubChain(wholesale_registration);

            notifier.onSubmitted(submittedEvent(submittedDto()));

            verifyNoInteractions(wholesale_application_received);
            verify(wholesale_registration).to("applicant@test.com");
            verify(applicant).send();
        }

        @Test
        @DisplayName("blank applicantEmail → confirmation skipped, admin notified without replyTo")
        void blankApplicantEmailSkipsConfirmation()
        {
            when(contactEnquiryMailer.resolveRecipient()).thenReturn("enquiries@store.co.za");
            MailTemplate.MailTemplateInstance admin = stubChain(wholesale_application_received);
            WholesaleCustomerDto dto = submittedDto();
            dto.setApplicantEmail(null);

            notifier.onSubmitted(submittedEvent(dto));

            verify(admin).send();
            verify(admin, never()).replyTo(anyString());
            verifyNoInteractions(wholesale_registration);
            verifyNoInteractions(settingsRepository);
        }

        @Test
        @DisplayName("blank from-address → skips both emails without resolving anything")
        void blankFromSkipsBothEmails() throws Exception
        {
            var fromField = WholesaleMailNotifier.class.getDeclaredField("mailerFrom");
            fromField.setAccessible(true);
            fromField.set(notifier, "   ");

            notifier.onSubmitted(submittedEvent(submittedDto()));

            verifyNoInteractions(contactEnquiryMailer);
            verifyNoInteractions(wholesale_application_received);
            verifyNoInteractions(wholesale_registration);
        }
    }

    // ── Mail composition correctness ────────────────────────────────────────

    @Nested
    @DisplayName("mail composition passes correct template data")
    class MailCompositionTests
    {

        @Test
        @DisplayName("approval event sets approved=true and no rejectionReason")
        void approvalEventData()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(approvalEvent("applicant@test.com"));

            verify(wholesale_status).to("applicant@test.com");
            verify(instance).from(CONFIGURED_FROM);
            verify(instance).data("firstName", "Jane");
            verify(instance).data("storeName", "My Store");
            verify(instance).data("approved", true);
            verify(instance).data("rejectionReason", null);
            verify(instance).send();
        }

        @Test
        @DisplayName("rejection event sets approved=false and includes rejectionReason")
        void rejectionEventData()
        {
            when(settingsRepository.findById("storefront.branding"))
                    .thenReturn(brandingSetting("{\"name\": \"My Store\"}"));
            MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

            notifier.onDecision(rejectionEvent("bob@test.com"));

            verify(wholesale_status).to("bob@test.com");
            verify(instance).from(CONFIGURED_FROM);
            verify(instance).data("firstName", "Bob");
            verify(instance).data("storeName", "My Store");
            verify(instance).data("approved", false);
            verify(instance).data("rejectionReason", "Insufficient documentation");
            verify(instance).send();
        }
    }
}
