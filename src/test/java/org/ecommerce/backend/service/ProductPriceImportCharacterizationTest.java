package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.entity.ProductPriceImportStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link ProductPriceImportService}.
 * <p>
 * Pins the current behaviour of the CSV upload → staging → comparison pipeline:
 * - All-valid price file: rows staged as VALID, correct proposed/current prices, hasChanges flags
 * - Invalid rows: missing/unknown SKUs produce specific validation errors
 * - Malformed data: non-numeric prices produce specific error messages
 * - Empty file: zero rows staged, batch marked PENDING with zero counts
 * <p>
 * These baselines guard against behavioural regression during the upcoming
 * decomposition into parser/validator/orchestrator (tasks 8.2–8.4).
 * <p>
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class ProductPriceImportCharacterizationTest
{
    @Inject
    ProductPriceImportService productPriceImportService;

    @InjectMock
    ProductPriceImportBatchRepository productPriceImportBatchRepository;

    @InjectMock
    ProductPriceImportStagedRepository productPriceImportStagedRepository;

    @InjectMock
    ProductVariantRepository productVariantRepository;

    @InjectMock
    VariantPricesRepository variantPricesRepository;

    private UUID batchId;
    private ProductPriceImportBatchEntity batch;
    private List<ProductPriceImportStagedEntity> capturedStagedRows;

    @BeforeEach
    void setUp()
    {
        batchId = UUID.randomUUID();
        batch = new ProductPriceImportBatchEntity();
        batch.setId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);

        when(productPriceImportBatchRepository.findById(batchId)).thenReturn(batch);

        capturedStagedRows = new ArrayList<>();
        doAnswer(invocation -> {
            ProductPriceImportStagedEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            capturedStagedRows.add(entity);
            return null;
        }).when(productPriceImportStagedRepository).persist(any(ProductPriceImportStagedEntity.class));
    }

    // ── Test: All-valid price file ──────────────────────────────────────────

    @Test
    void allValidPriceFile_stagesRowsAsValid_withCorrectPricesAndChangeDetection() throws Exception
    {
        // Arrange: two known variants with existing prices
        ProductVariantEntity variant1 = buildVariant("SKU-001");
        ProductVariantEntity variant2 = buildVariant("SKU-002");

        when(productVariantRepository.findBySku("SKU-001")).thenReturn(variant1);
        when(productVariantRepository.findBySku("SKU-002")).thenReturn(variant2);

        // variant1 has existing retail=100.00, wholesale=80.00
        VariantPricesEntity existingRetail1 = buildPrice(variant1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        VariantPricesEntity existingWholesale1 = buildPrice(variant1, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));
        when(variantPricesRepository.findLatestByVariantAndType(variant1.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail1);
        when(variantPricesRepository.findLatestByVariantAndType(variant1.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(existingWholesale1);

        // variant2 has existing retail=200.00, no wholesale
        VariantPricesEntity existingRetail2 = buildPrice(variant2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"));
        when(variantPricesRepository.findLatestByVariantAndType(variant2.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail2);
        when(variantPricesRepository.findLatestByVariantAndType(variant2.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

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
        ProductPriceImportStagedEntity row1 = capturedStagedRows.get(0);
        assertEquals("SKU-001", row1.getSku());
        assertEquals(0, new BigDecimal("150.00").compareTo(row1.getRetailPrice()));
        assertEquals(0, new BigDecimal("90.00").compareTo(row1.getWholesalePrice()));
        assertEquals(0, new BigDecimal("100.00").compareTo(row1.getCurrentRetailPrice()));
        assertEquals(0, new BigDecimal("80.00").compareTo(row1.getCurrentWholesalePrice()));
        assertEquals(ProductImportValidationStatusEn.VALID, row1.getValidationStatus());
        assertNull(row1.getValidationErrors());
        assertTrue(row1.getHasChanges(), "hasChanges should be true when prices differ");

        // Row 2: SKU-002, retail unchanged (200→200), wholesale new (null→120)
        ProductPriceImportStagedEntity row2 = capturedStagedRows.get(1);
        assertEquals("SKU-002", row2.getSku());
        assertEquals(0, new BigDecimal("200.00").compareTo(row2.getRetailPrice()));
        assertEquals(0, new BigDecimal("120.00").compareTo(row2.getWholesalePrice()));
        assertEquals(0, new BigDecimal("200.00").compareTo(row2.getCurrentRetailPrice()));
        assertNull(row2.getCurrentWholesalePrice());
        assertEquals(ProductImportValidationStatusEn.VALID, row2.getValidationStatus());
        assertNull(row2.getValidationErrors());
        assertTrue(row2.getHasChanges(), "hasChanges should be true when wholesale is new");

        // Batch final state: PENDING, totalRows=2, validationErrorCount=0
        assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn());
        assertEquals(2, batch.getTotalRows());
        assertEquals(0, batch.getValidationErrorCount());
    }

    @Test
    void allValidPriceFile_noPriceChange_hasChangesFalse() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-SAME");
        when(productVariantRepository.findBySku("SKU-SAME")).thenReturn(variant);

        VariantPricesEntity existingRetail = buildPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        VariantPricesEntity existingWholesale = buildPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(existingWholesale);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-SAME,100.00,80.00
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceImportStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-SAME", row.getSku());
        assertEquals(ProductImportValidationStatusEn.VALID, row.getValidationStatus());
        assertFalse(row.getHasChanges(), "hasChanges should be false when prices are identical");
    }

    // ── Test: Invalid rows (unknown SKU, missing SKU) ───────────────────────

    @Test
    void invalidRows_unknownSku_producesValidationError() throws Exception
    {
        // SKU-KNOWN exists, SKU-UNKNOWN does not
        ProductVariantEntity knownVariant = buildVariant("SKU-KNOWN");
        when(productVariantRepository.findBySku("SKU-KNOWN")).thenReturn(knownVariant);
        when(productVariantRepository.findBySku("SKU-UNKNOWN")).thenReturn(null);

        VariantPricesEntity existingRetail = buildPrice(knownVariant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        when(variantPricesRepository.findLatestByVariantAndType(knownVariant.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(variantPricesRepository.findLatestByVariantAndType(knownVariant.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-KNOWN,55.00,40.00
                SKU-UNKNOWN,99.99,75.00
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(2, capturedStagedRows.size());

        // Row 1: valid
        ProductPriceImportStagedEntity validRow = capturedStagedRows.get(0);
        assertEquals("SKU-KNOWN", validRow.getSku());
        assertEquals(ProductImportValidationStatusEn.VALID, validRow.getValidationStatus());
        assertNull(validRow.getValidationErrors());

        // Row 2: invalid — unknown SKU
        ProductPriceImportStagedEntity invalidRow = capturedStagedRows.get(1);
        assertEquals("SKU-UNKNOWN", invalidRow.getSku());
        assertEquals(ProductImportValidationStatusEn.INVALID, invalidRow.getValidationStatus());
        assertEquals("variant with sku 'SKU-UNKNOWN' not found", invalidRow.getValidationErrors());

        // Batch: totalRows=2, validationErrorCount=1
        assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn());
        assertEquals(2, batch.getTotalRows());
        assertEquals(1, batch.getValidationErrorCount());
    }

    @Test
    void invalidRows_missingSku_producesSkuRequiredError() throws Exception
    {
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
        ProductPriceImportStagedEntity row = capturedStagedRows.get(0);
        assertEquals(ProductImportValidationStatusEn.INVALID, row.getValidationStatus());
        assertEquals("sku is required", row.getValidationErrors());

        assertEquals(1, batch.getValidationErrorCount());
    }

    // ── Test: Malformed data (non-numeric prices) ───────────────────────────

    @Test
    void malformedData_invalidDecimalValues_producesParsingErrors() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-VALID");
        when(productVariantRepository.findBySku("SKU-VALID")).thenReturn(variant);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-VALID,abc,xyz
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceImportStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-VALID", row.getSku());
        assertEquals(ProductImportValidationStatusEn.INVALID, row.getValidationStatus());
        // Pin the exact error message format: "Invalid decimal value for {header}: {value}"
        assertTrue(row.getValidationErrors().contains("Invalid decimal value for retail_price: abc"), "Expected retail_price parsing error, got: " + row.getValidationErrors());
        assertTrue(row.getValidationErrors().contains("Invalid decimal value for wholesale_price: xyz"), "Expected wholesale_price parsing error, got: " + row.getValidationErrors());
        // Errors are joined with "; "
        assertEquals("Invalid decimal value for retail_price: abc; Invalid decimal value for wholesale_price: xyz", row.getValidationErrors());

        assertEquals(2, batch.getValidationErrorCount());
    }

    @Test
    void malformedData_oneInvalidPrice_producesPartialError() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-PARTIAL");
        when(productVariantRepository.findBySku("SKU-PARTIAL")).thenReturn(variant);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        // retail_price valid, wholesale_price malformed
        String csv = """
                sku,retail_price,wholesale_price
                SKU-PARTIAL,150.00,not-a-number
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceImportStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-PARTIAL", row.getSku());
        assertEquals(ProductImportValidationStatusEn.INVALID, row.getValidationStatus());
        assertEquals("Invalid decimal value for wholesale_price: not-a-number", row.getValidationErrors());
        // retail_price was parsed successfully
        assertEquals(0, new BigDecimal("150.00").compareTo(row.getRetailPrice()));
        // wholesale_price is null because parsing failed
        assertNull(row.getWholesalePrice());

        assertEquals(1, batch.getValidationErrorCount());
    }

    // ── Test: Empty file ────────────────────────────────────────────────────

    @Test
    void emptyFile_headerOnly_zeroRowsStagedBatchPending() throws Exception
    {
        String csv = """
                sku,retail_price,wholesale_price
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        // No rows staged
        assertTrue(capturedStagedRows.isEmpty());

        // Batch: PENDING, totalRows=0, validationErrorCount=0
        assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn());
        assertEquals(0, batch.getTotalRows());
        assertEquals(0, batch.getValidationErrorCount());
    }

    @Test
    void emptyFile_completelyEmpty_zeroRowsStagedBatchPending() throws Exception
    {
        String csv = "";

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertTrue(capturedStagedRows.isEmpty());
        assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn());
        assertEquals(0, batch.getTotalRows());
        assertEquals(0, batch.getValidationErrorCount());
    }

    // ── Test: getProductPriceImportRows DTO mapping ─────────────────────────

    @Test
    void getProductPriceImportRows_mapsStageEntitiesToComparisonDtos()
    {
        // Arrange: pre-built staged entities returned by the repository
        ProductPriceImportStagedEntity staged1 = new ProductPriceImportStagedEntity();
        staged1.setId(UUID.randomUUID());
        staged1.setSku("SKU-A");
        staged1.setRetailPrice(new BigDecimal("199.99"));
        staged1.setWholesalePrice(new BigDecimal("149.99"));
        staged1.setCurrentRetailPrice(new BigDecimal("180.00"));
        staged1.setCurrentWholesalePrice(new BigDecimal("140.00"));
        staged1.setValidationStatus(ProductImportValidationStatusEn.VALID);
        staged1.setValidationErrors(null);
        staged1.setHasChanges(true);

        ProductPriceImportStagedEntity staged2 = new ProductPriceImportStagedEntity();
        staged2.setId(UUID.randomUUID());
        staged2.setSku("SKU-B");
        staged2.setRetailPrice(null);
        staged2.setWholesalePrice(new BigDecimal("50.00"));
        staged2.setCurrentRetailPrice(null);
        staged2.setCurrentWholesalePrice(null);
        staged2.setValidationStatus(ProductImportValidationStatusEn.INVALID);
        staged2.setValidationErrors("Invalid decimal value for retail_price: bad");
        staged2.setHasChanges(false);

        when(productPriceImportStagedRepository.findByBatchId(batchId)).thenReturn(List.of(staged1, staged2));

        // Act
        List<ProductPriceComparisonDto> result = productPriceImportService.getProductPriceImportRows(batchId);

        // Assert: pins the DTO construction
        assertEquals(2, result.size());

        ProductPriceComparisonDto dto1 = result.get(0);
        assertEquals(staged1.getId(), dto1.getStagedId());
        assertEquals("SKU-A", dto1.getSku());
        assertEquals(0, new BigDecimal("199.99").compareTo(dto1.getProposedRetailPrice()));
        assertEquals(0, new BigDecimal("149.99").compareTo(dto1.getProposedWholesalePrice()));
        assertEquals(0, new BigDecimal("180.00").compareTo(dto1.getCurrentRetailPrice()));
        assertEquals(0, new BigDecimal("140.00").compareTo(dto1.getCurrentWholesalePrice()));
        assertEquals(ProductImportValidationStatusEn.VALID, dto1.getValidationStatus());
        assertNull(dto1.getValidationErrors());
        assertTrue(dto1.isHasChanges());

        ProductPriceComparisonDto dto2 = result.get(1);
        assertEquals(staged2.getId(), dto2.getStagedId());
        assertEquals("SKU-B", dto2.getSku());
        assertNull(dto2.getProposedRetailPrice());
        assertEquals(0, new BigDecimal("50.00").compareTo(dto2.getProposedWholesalePrice()));
        assertNull(dto2.getCurrentRetailPrice());
        assertNull(dto2.getCurrentWholesalePrice());
        assertEquals(ProductImportValidationStatusEn.INVALID, dto2.getValidationStatus());
        assertEquals("Invalid decimal value for retail_price: bad", dto2.getValidationErrors());
        assertFalse(dto2.isHasChanges());
    }

    // ── Test: Mixed valid and invalid rows preserve order ───────────────────

    @Test
    void mixedRows_errorOrderAndContentPreserved() throws Exception
    {
        ProductVariantEntity variant1 = buildVariant("GOOD-SKU");
        when(productVariantRepository.findBySku("GOOD-SKU")).thenReturn(variant1);
        when(productVariantRepository.findBySku("BAD-SKU")).thenReturn(null);
        when(productVariantRepository.findBySku("")).thenReturn(null);

        when(variantPricesRepository.findLatestByVariantAndType(variant1.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(null);
        when(variantPricesRepository.findLatestByVariantAndType(variant1.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

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
        assertEquals("GOOD-SKU", capturedStagedRows.get(0).getSku());
        assertEquals(ProductImportValidationStatusEn.VALID, capturedStagedRows.get(0).getValidationStatus());

        // Row 2: BAD-SKU, invalid (unknown)
        assertEquals("BAD-SKU", capturedStagedRows.get(1).getSku());
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(1).getValidationStatus());
        assertEquals("variant with sku 'BAD-SKU' not found", capturedStagedRows.get(1).getValidationErrors());

        // Row 3: empty SKU, invalid
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(2).getValidationStatus());
        assertEquals("sku is required", capturedStagedRows.get(2).getValidationErrors());

        // Row 4: GOOD-SKU but malformed retail_price
        assertEquals("GOOD-SKU", capturedStagedRows.get(3).getSku());
        assertEquals(ProductImportValidationStatusEn.INVALID, capturedStagedRows.get(3).getValidationStatus());
        assertEquals("Invalid decimal value for retail_price: not_valid", capturedStagedRows.get(3).getValidationErrors());

        // Batch totals
        assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn());
        assertEquals(4, batch.getTotalRows());
        // Validation errors: row2 has 1, row3 has 1, row4 has 1 = 3 total
        assertEquals(3, batch.getValidationErrorCount());
    }

    // ── Test: Blank prices default to BigDecimal.ZERO ───────────────────────

    @Test
    void blankPrices_defaultToZero() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-BLANK");
        when(productVariantRepository.findBySku("SKU-BLANK")).thenReturn(variant);

        VariantPricesEntity existingRetail = buildPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.RETAIL_PRICE)).thenReturn(existingRetail);
        when(variantPricesRepository.findLatestByVariantAndType(variant.getId(), PriceTypeEn.WHOLESALE_PRICE)).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-BLANK,,
                """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

        assertEquals(1, capturedStagedRows.size());
        ProductPriceImportStagedEntity row = capturedStagedRows.get(0);
        assertEquals("SKU-BLANK", row.getSku());
        // Blank values are parsed as BigDecimal.ZERO by CsvImportUtils.parseBigDecimal
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getRetailPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getWholesalePrice()));
        assertEquals(ProductImportValidationStatusEn.VALID, row.getValidationStatus());
        // retail changed from 50→0, wholesale changed from null→0
        assertTrue(row.getHasChanges());
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private ProductVariantEntity buildVariant(String sku)
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());
        variant.setSku(sku);
        return variant;
    }

    private VariantPricesEntity buildPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal price)
    {
        VariantPricesEntity priceEntity = new VariantPricesEntity();
        priceEntity.setId(UUID.randomUUID());
        priceEntity.setVariant(variant);
        priceEntity.setPriceType(priceType);
        priceEntity.setPrice(price);
        return priceEntity;
    }
}
