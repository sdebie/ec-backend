package org.ecommerce.backend.service;

import org.ecommerce.common.enums.WholesaleApplicationStatusEn;

import java.util.UUID;

/**
 * Immutable event fired when a wholesale application decision (APPROVED or REJECTED)
 * commits. Carries all data the mail notifier needs so the observer never re-reads
 * a detached entity.
 * <p>
 * {@code newAccountCreated} is meaningful only when {@code decision} is APPROVED: true
 * when approval created a brand-new account (no working password yet — the email must
 * direct the customer to Forgot Password), false when an existing account was upgraded
 * to wholesale in place (existing password still works). Always false on rejection.
 */
public record WholesaleDecisionEvent(UUID applicationId, WholesaleApplicationStatusEn decision, String recipientEmail,
                                     String firstName, String companyName, String rejectionReason,
                                     boolean newAccountCreated)
{
}
