package org.ecommerce.backend.service;

import org.ecommerce.common.enums.WholesaleApplicationStatusEn;

import java.util.UUID;

/**
 * Immutable event fired when a wholesale application decision (APPROVED or REJECTED)
 * commits. Carries all data the mail notifier needs so the observer never re-reads
 * a detached entity.
 */
public record WholesaleDecisionEvent(UUID applicationId, WholesaleApplicationStatusEn decision, String recipientEmail,
                                     String firstName, String companyName, String rejectionReason)
{
}
