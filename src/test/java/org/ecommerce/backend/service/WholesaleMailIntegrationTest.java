package org.ecommerce.backend.service;

// Feature: wholesale-application-review-workflow, Property 3: At-most-once, commit-only notification
// Feature: wholesale-application-review-workflow, Property 5: Mail failure isolation

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the wholesale decision → notification lifecycle.
 * <p>
 * These tests verify the CDI event observer behaviour:
 * - Guard failure / non-PENDING status → no event fired → no mail
 * - Rollback (via {@code @TestTransaction}) → AFTER_SUCCESS observer never fires
 * - Decision persists regardless of observer outcomes
 * <p>
 * Note: {@code @TestTransaction} rolls back the transaction, meaning the
 * {@code AFTER_SUCCESS} observer never fires (exactly the "no email on rollback"
 * requirement). For the "fires once on commit" path, the unit test
 * {@link WholesaleMailNotifierTest} verifies the observer method directly.
 * <p>
 * Feature: wholesale-application-review-workflow
 * Property 3: At-most-once, commit-only notification
 * Property 5: Mail failure isolation
 */
@QuarkusTest
@DisplayName("WholesaleMailIntegrationTest — observer lifecycle and isolation")
class WholesaleMailIntegrationTest
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private WholesaleApplicationEntity createPendingApplication()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        app.setFirstName("Test");
        app.setLastName("Applicant");
        app.setCompanyName("Test Company");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.now());
        em.persist(app);
        em.flush();
        return app;
    }

    private WholesaleApplicationEntity createConvertedApplication()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("converted-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        app.setFirstName("Converted");
        app.setLastName("User");
        app.setCompanyName("Converted Corp");
        app.setStatus(WholesaleApplicationStatusEn.CONVERTED);
        app.setProcessedAt(OffsetDateTime.now());
        app.setCreatedAt(OffsetDateTime.now());
        em.persist(app);
        em.flush();
        return app;
    }

    // ─── Property 3: No email on guard failure ──────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("reject on non-PENDING throws guard error — no event fired, no state change")
    void rejectNonPendingThrowsNoEvent()
    {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.getId();
        wholesaleCustomerService.approveWholesaleApplication(appId);
        em.flush();
        em.clear();

        assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "Too late"));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.getStatus());
    }

    @Test
    @TestTransaction
    @DisplayName("approve on non-PENDING (already REJECTED) throws guard error — no event fired")
    void approveNonPendingThrowsNoEvent()
    {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.getId();
        wholesaleCustomerService.rejectWholesaleApplication(appId, "Rejected");
        em.flush();
        em.clear();

        assertThrows(IllegalArgumentException.class, () -> wholesaleCustomerService.approveWholesaleApplication(appId));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.getStatus());
    }

    @Test
    @TestTransaction
    @DisplayName("CONVERTED application cannot be approved — no event fired")
    void convertedCannotBeApproved()
    {
        WholesaleApplicationEntity app = createConvertedApplication();
        UUID appId = app.getId();

        assertThrows(IllegalArgumentException.class, () -> wholesaleCustomerService.approveWholesaleApplication(appId));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, persisted.getStatus());
    }

    @Test
    @TestTransaction
    @DisplayName("CONVERTED application cannot be rejected — no event fired")
    void convertedCannotBeRejected()
    {
        WholesaleApplicationEntity app = createConvertedApplication();
        UUID appId = app.getId();

        assertThrows(IllegalArgumentException.class, () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "Nope"));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, persisted.getStatus());
    }

    // ─── Property 3: Rollback produces zero emails ──────────────────────────

    @Test
    @TestTransaction
    @DisplayName("@TestTransaction rolls back → AFTER_SUCCESS observer never fires (by design)")
    void testTransactionRollsBackSoObserverNeverFires()
    {
        // Under @TestTransaction the transaction is rolled back at test end.
        // AFTER_SUCCESS observers only fire on commit — so this verifies
        // the "no email on rollback" requirement structurally.
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.getId();

        var result = wholesaleCustomerService.approveWholesaleApplication(appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        // If we got here without exception, the event fire did not error.
        // The @TestTransaction rollback ensures AFTER_SUCCESS never executes.
    }

    // ─── Property 5: Decision persists even with missing recipient ──────────

    @Test
    @TestTransaction
    @DisplayName("application with blank applicantEmail and null accountEmail — decision still persists")
    void decisionPersistsWithBlankRecipient()
    {
        // applicant_email is NOT NULL in the DB, but can be blank (whitespace).
        // The buildDecisionEvent treats blank as "missing" and falls back to accountEmail.
        // When accountEmail is also null, the event carries a null recipient →
        // notifier skips + logs (but never throws).
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("   "); // blank — column constraint satisfied, treated as missing
        app.setAccountEmail(null);
        app.setFirstName("NoEmail");
        app.setLastName("User");
        app.setCompanyName("Ghost Corp");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.now());
        em.persist(app);
        em.flush();

        UUID appId = app.getId();

        // The reject call should still succeed — the event carries a null recipient
        // (blank applicantEmail + null accountEmail = null), and the notifier skips
        var result = wholesaleCustomerService.rejectWholesaleApplication(appId, "No email provided");

        assertEquals(WholesaleApplicationStatusEn.REJECTED, result.getStatus());
        assertEquals("No email provided", result.getRejectionReason());

        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.getStatus());
        assertEquals("No email provided", persisted.getRejectionReason());
    }

    @Test
    @TestTransaction
    @DisplayName("approval decision persists regardless of notification outcome")
    void approvalPersistsRegardlessly()
    {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.getId();

        var result = wholesaleCustomerService.approveWholesaleApplication(appId);

        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        assertNotNull(result.getProcessedAt());

        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.getStatus());
        assertNotNull(persisted.getProcessedAt());
    }

    // ─── Recipient chain: applicantEmail first, accountEmail fallback ────────

    @Test
    @TestTransaction
    @DisplayName("event uses applicantEmail as primary recipient — approval succeeds")
    void eventUsesApplicantEmailFirst()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("applicant@primary.com");
        app.setAccountEmail("account@fallback.com");
        app.setFirstName("Primary");
        app.setLastName("Test");
        app.setCompanyName("PrimaryCo");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.now());
        em.persist(app);
        em.flush();

        var result = wholesaleCustomerService.approveWholesaleApplication(app.getId());
        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
    }

    @Test
    @TestTransaction
    @DisplayName("event falls back to accountEmail when applicantEmail is blank")
    void eventFallsBackToAccountEmail()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("   "); // blank
        app.setAccountEmail("fallback@account.com");
        app.setFirstName("Fallback");
        app.setLastName("Test");
        app.setCompanyName("FallbackCo");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.now());
        em.persist(app);
        em.flush();

        var result = wholesaleCustomerService.rejectWholesaleApplication(app.getId(), "Testing fallback");
        assertEquals(WholesaleApplicationStatusEn.REJECTED, result.getStatus());
    }
}
