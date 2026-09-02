package org.ecommerce.backend.service;

import org.ecommerce.common.dto.QuoteRequestDetailsDto;

import java.util.UUID;

/**
 * Immutable event fired when staff generate and send a priced quote. Carries the full
 * mapped request snapshot — including quotedAmount/quotedNotes/quotedByName and each item's
 * unitPrice/lineTotal — so the mail observer can render the quote email without re-reading a
 * detached entity.
 */
public record QuoteGeneratedEvent(UUID requestId, QuoteRequestDetailsDto request)
{
}
