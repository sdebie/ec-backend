package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.common.dto.QuoteRequestLineDto;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuoteRequestService}: status transition matrix,
 * product/variant snapshot capture, and unknown-variant rejection.
 */
@QuarkusTest
@DisplayName("QuoteRequestService")
class QuoteRequestServiceTest {

    @Inject
    QuoteRequestService quoteRequestService;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(ProductVariantEntity.class);
        PanacheMock.mock(QuoteRequestEntity.class);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ProductVariantEntity buildVariant(UUID variantId, String productName, String sku) {
        ProductEntity product = new ProductEntity();
        product.name = productName;

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = variantId;
        variant.sku = sku;
        variant.product = product;
        return variant;
    }

    private QuoteRequestSubmissionDto buildSubmissionDto(List<QuoteRequestLineDto> items) {
        return new QuoteRequestSubmissionDto(
                "Jane Doe",
                "jane@example.com",
                "0821234567",
                "ACME Corp",
                "Need bulk pricing please",
                null, // honeypot empty
                items
        );
    }

    private QuoteRequestEntity buildExistingRequest(UUID id, QuoteRequestStatusEn status) {
        QuoteRequestEntity entity = new QuoteRequestEntity();
        entity.id = id;
        entity.name = "Test";
        entity.email = "test@example.com";
        entity.status = status;
        entity.createdAt = Instant.now().minusSeconds(3600);
        return entity;
    }

    // ── Status transition matrix ────────────────────────────────────────────

    @Nested
    @DisplayName("updateStatus — forward-only transition matrix")
    class StatusTransitionTests {

        @Test
        @DisplayName("NEW → IN_PROGRESS succeeds")
        void newToInProgress() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestEntity result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS);

