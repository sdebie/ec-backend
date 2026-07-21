package org.ecommerce.backend.service;

// Feature: quote-request-workflow, Property 6: submission data round-trip
// (Requirements 5.1) + DB-level variant ON DELETE SET NULL snapshot survival.

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.common.dto.QuoteRequestLineDto;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration tests for the REAL quote submission path.
 *
 * {@link QuoteRequestResourceIT} mocks {@code QuoteRequestService} and
 * {@link QuoteRequestServiceTest} uses PanacheMock, so neither proves that a
 * submission actually produces rows. These tests drive the real
 * {@link QuoteRequestService} against the real datasource inside a
 * {@link TestTransaction} (rolled back afterward — the shared dev DB is never
 * mutated, and AFTER_SUCCESS mail observers never fire on rollback).
 *
 * Covers:
 *   Property 6: Submission data round-trip  (Requirements 5.1)
 *   DB-level ON DELETE SET NULL + snapshot survival  (Requirements 5.1)
 *   Real-path status transition persistence  (Requirements 6.3)
 */
@QuarkusTest
class QuoteRequestPersistenceIT {

    @Inject
    QuoteRequestService quoteRequestService;

    @Inject
    EntityManager em;

    private ProductVariantEntity newVariant(String marker) {
        ProductEntity product = new ProductEntity();
        product.name = marker + " Product";
        product.slug = (marker + "-" + UUID.randomUUID()).toLowerCase();
        product.status = ProductStatusEn.ACTIVE;
        product.productType = ProductTypeEn.SIMPLE;
        product.persist();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.product = product;
        variant.sku = marker + "-SKU-" + UUID.randomUUID().toString().substring(0, 8);
        variant.status = ProductStatusEn.ACTIVE;
        variant.persist();
        return variant;
    }

    private QuoteRequestSubmissionDto submission(UUID variantId, int quantity) {
        return new QuoteRequestSubmissionDto(
                "Jane Roundtrip",
                "jane.roundtrip@example.com",
                "0821234567",
                "Roundtrip Corp",
                "Persist me properly.",
                null,
                List.of(new QuoteRequestLineDto(variantId, quantity))
        );
    }

    // ── Property 6: submission data round-trip ──────────────────────────────

    @Test
    @TestTransaction
    void submitPersistsAllFieldsAndSnapshots_roundTrip() {
        ProductVariantEntity variant = newVariant("ZZQRIT1");
        em.flush();

        QuoteRequestEntity submitted = quoteRequestService.submit(submission(variant.id, 7));
        em.flush();
        em.clear();

        QuoteRequestEntity reloaded = QuoteRequestEntity.findById(submitted.id);
        assertNotNull(reloaded, "submitted request must exist in the DB");
        assertEquals("Jane Roundtrip", reloaded.name);
        assertEquals("jane.roundtrip@example.com", reloaded.email);
        assertEquals("0821234567", reloaded.phone);
        assertEquals("Roundtrip Corp", reloaded.company);
        assertEquals("Persist me properly.", reloaded.message);
        assertEquals(QuoteRequestStatusEn.NEW, reloaded.status);
        assertNotNull(reloaded.createdAt);
        assertNull(reloaded.statusChangedAt);

        assertEquals(1, reloaded.items.size());
        QuoteRequestItemEntity item = reloaded.items.get(0);
        assertEquals("ZZQRIT1 Product", item.productNameSnapshot);
        assertEquals(variant.sku, item.variantSkuSnapshot);
        assertEquals(7, item.quantity);
        assertNotNull(item.variant, "variant reference intact while variant exists");
        assertEquals(variant.id, item.variant.id);
    }

    // ── DB-level ON DELETE SET NULL + snapshot survival ─────────────────────

    @Test
    @TestTransaction
    void variantDeletion_setsItemVariantNull_snapshotsSurvive() {
        ProductVariantEntity variant = newVariant("ZZQRIT2");
        em.flush();

        QuoteRequestEntity submitted = quoteRequestService.submit(submission(variant.id, 3));
        em.flush();

        String expectedSku = variant.sku;

        // Delete the variant row directly so the FK's ON DELETE SET NULL fires at
        // the DB level (em.remove would require untangling the ORM association).
        em.clear();
        em.createNativeQuery("DELETE FROM product_variants WHERE id = :id")
                .setParameter("id", variant.id)
                .executeUpdate();
        em.clear();

        QuoteRequestEntity reloaded = QuoteRequestEntity.findById(submitted.id);
        assertNotNull(reloaded);
        assertEquals(1, reloaded.items.size());
        QuoteRequestItemEntity item = reloaded.items.get(0);
        assertNull(item.variant, "FK ON DELETE SET NULL must null the reference");
        assertEquals("ZZQRIT2 Product", item.productNameSnapshot);
        assertEquals(expectedSku, item.variantSkuSnapshot);
        assertEquals(3, item.quantity);
    }

    // ── Real-path status transition persistence ─────────────────────────────

    @Test
    @TestTransaction
    void updateStatus_persistsNewStatusAndTimestamp() {
        ProductVariantEntity variant = newVariant("ZZQRIT3");
        em.flush();

        QuoteRequestEntity submitted = quoteRequestService.submit(submission(variant.id, 1));
        em.flush();

        quoteRequestService.updateStatus(submitted.id, QuoteRequestStatusEn.IN_PROGRESS);
        em.flush();
        em.clear();

        QuoteRequestEntity reloaded = QuoteRequestEntity.findById(submitted.id);
        assertEquals(QuoteRequestStatusEn.IN_PROGRESS, reloaded.status);
        assertNotNull(reloaded.statusChangedAt);

        // Invalid backward transition rejected by the real service against real state
        assertThrows(InvalidQuoteStatusTransitionException.class,
                () -> quoteRequestService.updateStatus(submitted.id, QuoteRequestStatusEn.NEW));
    }

    @Test
    @TestTransaction
    void submitUnknownVariant_persistsNothing() {
        long before = QuoteRequestEntity.count();

        assertThrows(IllegalArgumentException.class,
                () -> quoteRequestService.submit(submission(UUID.randomUUID(), 2)));

        assertEquals(before, QuoteRequestEntity.count(), "failed submission must not leave a row");
    }
}
