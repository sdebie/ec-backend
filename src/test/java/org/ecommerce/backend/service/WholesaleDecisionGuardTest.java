package org.ecommerce.backend.service;

// Feature: wholesale-application-review-workflow, Property 1: Decision guard and state transition, Property 2: Rejection reason invariant
// Validates: Requirements 2.2, 2.3, 3.1, 3.3

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed characterization + guard tests for the wholesale decision (approve/reject).
 *
 * Uses the REAL {@link WholesaleCustomerService#approveWholesaleApplication} and
 * {@link WholesaleCustomerService#rejectWholesaleApplication} — no mocks.
 * {@link TestTransaction} ensures each test rolls back so the shared dev DB is never mutated.
 *
 * Feature: wholesale-application-review-workflow
 * Property 1: Decision guard and state transition
 * Property 2: Rejection reason invariant
 */
@QuarkusTest
class WholesaleDecisionGuardTest {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Creates and persists a PENDING wholesale application with a unique applicant email.
     * No linked customer — the approve path will create one.
     */
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

    /**
     * Creates a PENDING application and immediately approves it, returning the
     * now-APPROVED entity. Useful for testing guards on non-PENDING applications.
     */
    private WholesaleApplicationEntity createApprovedApplication() {
        WholesaleApplicationEntity app = createPendingApplication();
        wholesaleCustomerService.approveWholesaleApplication(app.id);
        em.flush();
        em.clear();
        return em.find(WholesaleApplicationEntity.class, app.id);
    }

    /**
     * Creates a PENDING application and immediately rejects it, returning the
     * now-REJECTED entity. Useful for testing guards on non-PENDING applications.
     */
    private WholesaleApplicationEntity createRejectedApplication() {
        WholesaleApplicationEntity app = createPendingApplication();
        wholesaleCustomerService.rejectWholesaleApplication(app.id, "Initial rejection reason");
        em.flush();
        em.clear();
        return em.find(WholesaleApplicationEntity.class, app.id);
    }

    // ─── Property 1: Decision guard and state transition ────────────────────

    @Test
    @TestTransaction
    void approve_pendingApplication_shouldTransitionToApproved() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.approveWholesaleApplication(appId);

        // Verify returned DTO
        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        assertNotNull(result.getProcessedAt());
        assertNull(result.getRejectionReason());

        // Verify persisted entity
        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.status);
        assertNotNull(persisted.processedAt);
        assertNull(persisted.rejectionReason);
    }

    @Test
    @TestTransaction
    void reject_pendingApplication_withReason_shouldTransitionToRejected_andTrimReason() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.rejectWholesaleApplication(appId, "Some reason  ");

        // Verify returned DTO
        assertEquals(WholesaleApplicationStatusEn.REJECTED, result.getStatus());
        assertNotNull(result.getProcessedAt());
        assertEquals("Some reason", result.getRejectionReason());

        // Verify persisted entity — stored value is trimmed
        em.flush();
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.status);
        assertNotNull(persisted.processedAt);
        assertEquals("Some reason", persisted.rejectionReason);
    }

    @Test
    @TestTransaction
    void approve_nonPendingApplication_shouldThrowAndNotChangeState() {
        WholesaleApplicationEntity app = createApprovedApplication();
        UUID appId = app.id;
        OffsetDateTime originalProcessedAt = app.processedAt;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.approveWholesaleApplication(appId)
        );

        assertTrue(ex.getMessage().contains("PENDING"));

        // Verify no state change
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.status);
        assertEquals(originalProcessedAt.toInstant(), persisted.processedAt.toInstant());
    }

    @Test
    @TestTransaction
    void reject_nonPendingApplication_shouldThrowAndNotChangeState() {
        WholesaleApplicationEntity app = createRejectedApplication();
        UUID appId = app.id;
        OffsetDateTime originalProcessedAt = app.processedAt;
        String originalReason = app.rejectionReason;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "New reason")
        );

        assertTrue(ex.getMessage().contains("PENDING"));

        // Verify no state change
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.REJECTED, persisted.status);
        assertEquals(originalProcessedAt.toInstant(), persisted.processedAt.toInstant());
        assertEquals(originalReason, persisted.rejectionReason);
    }

    // ─── Property 2: Rejection reason invariant ─────────────────────────────

    @Test
    @TestTransaction
    void reject_withEmptyReason_shouldThrowAndNotChangeState() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "")
        );

        assertTrue(ex.getMessage().contains("reason"));

        // Verify no state change
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.PENDING, persisted.status);
        assertNull(persisted.processedAt);
        assertNull(persisted.rejectionReason);
    }

    @Test
    @TestTransaction
    void reject_withWhitespaceOnlyReason_shouldThrowAndNotChangeState() {
        WholesaleApplicationEntity app = createPendingApplication();
        UUID appId = app.id;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.rejectWholesaleApplication(appId, "   ")
        );

        assertTrue(ex.getMessage().contains("reason"));

        // Verify no state change
        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, appId);
        assertEquals(WholesaleApplicationStatusEn.PENDING, persisted.status);
        assertNull(persisted.processedAt);
        assertNull(persisted.rejectionReason);
    }
}
