package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 4: Import decomposition preserves error accumulation (price import)
// Validates: Requirements 3.2, 4.1, 4.2

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductPriceImportParser.ParsedPriceRow;
import org.ecommerce.common.entity.ProductPriceUploadStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Example-based test verifying that the decomposed price-import pipeline
 * (parser → validator → applyValidationResults) produces the same error output
 * (content, order, joining format) and outcomes as the pre-refactor monolithic service.
 *
 * <p>This test calls the parser and validator directly (NOT through the service),
 * proving the decomposition itself is correct. It covers representative price
 * import files: all-valid, unknown SKU, missing SKU, malformed prices, empty file,
 * and mixed rows.
 *
 * <p><b>Validates: Requirements 3.2, 4.1, 4.2</b>
 */
@QuarkusTest
class ProductPriceImportErrorAccumulationEquivalenceTest
{
    @Inject
    ProductPriceImportParser parser;

    @Inject
    ProductPriceImportValidator validator;

    @InjectMock
    ProductVariantRepository productVariantRepository;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(VariantPricesEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: All-valid rows — no errors accumulated
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: all-valid rows produce VALID status and null errors")
    void allValidRows_noErrorsAccumulated() throws Exception
    {
        ProductVariantEntity variant1 = buildVariant("SKU-001");
        ProductVariantEntity variant2 = buildVariant("SKU-002");

        when(productVariantRepository.findBySku("SKU-001")).thenReturn(variant1);
        when(productVariantRepository.findBySku("SKU-002")).thenReturn(variant2);

        mockLatestPrice(variant1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        mockLatestPrice(variant1, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));
        mockLatestPrice(variant2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"));
        mockLatestPrice(variant2, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-001,150.00,90.00
                SKU-002,200.00,120.00
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(2, results.size());
        for (PipelineResult result : results) {
            assertEquals(ProductImportValidationStatusEn.VALID, result.status());
            assertNull(result.errorString());
            assertTrue(result.errors().isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 2: Unknown SKU — matches characterization baseline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: unknown SKU produces 'variant with sku 'X' not found' error")
    void unknownSku_producesVariantNotFoundError() throws Exception
    {
        ProductVariantEntity knownVariant = buildVariant("SKU-KNOWN");
        when(productVariantRepository.findBySku("SKU-KNOWN")).thenReturn(knownVariant);
        when(productVariantRepository.findBySku("SKU-UNKNOWN")).thenReturn(null);

        mockLatestPrice(knownVariant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        mockLatestPrice(knownVariant, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-KNOWN,55.00,40.00
                SKU-UNKNOWN,99.99,75.00
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(2, results.size());

        // Row 1: valid
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(0).status());
        assertNull(results.get(0).errorString());

        // Row 2: unknown SKU — exact error message matches characterization baseline
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(1).status());
        assertEquals("variant with sku 'SKU-UNKNOWN' not found", results.get(1).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 3: Missing SKU — matches characterization baseline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: missing/blank SKU produces 'sku is required' error")
    void missingSku_producesSkuRequiredError() throws Exception
    {
        String csv = """
                sku,retail_price,wholesale_price
                ,100.00,80.00
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());
        assertEquals("sku is required", results.get(0).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 4: Malformed prices — matches characterization baseline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: malformed prices produce parsing errors joined with '; '")
    void malformedPrices_producesParsingErrors() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-VALID");
        when(productVariantRepository.findBySku("SKU-VALID")).thenReturn(variant);
        mockLatestPrice(variant, PriceTypeEn.RETAIL_PRICE, null);
        mockLatestPrice(variant, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-VALID,abc,xyz
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());
        // Pin exact error format matching characterization baseline
        assertEquals(
                "Invalid decimal value for retail_price: abc; Invalid decimal value for wholesale_price: xyz",
                results.get(0).errorString()
        );

        // Verify individual errors in order
        List<String> errors = results.get(0).errors();
        assertEquals(2, errors.size());
        assertEquals("Invalid decimal value for retail_price: abc", errors.get(0));
        assertEquals("Invalid decimal value for wholesale_price: xyz", errors.get(1));
    }

    @Test
    @DisplayName("Decomposed pipeline: one malformed price produces single parsing error")
    void oneMalformedPrice_producesSingleError() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-PARTIAL");
        when(productVariantRepository.findBySku("SKU-PARTIAL")).thenReturn(variant);
        mockLatestPrice(variant, PriceTypeEn.RETAIL_PRICE, null);
        mockLatestPrice(variant, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-PARTIAL,150.00,not-a-number
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());
        assertEquals("Invalid decimal value for wholesale_price: not-a-number", results.get(0).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 5: Empty file — no rows parsed, no errors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: empty file (header only) produces no results")
    void emptyFile_headerOnly_noResults() throws Exception
    {
        String csv = """
                sku,retail_price,wholesale_price
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertTrue(results.isEmpty(), "Empty file should produce no pipeline results");
    }

    @Test
    @DisplayName("Decomposed pipeline: completely empty input produces no results")
    void emptyFile_completelyEmpty_noResults() throws Exception
    {
        String csv = "";

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertTrue(results.isEmpty(), "Completely empty input should produce no pipeline results");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 6: Mixed file — valid, invalid, malformed rows
    //             Proves per-row error isolation and order in decomposed pipeline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: mixed file preserves per-row error isolation, content, and order")
    void mixedFile_preservesPerRowIsolationAndOrder() throws Exception
    {
        ProductVariantEntity goodVariant = buildVariant("GOOD-SKU");
        when(productVariantRepository.findBySku("GOOD-SKU")).thenReturn(goodVariant);
        when(productVariantRepository.findBySku("BAD-SKU")).thenReturn(null);

        mockLatestPrice(goodVariant, PriceTypeEn.RETAIL_PRICE, null);
        mockLatestPrice(goodVariant, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                GOOD-SKU,100.00,80.00
                BAD-SKU,50.00,40.00
                ,200.00,150.00
                GOOD-SKU,not_valid,60.00
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(4, results.size());

        // Row 1: GOOD-SKU, valid
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(0).status());
        assertNull(results.get(0).errorString());

        // Row 2: BAD-SKU, unknown SKU error
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(1).status());
        assertEquals("variant with sku 'BAD-SKU' not found", results.get(1).errorString());

        // Row 3: empty SKU, sku required error
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(2).status());
        assertEquals("sku is required", results.get(2).errorString());

        // Row 4: GOOD-SKU with malformed retail_price
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(3).status());
        assertEquals("Invalid decimal value for retail_price: not_valid", results.get(3).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 7: Parse errors + validation errors accumulate together
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: parse errors passed to validator accumulate with validation errors")
    void parseAndValidationErrors_accumulateInOrder() throws Exception
    {
        // Malformed retail_price (parse error) + unknown SKU (validation error)
        when(productVariantRepository.findBySku("UNKNOWN")).thenReturn(null);

        String csv = """
                sku,retail_price,wholesale_price
                UNKNOWN,bad_price,50.00
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());

        // Parse error first (from parser), then validation error (from validator) — joined with "; "
        assertEquals(
                "Invalid decimal value for retail_price: bad_price; variant with sku 'UNKNOWN' not found",
                results.get(0).errorString()
        );

        // Verify individual error order: parse error → validation error
        List<String> errors = results.get(0).errors();
        assertEquals(2, errors.size());
        assertEquals("Invalid decimal value for retail_price: bad_price", errors.get(0));
        assertEquals("variant with sku 'UNKNOWN' not found", errors.get(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 8: hasChanges flag correctness in decomposed pipeline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: hasChanges is true when prices differ, false when identical")
    void hasChanges_reflectsPriceDifferences() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-SAME");
        when(productVariantRepository.findBySku("SKU-SAME")).thenReturn(variant);

        mockLatestPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"));
        mockLatestPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"));

        // Same prices as current
        String csvSame = """
                sku,retail_price,wholesale_price
                SKU-SAME,100.00,80.00
                """;

        List<PipelineResult> sameResults = runDecomposedPipeline(csvSame);
        assertEquals(1, sameResults.size());
        assertFalse(sameResults.get(0).hasChanges(), "hasChanges should be false when prices are identical");

        // Different prices
        String csvDiff = """
                sku,retail_price,wholesale_price
                SKU-SAME,150.00,90.00
                """;

        List<PipelineResult> diffResults = runDecomposedPipeline(csvDiff);
        assertEquals(1, diffResults.size());
        assertTrue(diffResults.get(0).hasChanges(), "hasChanges should be true when prices differ");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 9: Blank prices default to BigDecimal.ZERO (parser behaviour)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: blank prices are parsed as ZERO and flagged as changes if current differs")
    void blankPrices_parsedAsZero() throws Exception
    {
        ProductVariantEntity variant = buildVariant("SKU-BLANK");
        when(productVariantRepository.findBySku("SKU-BLANK")).thenReturn(variant);

        mockLatestPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"));
        mockLatestPrice(variant, PriceTypeEn.WHOLESALE_PRICE, null);

        String csv = """
                sku,retail_price,wholesale_price
                SKU-BLANK,,
                """;

        List<PipelineResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(0).status());
        assertNull(results.get(0).errorString());
        // retail changed from 50→0, wholesale changed from null→0
        assertTrue(results.get(0).hasChanges());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pipeline helper — runs the decomposed steps explicitly
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Runs the decomposed price-import pipeline: parser.parseAll → for each row:
     * validator.validateAndDiff → validator.applyValidationResults.
     * This is the same sequence the orchestrating service performs, but called
     * directly on the extracted collaborators.
     */
    private List<PipelineResult> runDecomposedPipeline(String csv) throws IOException
    {
        InputStream inputStream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<ParsedPriceRow> parsedRows = parser.parseAll(inputStream);

        List<PipelineResult> results = new ArrayList<>();
        for (ParsedPriceRow row : parsedRows) {
            // Run validation + diff (validator receives parse errors from the parser)
            ProductPriceImportValidator.ValidationResult validationResult =
                    validator.validateAndDiff(row.sku(), row.retailPrice(), row.wholesalePrice(), row.validationErrors());

            // Apply results to a staged entity (sets status + joins errors)
            ProductPriceUploadStagedEntity staged = new ProductPriceUploadStagedEntity();
            staged.setSku(row.sku());
            staged.setRetailPrice(row.retailPrice());
            staged.setWholesalePrice(row.wholesalePrice());
            staged.setCurrentRetailPrice(validationResult.currentRetailPrice());
            staged.setCurrentWholesalePrice(validationResult.currentWholesalePrice());
            staged.setHasChanges(validationResult.hasChanges());

            validator.applyValidationResults(staged, validationResult.validationErrors());

            results.add(new PipelineResult(
                    staged.getValidationStatus(),
                    staged.getValidationErrors(),
                    List.copyOf(validationResult.validationErrors()),
                    staged.getHasChanges()
            ));
        }

        return results;
    }

    /**
     * Captures the output of the decomposed pipeline for a single row.
     */
    private record PipelineResult(
            ProductImportValidationStatusEn status,
            String errorString,
            List<String> errors,
            Boolean hasChanges
    )
    {
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Test helpers
    // ══════════════════════════════════════════════════════════════════════════

    private ProductVariantEntity buildVariant(String sku)
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());
        variant.setSku(sku);
        return variant;
    }

    private void mockLatestPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal price)
    {
        VariantPricesEntity priceEntity = null;
        if (price != null) {
            priceEntity = new VariantPricesEntity();
            priceEntity.setId(UUID.randomUUID());
            priceEntity.setVariant(variant);
            priceEntity.setPriceType(priceType);
            priceEntity.setPrice(price);
        }
        when(VariantPricesEntity.findLatestByVariantAndType(variant.getId(), priceType)).thenReturn(priceEntity);
    }
}
