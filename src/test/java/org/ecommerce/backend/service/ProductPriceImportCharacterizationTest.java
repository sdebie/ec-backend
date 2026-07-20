package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.entity.ProductPriceUploadBatchEntity;
import org.ecommerce.common.entity.ProductPriceUploadStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceUploadBatchRepository;
import org.ecommerce.common.repository.ProductPriceUploadStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for {@link ProductPriceImportService}.
 *
 * Pins the current behaviour of the CSV upload → staging → comparison pipeline:
 * - All-valid price file: rows staged as VALID, correct proposed/current prices, hasChanges flags
 * - Invalid rows: missing/unknown SKUs produce specific validation errors
 * - Malformed data: non-numeric prices produce specific error messages
 * - Empty file: zero rows staged, batch marked PENDING with zero counts
 *
 * These baselines guard against behavioural regression during the upcoming
 * decomposition into parser/validator/orchestrator (tasks 8.2–8.4).
 *
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class ProductPriceImportCharacterizationTest {

    @Inject
    ProductPriceImportService productPriceImportService;

    @InjectMock
    ProductPriceUploadBatchRepository productPriceUploadBatchRepository;

    @InjectMock
    ProductPriceUploadStagedRepository productPriceUploadStagedRepository;

    @InjectMock
    ProductVariantRepository productVariantRepository;

    private UUID batchId;
    private ProductPriceUploadBatchEntity batch;
    private List<ProductPriceUploadStagedEntity> capturedStagedRows;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(VariantPricesEntity.class);

        batchId = UUID.randomUUID();
        batch = new ProductPriceUploadBatchEntity();
        batch.id = batchId;
        batch.productUploadStatusEn = ProductUploadStatusEn.IMPORTING;
        batch.totalRows = 0;
        batch.processedRows = 0;
        batch.skippedRows = 0;
        batch.validationErrorCount = 0;

        when(productPriceUploadBatchRepository.findById(batchId)).thenReturn(batch);

        capturedStagedRows = new ArrayList<>();
        doAnswer(invocation -> {
            ProductPriceUploadStagedEntity entity = invocation.getArgument(0);
            entity.id = UUID.randomUUID();
            capturedStagedRows.add(entity);
            return null;
        }).when(productPriceUploadStagedRepository).persist(any(ProductPriceUploadStagedEntity.class));
    }

    // ── Test: All-valid price file ──────────────────────────────────────────

    @Test
    void allValidPriceFile_stagesRowsAsValid_withCorrectPricesAndChangeDetection() throws Exception {
        // Arrange: two known variants with existing prices
        ProductVariantEntity variant1 = buildVariant("SKU-001");
        ProductVariantEntity variant2 = buildVariant("SKU-002");

        when(productVariantRepository.findBySku("SKU-001")).thenReturn(variant1);
        when(productVariantRepository.findBySku("SKU-002")).thenReturn(variant2);

        // variant1 has existing retail=100.00, wholesale=80.00
        VariantPricesEntity existingRetail1 = buildPrice(variant1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        VariantPricesEntity existingWholesale1 = buildPrice(variant1, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));
        when(VariantPricesEntity.findLatestByVariantAndType(variant1.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail1);
        when(VariantPricesEntity.findLatestByVariantAndType(variant1.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(existingWholesale1);

        // variant2 has existing retail=200.00, no wholesale
        VariantPricesEntity existingRetail2 = buildPrice(variant2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"));
        when(VariantPricesEntity.findLatestByVariantAndType(variant2.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail2);
        when(VariantPricesEntity.findLatestByVariantAndType(variant2.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-001,150.00,90.00
                SKU-002,200.00,120.00
                """;

        // Act
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        // Assert: two staged rows captured
        assertEquals(2, capturedStagedRows.size());

        // Row 1: SKU-001, prices changed (retail 100→150, wholesale 80→90)
        ProductPriceUploadStagedEntity row1 = capturedStagedRows.get(0);
        assertEquals("SKU-001", row1.sku);
        assertEquals(0, new BigDecimal("150.00").compareTo(row1.retailPrice));
        assertEquals(0, new BigDecimal("90.00").compareTo(row1.wholesalePrice));
        assertEquals(0, new BigDecimal("100.00").compareTo(row1.currentRetailPrice));
        assertEquals(0, new BigDecimal("80.00").compareTo(row1.currentWholesalePrice));
        assertEquals(ProductImportValidationStatusEn.VALID, row1.validationStatus);
        assertNull(row1.validationErrors);
        assertTrue(row1.hasChanges, "hasChanges should be true when prices differ");

        // Row 2: SKU-002, retail unchanged (200→200), wholesale new (null→120)
        ProductPriceUploadStagedEntity row2 = capturedStagedRows.get(1);
        assertEquals("SKU-002", row2.sku);
        assertEquals(0, new BigDecimal("200.00").compareTo(row2.retailPrice));
        assertEquals(0, new BigDecimal("120.00").compareTo(row2.wholesalePrice));
        assertEquals(0, new BigDecimal("200.00").compareTo(row2.currentRetailPrice));
        assertNull(row2.currentWholesalePrice);
        assertEquals(ProductImportValidationStatusEn.VALID, row2.validationStatus);
        assertNull(row2.validationErrors);
        assertTrue(row2.hasChanges, "hasChanges should be true when wholesale is new");

        // Batch final state: PENDING, totalRows=2, validationErrorCount=0
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
        assertEquals(2, batch.totalRows);
        assertEquals(0, batch.validationErrorCount);
    }

    @Test
    void allValidPriceFile_noPriceChange_hasChangesFalse() throws Exception {
        ProductVariantEntity variant = buildVariant("SKU-SAME");
        when(productVariantRepository.findBySku("SKU-SAME")).thenReturn(variant);

        VariantPricesEntity existingRetail = buildPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        VariantPricesEntity existingWholesale = buildPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(existingWholesale);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-SAME,100.00,80.00
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceUploadStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-SAME", row.sku);
        assertEquals(ProductImportValidationStatusEn.VALID, row.validationStatus);
        assertFalse(row.hasChanges, "hasChanges should be false when prices are identical");
    }

    // ── Test: Invalid rows (unknown SKU, missing SKU) ───────────────────────

    @Test
    void invalidRows_unknownSku_producesValidationError() throws Exception {
        // SKU-KNOWN exists, SKU-UNKNOWN does not
        ProductVariantEntity knownVariant = buildVariant("SKU-KNOWN");
        when(productVariantRepository.findBySku("SKU-KNOWN")).thenReturn(knownVariant);
        when(productVariantRepository.findBySku("SKU-UNKNOWN")).thenReturn(null);

        VariantPricesEntity existingRetail = buildPrice(knownVariant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        when(VariantPricesEntity.findLatestByVariantAndType(knownVariant.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(VariantPricesEntity.findLatestByVariantAndType(knownVariant.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-KNOWN,55.00,40.00
                SKU-UNKNOWN,99.99,75.00
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(2, capturedStagedRows.size());

        // Row 1: valid
        ProductPriceUploadStagedEntity validRow = capturedStagedRows.get(0);
        assertEquals("SKU-KNOWN", validRow.sku);
        assertEquals(ProductImportValidationStatusEn.VALID, validRow.validationStatus);
        assertNull(validRow.validationErrors);

        // Row 2: invalid — unknown SKU
        ProductPriceUploadStagedEntity invalidRow = capturedStagedRows.get(1);
        assertEquals("SKU-UNKNOWN", invalidRow.sku);
        assertEquals(ProductImportValidationStatusEn.INVALID, invalidRow.validationStatus);
        assertEquals("variant with sku 'SKU-UNKNOWN' not found", invalidRow.validationErrors);

        // Batch: totalRows=2, validationErrorCount=1
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
        assertEquals(2, batch.totalRows);
        assertEquals(1, batch.validationErrorCount);
    }

    @Test
    void invalidRows_missingSku_producesSkuRequiredError() throws Exception {
        // A row with blank/empty SKU
        String csv = """
                sku,retail_price,wholesale_price
                ,100.00,80.00
                """;

        // findBySku with blank returns null (repository handles this)
        when(productVariantRepository.findBySku(null)).thenReturn(null);
        when(productVariantRepository.findBySku("")).thenReturn(null);

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceUploadStagedEntity row = capturedStagedRows.get(0);
        assertEquals(ProductImportValidationStatusEn.INVALID, row.validationStatus);
        assertEquals("sku is required", row.validationErrors);

        assertEquals(1, batch.validationErrorCount);
    }

    // ── Test: Malformed data (non-numeric prices) ───────────────────────────

    @Test
    void malformedData_invalidDecimalValues_producesParsingErrors() throws Exception {
        ProductVariantEntity variant = buildVariant("SKU-VALID");
        when(productVariantRepository.findBySku("SKU-VALID")).thenReturn(variant);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-VALID,abc,xyz
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceUploadStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-VALID", row.sku);
        assertEquals(ProductImportValidationStatusEn.INVALID, row.validationStatus);
        // Pin the exact error message format: "Invalid decimal value for {header}: {value}"
        assertTrue(row.validationErrors.contains("Invalid decimal value for retail_price: abc"),
                "Expected retail_price parsing error, got: " + row.validationErrors);
        assertTrue(row.validationErrors.contains("Invalid decimal value for wholesale_price: xyz"),
                "Expected wholesale_price parsing error, got: " + row.validationErrors);
        // Errors are joined with "; "
        assertEquals("Invalid decimal value for retail_price: abc; Invalid decimal value for wholesale_price: xyz",
                row.validationErrors);

        assertEquals(2, batch.validationErrorCount);
    }

    @Test
    void malformedData_oneInvalidPrice_producesPartialError() throws Exception {
        ProductVariantEntity variant = buildVariant("SKU-PARTIAL");
        when(productVariantRepository.findBySku("SKU-PARTIAL")).thenReturn(variant);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        // retail_price valid, wholesale_price malformed
        String csv = """
                sku,retail_price,wholesale_price
                SKU-PARTIAL,150.00,not-a-number
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceUploadStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-PARTIAL", row.sku);
        assertEquals(ProductImportValidationStatusEn.INVALID, row.validationStatus);
        assertEquals("Invalid decimal value for wholesale_price: not-a-number", row.validationErrors);
        // retail_price was parsed successfully
        assertEquals(0, new BigDecimal("150.00").compareTo(row.retailPrice));
        // wholesale_price is null because parsing failed
        assertNull(row.wholesalePrice);

        assertEquals(1, batch.validationErrorCount);
    }

    // ── Test: Empty file ────────────────────────────────────────────────────

    @Test
    void emptyFile_headerOnly_zeroRowsStagedBatchPending() throws Exception {
        String csv = """
                sku,retail_price,wholesale_price
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        // No rows staged
        assertTrue(capturedStagedRows.isEmpty());

        // Batch: PENDING, totalRows=0, validationErrorCount=0
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
        assertEquals(0, batch.totalRows);
        assertEquals(0, batch.validationErrorCount);
    }

    @Test
    void emptyFile_completelyEmpty_zeroRowsStagedBatchPending() throws Exception {
        String csv = "";

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertTrue(capturedStagedRows.isEmpty());
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
        assertEquals(0, batch.totalRows);
        assertEquals(0, batch.validationErrorCount);
    }

    // ── Test: getProductPriceImportRows DTO mapping ─────────────────────────

    @Test
    void getProductPriceImportRows_mapsStageEntitiesToComparisonDtos() {
        // Arrange: pre-built staged entities returned by the repository
        ProductPriceUploadStagedEntity staged1 = new ProductPriceUploadStagedEntity();
        staged1.id = UUID.randomUUID();
        staged1.sku = "SKU-A";
        staged1.retailPrice = new BigDecimal("199.99");
        staged1.wholesalePrice = new BigDecimal("149.99");
        staged1.currentRetailPrice = new BigDecimal("180.00");
        staged1.currentWholesalePrice = new BigDecimal("140.00");
        staged1.validationStatus = ProductImportValidationStatusEn.VALID;
        staged1.validationErrors = null;
        staged1.hasChanges = true;

        ProductPriceUploadStagedEntity staged2 = new ProductPriceUploadStagedEntity();
        staged2.id = UUID.randomUUID();
        staged2.sku = "SKU-B";
        staged2.retailPrice = null;
        staged2.wholesalePrice = new BigDecimal("50.00");
        staged2.currentRetailPrice = null;
        staged2.currentWholesalePrice = null;
        staged2.validationStatus = ProductImportValidationStatusEn.INVALID;
        staged2.validationErrors = "Invalid decimal value for retail_price: bad";
        staged2.hasChanges = false;

        when(productPriceUploadStagedRepository.findByBatchId(batchId))
                .thenReturn(List.of(staged1, staged2));

        // Act
        List<ProductPriceComparisonDto> result = productPriceImportService.getProductPriceImportRows(batchId);

        // Assert: pins the DTO construction
        assertEquals(2, result.size());

        ProductPriceComparisonDto dto1 = result.get(0);
        assertEquals(staged1.id, dto1.stagedId);
        assertEquals("SKU-A", dto1.sku);
        assertEquals(0, new BigDecimal("199.99").compareTo(dto1.proposedRetailPrice));
        assertEquals(0, new BigDecimal("149.99").compareTo(dto1.proposedWholesalePrice));
        assertEquals(0, new BigDecimal("180.00").compareTo(dto1.currentRetailPrice));
        assertEquals(0, new BigDecimal("140.00").compareTo(dto1.currentWholesalePrice));
        assertEquals(ProductImportValidationStatusEn.VALID, dto1.validationStatus);
        assertNull(dto1.validationErrors);
        assertTrue(dto1.hasChanges);

        ProductPriceComparisonDto dto2 = result.get(1);
        assertEquals(staged2.id, dto2.stagedId);
        assertEquals("SKU-B", dto2.sku);
        assertNull(dto2.proposedRetailPrice);
        assertEquals(0, new BigDecimal("50.00").compareTo(dto2.proposedWholesalePrice));
        assertNull(dto2.currentRetailPrice);
        assertNull(dto2.currentWholesalePrice);
        assertEquals(ProductImportValidationStatusEn.INVALID, dto2.validationStatus);
        assertEquals("Invalid decimal value for retail_price: bad", dto2.validationErrors);
        assertFalse(dto2.hasChanges);
    }

    // ── Test: Mixed valid and invalid rows preserve order ───────────────────

    @Test
    void mixedRows_errorOrderAndContentPreserved() throws Exception {
        ProductVariantEntity variant1 = buildVariant("GOOD-SKU");
        when(productVariantRepository.findBySku("GOOD-SKU")).thenReturn(variant1);
        when(productVariantRepository.findBySku("BAD-SKU")).thenReturn(null);
        when(productVariantRepository.findBySku("")).thenReturn(null);

        when(VariantPricesEntity.findLatestByVariantAndType(variant1.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(VariantPricesEntity.findLatestByVariantAndType(variant1.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                GOOD-SKU,100.00,80.00
                BAD-SKU,50.00,40.00
                ,200.00,150.00
                GOOD-SKU,not_valid,60.00
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(4, capturedStagedRows.size());

        // Row 1: GOOD-SKU, valid
        assertEquals("GOOD-SKU", capturedStagedRows.get(0).sku);
        assertEquals(ProductImportValidationStatusEn.VALID, capturedStagedRows.get(0).validationStatus);

        // Row 2: BAD-SKU, invalid (unknown)
        assertEquals("BAD-SKU", capturedStagedRows.get(1).sku);
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(1).validationStatus);
        assertEquals("variant with sku 'BAD-SKU' not found", capturedStagedRows.get(1).validationErrors);

        // Row 3: empty SKU, invalid
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(2).validationStatus);
        assertEquals("sku is required", capturedStagedRows.get(2).validationErrors);

        // Row 4: GOOD-SKU but malformed retail_price
        assertEquals("GOOD-SKU", capturedStagedRows.get(3).sku);
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(3).validationStatus);
        assertEquals("Invalid decimal value for retail_price: not_valid", capturedStagedRows.get(3).validationErrors);

        // Batch totals
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
        assertEquals(4, batch.totalRows);
        // Validation errors: row2 has 1, row3 has 1, row4 has 1 = 3 total
        assertEquals(3, batch.validationErrorCount);
    }

    // ── Test: Blank prices default to BigDecimal.ZERO ───────────────────────

    @Test
    void blankPrices_defaultToZero() throws Exception {
        ProductVariantEntity variant = buildVariant("SKU-BLANK");
        when(productVariantRepository.findBySku("SKU-BLANK")).thenReturn(variant);

        VariantPricesEntity existingRetail = buildPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(VariantPricesEntity.findLatestByVariantAndType(variant.id, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-BLANK,,
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceUploadStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-BLANK", row.sku);
        // Blank values are parsed as BigDecimal.ZERO by CsvImportUtils.parseBigDecimal
        assertEquals(0, BigDecimal.ZERO.compareTo(row.retailPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.wholesalePrice));
        assertEquals(ProductImportValidationStatusEn.VALID, row.validationStatus);
        // retail changed from 50→0, wholesale changed from null→0
        assertTrue(row.hasChanges);
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private ProductVariantEntity buildVariant(String sku) {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = UUID.randomUUID();
        variant.sku = sku;
        return variant;
    }

    private VariantPricesEntity buildPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal price) {
        VariantPricesEntity priceEntity = new VariantPricesEntity();
        priceEntity.id = UUID.randomUUID();
        priceEntity.variant = variant;
        priceEntity.priceType = priceType;
        priceEntity.price = price;
        return priceEntity;
    }
}
