package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.jboss.logging.Logger;

/**
 * Observes {@link QuoteRequestSubmittedEvent} after the submission transaction commits
 * and sends a notification email to the operator (enquiry inbox).
 * <p>
 * Follows the fire-and-log pattern of {@link WholesaleMailNotifier}: mail delivery is
 * reactive and never blocks the caller. A delivery failure or missing recipient is
 * logged with the {@code [QuoteRequest]} marker but never propagated — the request
 * is already persisted.
 */
@ApplicationScoped
public class QuoteRequestMailer
{
    private static final Logger LOG = Logger.getLogger(QuoteRequestMailer.class);

    @Inject
    MailTemplate quote_request;

    @Inject
    MailTemplate quote_ready;

    @Inject
    EnquiryRecipientResolver enquiryRecipientResolver;

    @Inject
    StoreEmailDetailsResolver storeEmailDetailsResolver;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

    /**
     * Fires after the quote request submission transaction commits successfully.
     * Resolves the operator recipient from store settings and sends the notification.
     * Missing recipient or delivery failure are logged and skipped — no exception is thrown.
     */
    public void onSubmitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) QuoteRequestSubmittedEvent event)
    {
        QuoteRequestDetailsDto request = event.request();

        // Resolve recipient from storefront.contact → enquiryEmail
        String recipient = enquiryRecipientResolver.find().orElse(null);
        if (recipient == null) {
            LOG.warnf("[QuoteRequest] no recipient configured — notification skipped (request %s)", event.requestId());
            return;
        }

        try {
            var mail = quote_request.to(recipient)
                    .from(mailerFrom)
                    .subject("New quote request from " + request.getName())
                    .data("name", request.getName())
                    .data("email", request.getEmail())
                    .data("phone", request.getPhone())
                    .data("company", request.getCompany())
                    .data("message", request.getMessage())
                    .data("items", request.getItems());

            // Set replyTo to the submitter's email
            if (request.getEmail() != null && !request.getEmail().isBlank()) {
                mail = mail.replyTo(request.getEmail());
            }

            mail.send()
                    .subscribe().with(
                            success -> LOG.infof("[QuoteRequest] notification delivered for request %s to %s",
                                    event.requestId(), recipient),
                            failure -> LOG.errorf(failure, "[QuoteRequest] notification delivery failed for request %s to %s",
                                    event.requestId(), recipient)
                    );
        } catch (Exception e) {
            LOG.errorf(e, "[QuoteRequest] failed to compose notification for request %s", event.requestId());
        }
    }

    /**
     * Fires after generateAndSendQuote's transaction commits successfully and emails the
     * customer their priced quote. Same fire-and-log delivery pattern as {@link #onSubmitted}.
     */
    public void onQuoteSent(@Observes(during = TransactionPhase.AFTER_SUCCESS) QuoteGeneratedEvent event)
    {
        QuoteRequestDetailsDto request = event.request();
        StoreEmailDetails store = storeEmailDetailsResolver.resolve();

        if (request.getEmail() == null || request.getEmail().isBlank()) {
            LOG.warnf("[QuoteRequest] no recipient email on request %s — quote email skipped", event.requestId());
            return;
        }

        try {
            var mail = quoteInstance(quote_ready, request, store)
                    .to(request.getEmail())
                    .from(mailerFrom)
                    .subject("Your Quote" + (store.name() != null ? " from " + store.name() : ""));

            if (store.enquiryEmail() != null && !store.enquiryEmail().isBlank()) {
                mail = mail.replyTo(store.enquiryEmail());
            }

            mail.send()
                    .subscribe().with(
                            success -> LOG.infof("[QuoteRequest] quote email delivered for request %s to %s",
                                    event.requestId(), request.getEmail()),
                            failure -> LOG.errorf(failure, "[QuoteRequest] quote email delivery failed for request %s to %s",
                                    event.requestId(), request.getEmail())
                    );
        } catch (Exception e) {
            LOG.errorf(e, "[QuoteRequest] failed to compose quote email for request %s", event.requestId());
        }
    }

    /**
     * Renders the quote email exactly as {@link #onQuoteSent} would send it, without sending
     * it — same template, same data-building code, so a preview can never drift from what
     * generateAndSendQuote actually delivers.
     */
    public String renderQuotePreview(QuoteRequestDetailsDto request)
    {
        StoreEmailDetails store = storeEmailDetailsResolver.resolve();
        return quoteInstance(quote_ready, request, store).templateInstance().render();
    }

    private MailTemplate.MailTemplateInstance quoteInstance(MailTemplate template, QuoteRequestDetailsDto request, StoreEmailDetails store)
    {
        return template.instance()
                .data("name", request.getName())
                .data("items", request.getItems())
                .data("quotedAmount", request.getQuotedAmount())
                .data("quotedNotes", request.getQuotedNotes())
                .data("store", store);
    }

}
