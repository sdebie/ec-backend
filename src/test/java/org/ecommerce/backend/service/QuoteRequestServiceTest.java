package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.common.dto.QuoteItemPriceInput;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.ecommerce.common.dto.QuoteRequestLineDto;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuoteRequestService}: status transition matrix,
 * product/variant snapshot capture, and unknown-variant rejection.
 */
@QuarkusTest
@DisplayName("QuoteRequestService")
class QuoteRequestServiceTest
{
    @Inject
    QuoteRequestService quoteRequestService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(ProductVariantEntity.class);
        PanacheMock.mock(QuoteRequestEntity.class);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ProductVariantEntity buildVariant(UUID variantId, String productName, String sku)
    {
        ProductEntity product = new ProductEntity();
        product.setName(productName);

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(variantId);
        variant.setSku(sku);
        variant.setProduct(product);
        return variant;
    }

    private QuoteRequestSubmissionDto buildSubmissionDto(List<QuoteRequestLineDto> items)
    {
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

    private QuoteRequestEntity buildExistingRequest(UUID id, QuoteRequestStatusEn status)
    {
        QuoteRequestEntity entity = new QuoteRequestEntity();
        entity.setId(id);
        entity.setName("Test");
        entity.setEmail("test@example.com");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.now().minusSeconds(3600));
        entity.setItems(new ArrayList<>());
        return entity;
    }

    private QuoteRequestItemEntity buildItem(QuoteRequestEntity request, String name, int quantity)
    {
        QuoteRequestItemEntity item = new QuoteRequestItemEntity();
        item.setId(UUID.randomUUID());
        item.setQuoteRequest(request);
        item.setProductNameSnapshot(name);
        item.setQuantity(quantity);
        return item;
    }

    private StaffUserEntity buildStaff()
    {
        StaffUserEntity staff = new StaffUserEntity();
        staff.setId(UUID.randomUUID());
        staff.setEmail("staff@example.com");
        staff.setFullName("Staff Member");
        return staff;
    }

    // ── Status transition matrix ────────────────────────────────────────────

    @Nested
    @DisplayName("updateStatus — forward-only transition matrix")
    class StatusTransitionTests
    {

        @Test
        @DisplayName("NEW → IN_PROGRESS succeeds")
        void newToInProgress()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestDetailsDto result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS);

