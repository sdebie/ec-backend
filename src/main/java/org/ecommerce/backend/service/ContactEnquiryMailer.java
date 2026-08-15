package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;
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

    @Inject
    MailTemplate contact_enquiry;

    @Inject
    EnquiryRecipientResolver enquiryRecipientResolver;

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
        String enquiryEmail = enquiryRecipientResolver.require();

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

}
