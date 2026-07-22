package org.ecommerce.backend.service;

import org.ecommerce.common.dto.WholesaleCustomerDto;

import java.util.UUID;

/**
 * Immutable event fired when a new wholesale application is submitted (PENDING).
 * Carries the full mapped application snapshot so the mail observers (admin
 * notification + applicant confirmation) can render every submitted field without
 * re-reading a detached entity.
 */
public record WholesaleApplicationSubmittedEvent(UUID applicationId, WholesaleCustomerDto application)
{
}
