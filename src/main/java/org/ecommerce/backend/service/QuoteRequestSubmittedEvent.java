package org.ecommerce.backend.service;

import org.ecommerce.common.dto.QuoteRequestDetailsDto;

import java.util.UUID;

/**
 * Immutable event fired when a new quote request is submitted and persisted.
 * Carries the full mapped request snapshot so the mail observer can render
 * every submitted field without re-reading a detached entity.
 */
public record QuoteRequestSubmittedEvent(
        UUID requestId,
        QuoteRequestDetailsDto request
) {}