            assertEquals(QuoteRequestStatusEn.IN_PROGRESS, result.getStatus());
            assertNotNull(result.getStatusChangedAt());
        }

        @Test
        @DisplayName("NEW → CLOSED succeeds (skip allowed)")
        void newToClosed()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestDetailsDto result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.CLOSED);

            assertEquals(QuoteRequestStatusEn.CLOSED, result.getStatus());
            assertNotNull(result.getStatusChangedAt());
        }

        @Test
        @DisplayName("IN_PROGRESS → CLOSED succeeds")
        void inProgressToClosed()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            QuoteRequestDetailsDto result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.CLOSED);

            assertEquals(QuoteRequestStatusEn.CLOSED, result.getStatus());
            assertNotNull(result.getStatusChangedAt());
        }

        @Test
        @DisplayName("CLOSED → NEW throws InvalidQuoteStatusTransitionException")
        void closedToNew()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.CLOSED);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.NEW));
        }

        @Test
        @DisplayName("CLOSED → IN_PROGRESS throws InvalidQuoteStatusTransitionException")
        void closedToInProgress()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.CLOSED);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("IN_PROGRESS → NEW throws InvalidQuoteStatusTransitionException (no re-open)")
        void inProgressToNew()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.NEW));
        }

        @Test
        @DisplayName("IN_PROGRESS → IN_PROGRESS throws (same-state not a valid transition)")
        void inProgressToInProgress()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity entity = buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS);
            when(QuoteRequestEntity.findById(id)).thenReturn(entity);

            assertThrows(InvalidQuoteStatusTransitionException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("not found → IllegalArgumentException")
        void notFound()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("null id → IllegalArgumentException")
        void nullId()
        {
            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(null, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("null newStatus → IllegalArgumentException")
        void nullStatus()
        {
            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(UUID.randomUUID(), null));
        }

        @Test
        @DisplayName("NEW → QUOTE_SENT via updateStatus is rejected — must go through generateAndSendQuote")
        void newToQuoteSentRejected()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(buildExistingRequest(id, QuoteRequestStatusEn.NEW));

            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.QUOTE_SENT));
        }

        @Test
        @DisplayName("IN_PROGRESS → QUOTE_SENT via updateStatus is rejected — must go through generateAndSendQuote")
        void inProgressToQuoteSentRejected()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(buildExistingRequest(id, QuoteRequestStatusEn.IN_PROGRESS));

            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.QUOTE_SENT));
        }

        @Test
        @DisplayName("QUOTE_SENT → CLOSED succeeds")
        void quoteSentToClosed()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(buildExistingRequest(id, QuoteRequestStatusEn.QUOTE_SENT));

            QuoteRequestDetailsDto result = quoteRequestService.updateStatus(id, QuoteRequestStatusEn.CLOSED);

            assertEquals(QuoteRequestStatusEn.CLOSED, result.getStatus());
        }

        @Test
        @DisplayName("QUOTE_SENT → IN_PROGRESS throws (no backward move once quoted)")
        void quoteSentToInProgressRejected()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(buildExistingRequest(id, QuoteRequestStatusEn.QUOTE_SENT));

            assertThrows(InvalidQuoteStatusTransitionException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.IN_PROGRESS));
        }

        @Test
        @DisplayName("CLOSED → QUOTE_SENT throws (CLOSED stays terminal)")
        void closedToQuoteSentRejected()
        {
            UUID id = UUID.randomUUID();
            when(QuoteRequestEntity.findById(id)).thenReturn(buildExistingRequest(id, QuoteRequestStatusEn.CLOSED));

            // Rejected either way: updateStatus refuses QUOTE_SENT as a target regardless of
            // current status, so this alone wouldn't prove CLOSED is terminal — the real proof
            // is generateAndSendQuote rejecting it too (see GenerateAndSendQuoteTests).
            assertThrows(IllegalArgumentException.class, () -> quoteRequestService.updateStatus(id, QuoteRequestStatusEn.QUOTE_SENT));
        }
    }

    // ── generateAndSendQuote ─────────────────────────────────────────────────

    @Nested
    @DisplayName("generateAndSendQuote")
    class GenerateAndSendQuoteTests
    {
        @Test
        @DisplayName("prices every item, computes the total, and moves to QUOTE_SENT")
        void happyPath()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 2);
            QuoteRequestItemEntity item2 = buildItem(request, "Gadget", 3);
            request.getItems().addAll(List.of(item1, item2));
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(
                    new QuoteItemPriceInput(item1.getId(), new BigDecimal("10.00")),
                    new QuoteItemPriceInput(item2.getId(), new BigDecimal("5.00"))
            );

            QuoteRequestDetailsDto result = quoteRequestService.generateAndSendQuote(id, prices, "Valid 14 days", buildStaff());

            // 2*10.00 + 3*5.00 = 35.00
            assertEquals(0, new BigDecimal("35.00").compareTo(result.getQuotedAmount()));
            assertEquals(QuoteRequestStatusEn.QUOTE_SENT, result.getStatus());
            assertEquals("Valid 14 days", result.getQuotedNotes());
            assertEquals("Staff Member", result.getQuotedByName());
            assertNotNull(result.getStatusChangedAt());
        }

        @Test
        @DisplayName("missing a price for one of the request's items is rejected")
        void missingItemPriceRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            QuoteRequestItemEntity item2 = buildItem(request, "Gadget", 1);
            request.getItems().addAll(List.of(item1, item2));
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN));

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.generateAndSendQuote(id, prices, null, buildStaff()));
        }

        @Test
        @DisplayName("a price for an item that isn't on the request is rejected")
        void unknownItemPriceRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            request.getItems().add(item1);
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(
                    new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN),
                    new QuoteItemPriceInput(UUID.randomUUID(), BigDecimal.ONE)
            );

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.generateAndSendQuote(id, prices, null, buildStaff()));
        }

        @Test
        @DisplayName("CLOSED request cannot be quoted")
        void closedRequestRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.CLOSED);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            request.getItems().add(item1);
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN));

            assertThrows(InvalidQuoteStatusTransitionException.class,
                    () -> quoteRequestService.generateAndSendQuote(id, prices, null, buildStaff()));
        }

        @Test
        @DisplayName("null quotedBy → IllegalArgumentException")
        void nullQuotedByRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            request.getItems().add(item1);
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN));

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.generateAndSendQuote(id, prices, null, null));
        }

        @Test
        @DisplayName("notes over the length limit are rejected")
        void notesTooLongRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            request.getItems().add(item1);
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN));
            String tooLong = "x".repeat(2001);

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.generateAndSendQuote(id, prices, tooLong, buildStaff()));
        }
    }

    // ── previewQuote ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("previewQuote")
    class PreviewQuoteTests
    {
        @Test
        @DisplayName("computes the preview total without mutating the underlying entity")
        void previewDoesNotMutateEntity()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 2);
            request.getItems().add(item1);
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), new BigDecimal("12.50")));

            QuoteRequestDetailsDto preview = quoteRequestService.previewQuote(id, prices, "Draft notes", buildStaff());

            // The DTO reflects the would-be quote...
            assertEquals(0, new BigDecimal("25.00").compareTo(preview.getQuotedAmount()));
            QuoteRequestItemDto previewItem = preview.getItems().get(0);
            assertEquals(0, new BigDecimal("12.50").compareTo(previewItem.getUnitPrice()));

            // ...but the real entity behind it — the one any other reader would see — is untouched.
            assertNull(request.getQuotedAmount());
            assertEquals(QuoteRequestStatusEn.NEW, request.getStatus());
            assertNull(item1.getUnitPrice());
        }

        @Test
        @DisplayName("missing item price coverage is rejected, same as generateAndSendQuote")
        void missingCoverageRejected()
        {
            UUID id = UUID.randomUUID();
            QuoteRequestEntity request = buildExistingRequest(id, QuoteRequestStatusEn.NEW);
            QuoteRequestItemEntity item1 = buildItem(request, "Widget", 1);
            QuoteRequestItemEntity item2 = buildItem(request, "Gadget", 1);
            request.getItems().addAll(List.of(item1, item2));
            when(QuoteRequestEntity.findById(id)).thenReturn(request);

            List<QuoteItemPriceInput> prices = List.of(new QuoteItemPriceInput(item1.getId(), BigDecimal.TEN));

            assertThrows(IllegalArgumentException.class,
                    () -> quoteRequestService.previewQuote(id, prices, null, buildStaff()));
        }
    }

    // ── Snapshot capture ────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit — snapshot capture")
    class SnapshotCaptureTests
    {

        @Test
        @DisplayName("captures product name and variant SKU in line item snapshots")
        void capturesProductNameAndSku()
        {
            UUID variantId = UUID.randomUUID();
            ProductVariantEntity variant = buildVariant(variantId, "Widget Pro", "WID-PRO-001");

            when(ProductVariantEntity.findByIdWithProduct(variantId)).thenReturn(variant);
            doNothing().when(PanacheMock.getMock(QuoteRequestEntity.class)).persist();

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(variantId, 3)
            ));

            QuoteRequestEntity result = quoteRequestService.submit(dto);

            assertEquals(1, result.getItems().size());
            QuoteRequestItemEntity item = result.getItems().get(0);
            assertEquals("Widget Pro", item.getProductNameSnapshot());
            assertEquals("WID-PRO-001", item.getVariantSkuSnapshot());
            assertEquals(3, item.getQuantity());
            assertEquals(variant, item.getVariant());
        }

        @Test
        @DisplayName("captures multiple line items with correct snapshots")
        void capturesMultipleItems()
        {
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

            assertEquals(2, result.getItems().size());
            assertEquals("Product A", result.getItems().get(0).getProductNameSnapshot());
            assertEquals("SKU-A", result.getItems().get(0).getVariantSkuSnapshot());
            assertEquals(5, result.getItems().get(0).getQuantity());
            assertEquals("Product B", result.getItems().get(1).getProductNameSnapshot());
            assertEquals("SKU-B", result.getItems().get(1).getVariantSkuSnapshot());
            assertEquals(10, result.getItems().get(1).getQuantity());
        }

        @Test
        @DisplayName("persisted entity has correct contact fields and status NEW")
        void persistsContactFieldsAndStatus()
        {
            UUID variantId = UUID.randomUUID();
            ProductVariantEntity variant = buildVariant(variantId, "Test Product", "TST-001");

            when(ProductVariantEntity.findByIdWithProduct(variantId)).thenReturn(variant);
            doNothing().when(PanacheMock.getMock(QuoteRequestEntity.class)).persist();

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(variantId, 1)
            ));

            QuoteRequestEntity result = quoteRequestService.submit(dto);

            assertEquals("Jane Doe", result.getName());
            assertEquals("jane@example.com", result.getEmail());
            assertEquals("0821234567", result.getPhone());
            assertEquals("ACME Corp", result.getCompany());
            assertEquals("Need bulk pricing please", result.getMessage());
            assertEquals(QuoteRequestStatusEn.NEW, result.getStatus());
            assertNotNull(result.getCreatedAt());
        }
    }

    // ── Unknown-variant rejection ───────────────────────────────────────────

    @Nested
    @DisplayName("submit — unknown-variant rejection")
    class UnknownVariantTests
    {

        @Test
        @DisplayName("throws IllegalArgumentException when variantId does not exist")
        void unknownVariantThrows()
        {
            UUID unknownId = UUID.randomUUID();
            when(ProductVariantEntity.findByIdWithProduct(unknownId)).thenReturn(null);

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(new QuoteRequestLineDto(unknownId, 2)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> quoteRequestService.submit(dto));

            assertTrue(ex.getMessage().contains(unknownId.toString()));
        }

        @Test
        @DisplayName("throws on first unknown variant in a multi-item request")
        void firstUnknownInMultiItemThrows()
        {
            UUID validId = UUID.randomUUID();
            UUID unknownId = UUID.randomUUID();
            ProductVariantEntity validVariant = buildVariant(validId, "Valid Product", "VAL-001");

            when(ProductVariantEntity.findByIdWithProduct(validId)).thenReturn(validVariant);
            when(ProductVariantEntity.findByIdWithProduct(unknownId)).thenReturn(null);

            QuoteRequestSubmissionDto dto = buildSubmissionDto(List.of(
                    new QuoteRequestLineDto(validId, 1),
                    new QuoteRequestLineDto(unknownId, 1)
            ));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> quoteRequestService.submit(dto));
            assertTrue(ex.getMessage().contains(unknownId.toString()));
        }
    }
}