            assertEquals(QuoteRequestStatusEn.IN_PROGRESS, result.status);
            assertNotNull(result.statusChangedAt);
        }

        @Test
        @DisplayName("NEW → CLOSED succeeds (skip allowed)")
        void newToClosed() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestEntity result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.CLOSED);

            assertEquals(QuoteRequestStatusEn.CLOSED, result.status);
            assertNotNull(result.statusChangedAt);
        }

        @Test
        @DisplayName("IN_PROGRESS → CLOSED succeeds")
        void inProgressToClosed() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestEntity result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.CLOSED);

            assertEquals(QuoteRequestStatusEn.CLOSED, result.status);
            assertNotNull(result.statusChangedAt);
        }

        @Test
        @DisplayName("CLOSED → NEW throws InvalidQuoteStatusTransitionException")
        void closedToNew() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.CLOSED);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class,
                    () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.NEW));
        }

        @Test
        @DisplayName("CLOSED → IN_PROGRESS throws InvalidQuoteStatusTransitionException")
        void closedToInProgress() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.CLOSED);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class,
                    () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("IN_PROGRESS → NEW throws InvalidQuoteStatusTransitionException (no re-open)")
        void inProgressToNew() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class,
                    () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.NEW));
        }

        @Test
        @DisplayName("IN_PROGRESS → IN_PROGRESS throws (same-state not a valid transition)")
        void inProgressToInProgress() {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class,
                    () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("not found → IllegalArgumentException")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("null id → IllegalArgumentException")
        void nullId() {
            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.updateStatus(null, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("null newStatus → IllegalArgumentException")
        void nullStatus() {
            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.updateStatus(UUID.randomUUID(), null));
        }
    }

    // ── Snapshot capture ────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit — snapshot capture")
    class SnapshotCaptureTests {

        @Test
        @DisplayName("captures product name and variant SKU in line item snapshots")
        void capturesProductNameAndSku() {
            UUID variantId = UUID.randomUUID();
            ProductVariantEntity variant = buildVariant(variantId, "Widget Pro", "WID-PRO-001");

            when(ProductVariantEntity.findByIdWithProduct(variantId)).thenReturn(variant);
            doNothing().when(PanacheMock.getMock(QuoteRequestEntity.class)).persist();

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(variantId, 3)
            ));

            QuoteRequestEntity result = quoteRequestService.submit(dto);

            assertEquals(1, result.items.size());
            QuoteRequestItemEntity item = result.items.get(0);
            assertEquals("Widget Pro", item.productNameSnapshot);
            assertEquals("WID-PRO-001", item.variantSkuSnapshot);
            assertEquals(3, item.quantity);
            assertEquals(variant, item.variant);
        }

        @Test
        @DisplayName("captures multiple line items with correct snapshots")
        void capturesMultipleItems() {
            UUID variantId1 = UUID.randomUUID();
            UUID variantId2 = UUID.randomUUID();
            ProductVariantEntity variant1 = buildVariant(variantId1, "Product A", "SKU-A");
            ProductVariantEntity variant2 = buildVariant(variantId2, "Product B", "SKU-B");

            when(ProductVariantEntity.findByIdWithProduct(variantId1)).thenReturn(variant1);
            when(ProductVariantEntity.findByIdWithProduct(variantId2)).thenReturn(variant2);
            doNothing().when(PanacheMock.getMock(QuoteRequestEntity.class)).persist();

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(variantId1, 5),
                    new QuoteRequestLineDto(variantId2, 10)
            ));

            QuoteRequestEntity result = quoteRequestService.submit(dto);

            assertEquals(2, result.items.size());
            assertEquals("Product A", result.items.get(0).productNameSnapshot);
            assertEquals("SKU-A", result.items.get(0).variantSkuSnapshot);
            assertEquals(5, result.items.get(0).quantity);
            assertEquals("Product B", result.items.get(1).productNameSnapshot);
            assertEquals("SKU-B", result.items.get(1).variantSkuSnapshot);
            assertEquals(10, result.items.get(1).quantity);
        }

        @Test
        @DisplayName("persisted entity has correct contact fields and status NEW")
        void persistsContactFieldsAndStatus() {
            UUID variantId = UUID.randomUUID();
            ProductVariantEntity variant = buildVariant(variantId, "Test Product", "TST-001");

            when(ProductVariantEntity.findByIdWithProduct(variantId)).thenReturn(variant);
            doNothing().when(PanacheMock.getMock(QuoteRequestEntity.class)).persist();

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(variantId, 1)
            ));

            QuoteRequestEntity result = quoteRequestService.submit(dto);

            assertEquals("Jane Doe", result.name);
            assertEquals("jane@example.com", result.email);
            assertEquals("0821234567", result.phone);
            assertEquals("ACME Corp", result.company);
            assertEquals("Need bulk pricing please", result.message);
            assertEquals(QuoteRequestStatusEn.NEW, result.status);
            assertNotNull(result.createdAt);
        }
    }

    // ── Unknown-variant rejection ───────────────────────────────────────────

    @Nested
    @DisplayName("submit — unknown-variant rejection")
    class UnknownVariantTests {

        @Test
        @DisplayName("throws IllegalArgumentException when variantId does not exist")
        void unknownVariantThrows() {
            UUID unknownId = UUID.randomUUID();
            when(ProductVariantEntity.findByIdWithProduct(unknownId)).thenReturn(null);

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(unknownId, 2)
            ));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.submit(dto));

            assertTrue(ex.getMessage().contains(unknownId.toString()));
        }

        @Test
        @DisplayName("throws on first unknown variant in a multi-item request")
        void firstUnknownInMultiItemThrows() {
            UUID validId = UUID.randomUUID();
            UUID unknownId = UUID.randomUUID();
            ProductVariantEntity validVariant = buildVariant(validId, "Valid Product", "VAL-001");

            when(ProductVariantEntity.findByIdWithProduct(validId)).thenReturn(validVariant);
            when(ProductVariantEntity.findByIdWithProduct(unknownId)).thenReturn(null);

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(validId, 1),
                    new QuoteRequestLineDto(unknownId, 1)
            ));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.submit(dto));

            assertTrue(ex.getMessage().contains(unknownId.toString()));
        }
    }
}
