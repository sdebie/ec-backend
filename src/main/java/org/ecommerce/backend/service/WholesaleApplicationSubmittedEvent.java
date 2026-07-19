package org.ecommerce.backend.service;

import java.util.UUID;

/**
 * Immutable event fired when a new wholesale application is submitted (PENDING).
 * Carries all data the admin notification mail needs so the observer never re-reads
 * a detached entity.
 */
public record WholesaleApplicationSubmittedEvent(
        UUID applicationId,
        String applicantEmail,
        String firstName,
        String lastName, // nullable
        String companyName,
        String tradingName, // nullable
        String phone, // nullable
        String vatNumber // nullable
) {}
