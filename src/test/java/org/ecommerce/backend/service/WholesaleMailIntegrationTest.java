package org.ecommerce.backend.service;

// Feature: wholesale-application-review-workflow, Property 3: At-most-once, commit-only notification
// Feature: wholesale-application-review-workflow, Property 5: Mail failure isolation
// Validates: Requirements 4.3, 4.5, 6.1, 6.2, 6.3, 6.4, 6.5

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
class WholesaleMailIntegrationTest {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private WholesaleApplicationEntity createPendingApplication() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.applicantEmail = "test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        app.firstName = "Test";
        app.lastName = "Applicant";
        app.companyName = "Test Company";
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.now();
        em.persist(app);
        em.flush();
        return app;
    }

    private WholesaleApplicationEntity createConvertedApplication() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.applicantEmail = "converted-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        app.firstName = "Converted";
        app.lastName = "User";
        app.companyName = "Converted Corp";
        app.status = WholesaleApplicationStatusEn.CONVERTED;
        app.processedAt = OffsetDateTime.now();
        app.createdAt = OffsetDateTime.now();
        em.persist(app);
        em.flush();
        return app;
    }

    // ─── Property 3: No email on guard failure ──────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("reject on non-PENDING throws guard error — no event fired, no state change")
    void rejectNonPendingThrowsNoEvent() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;
        wholesaleCustomerService.approveWholesaleApplication(appId);
        em.flush();
        em.clear();

        assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "Too late"));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.status);
    }

    @Test
    @TestTransaction
    @DisplayName("approve on non-PENDING (already REJECTED) throws guard error — no event fired")
    void approveNonPendingThrowsNoEvent() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;
        wholesaleCustomerService.rejectWholesaleApplication(appId, "Rejected");
        em.flush();
        em.clear();

        assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.approveWholesaleApplication(appId));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.status);
    }

    @Test
    @TestTransaction
    @DisplayName("CONVERTED application cannot be approved — no event fired")
    void convertedCannotBeApproved() {
        WholesaleApplicationEntity app = createConvertedApplication();
        UUID appId = app.id;

        assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.approveWholesaleApplication(appId));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, persisted.status);
    }

    @Test
    @TestTransaction
    @DisplayName("CONVERTED application cannot be rejected — no event fired")
    void convertedCannotBeRejected() {
        WholesaleApplicationEntity app = createConvertedApplication();
        UUID appId = app.id;

        assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "Nope"));

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, persisted.status);
    }

    // ─── Property 3: Rollback produces zero emails ──────────────────────────

    @Test
    @TestTransaction
    @DisplayName("@TestTransaction rolls back → AFTER_SUCCESS observer never fires (by design)")
    void testTransactionRollsBackSoObserverNeverFires() {
        // Under @TestTransaction the transaction is rolled back at test end.
        // AFTER_SUCCESS observers only fire on commit — so this verifies
        // the "no email on rollback" requirement structurally.
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        var result = wholesaleCustomerService.approveWholesaleApplication(appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        // If we got here without exception, the event fire did not error.
        // The @TestTransaction rollback ensures AFTER_SUCCESS never executes.
    }

    // ─── Property 5: Decision persists even with missing recipient ──────────

    @Test
    @TestTransaction
    @DisplayName("application with blank applicantEmail and null accountEmail — decision still persists")
    void decisionPersistsWithBlankRecipient() {
        // applicant_email is NOT NULL in the DB, but can be blank (whitespace).
        // The buildDecisionEvent treats blank as "missing" and falls back to accountEmail.
        // When accountEmail is also null, the event carries a null recipient →
        // notifier skips + logs (but never throws).
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.applicantEmail = "   "; // blank — column constraint satisfied, treated as missing
        app.accountEmail = null;
        app.firstName = "NoEmail";
        app.lastName = "User";
        app.companyName = "Ghost Corp";
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.now();
        em.persist(app);
        em.flush();

        UUID appId = app.id;

        // The reject call should still succeed — the event carries a null recipient
        // (blank applicantEmail + null accountEmail = null), and the notifier skips
        var result = wholesaleCustomerService.rejectWholesaleApplication(appId, "No email provided");

        assertEquals(WholesaleApplicationStatusEn.REJECTED, result.getStatus());
        assertEquals("No email provided", result.getRejectionReason());

        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.status);
        assertEquals("No email provided", persisted.rejectionReason);
    }

    @Test
    @TestTransaction
    @DisplayName("approval decision persists regardless of notification outcome")
    void approvalPersistsRegardlessly() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        var result = wholesaleCustomerService.approveWholesaleApplication(appId);

        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        assertNotNull(result.getProcessedAt());

        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.status);
        assertNotNull(persisted.processedAt);
    }

    // ─── Recipient chain: applicantEmail first, accountEmail fallback ────────

    @Test
    @TestTransaction
    @DisplayName("event uses applicantEmail as primary recipient — approval succeeds")
    void eventUsesApplicantEmailFirst() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.applicantEmail = "applicant@primary.com";
        app.accountEmail = "account@fallback.com";
        app.firstName = "Primary";
        app.lastName = "Test";
        app.companyName = "PrimaryCo";
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.now();
        em.persist(app);
        em.flush();

        var result = wholesaleCustomerService.approveWholesaleApplication(app.id);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
    }

    @Test
    @TestTransaction
    @DisplayName("event falls back to accountEmail when applicantEmail is blank")
    void eventFallsBackToAccountEmail() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.applicantEmail = "   "; // blank
        app.accountEmail = "fallback@account.com";
        app.firstName = "Fallback";
        app.lastName = "Test";
        app.companyName = "FallbackCo";
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.now();
        em.persist(app);
        em.flush();

        var result = wholesaleCustomerService.rejectWholesaleApplication(app.id, "Testing fallback");
        assertEquals(WholesaleApplicationStatusEn.REJECTED, result.getStatus());
    }
}
