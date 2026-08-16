package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 4: Import decomposition preserves error accumulation

import org.ecommerce.backend.csv.ProductImportValidator;
import org.ecommerce.backend.csv.ProductImportParser;
import org.ecommerce.backend.csv.ProductImportParser.StagedProductCsvRow;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Example-based test verifying that the decomposed import pipeline
 * (parser → validator → applyValidationResults) produces the same error output
 * (content, order, joining format) as the pre-refactor monolithic service.
 *
 * <p>This test explicitly calls the parser and validator as separate steps
 * (NOT through the service), proving the decomposition itself is correct.
 * It covers representative import files: all-valid, some-invalid, malformed,
 * unknown-SKU, and empty.
 *
 */
class ProductImportErrorAccumulationEquivalenceTest
{
    private ProductImportParser parser;
    private ProductImportValidator validator;

    private CategoryRepository categoryRepository;
    private BrandRepository brandRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository productVariantRepository;
    private ProductImageRepository productImageRepository;

    @BeforeEach
    void setUp() throws Exception
    {
        parser = new ProductImportParser();
        validator = new ProductImportValidator();

        categoryRepository = mock(CategoryRepository.class);
        brandRepository = mock(BrandRepository.class);
        productRepository = mock(ProductRepository.class);
        productVariantRepository = mock(ProductVariantRepository.class);
        productImageRepository = mock(ProductImageRepository.class);

        // Wire repositories into validator via reflection (same approach as characterization tests)
        setField(validator, "categoryRepository", categoryRepository);
        setField(validator, "brandRepository", brandRepository);
        setField(validator, "productRepository", productRepository);
        setField(validator, "productVariantRepository", productVariantRepository);
        setField(validator, "productImageRepository", productImageRepository);
        setField(validator, "storagePath", "/tmp/test-storage");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: All-valid rows — no errors accumulated
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: all-valid rows produce VALID status and null errors for each row")
    void allValidRows_noErrorsAccumulated() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,10,nike,,\n"
                + "tee-2,SKU-B,Tee B,Desc B,apparel,Short B,20,nike,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(2, results.size());
        for (ValidationResult result : results) {
            assertEquals(ProductImportValidationStatusEn.VALID, result.status());
            assertNull(result.errorString());
            assertTrue(result.errors().isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 2: Some-invalid rows — errors pin per row with correct content
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: some-invalid rows produce per-row errors matching monolithic baseline")
    void someInvalidRows_errorsMatchMonolithicBaseline() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(categoryRepository.findBySlugIgnoreCase("bad-cat")).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(brandRepository.findBySlugIgnoreCase("bad-brand")).thenReturn(null);
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row 1: valid, Row 2: unknown category, Row 3: unknown brand
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,10,nike,,\n"
                + "tee-2,SKU-B,Tee B,Desc B,bad-cat,Short B,20,nike,,\n"
                + "tee-3,SKU-C,Tee C,Desc C,apparel,Short C,30,bad-brand,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(3, results.size());

        // Row 1: valid
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(0).status());
        assertNull(results.get(0).errorString());

        // Row 2: unknown category — matches characterization baseline
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(1).status());
        assertEquals("Unknown category: bad-cat", results.get(1).errorString());

        // Row 3: unknown brand — matches characterization baseline
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(2).status());
        assertEquals("Unknown brand: bad-brand", results.get(2).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 3: Malformed row (invalid stock) — parse error carried through
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: malformed stock produces parse error in final error string")
    void malformedStock_parseErrorCarriedThrough() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,INVALID_NUMBER,nike,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());
        assertEquals("Invalid integer value for stock: INVALID_NUMBER", results.get(0).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 4: Parse error + validation errors accumulate together in order
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: parse error + validation errors both accumulate in correct order")
    void parseAndValidationErrors_accumulateInOrder() throws Exception
    {
        when(categoryRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "bad-prod,SKU-BAD,Bad Prod,Desc,bad-cat,Short,XYZ,bad-brand,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());

        // Pin: parse error first (stock), then category, then brand — joined by "; "
        String expected = "Invalid integer value for stock: XYZ; Unknown category: bad-cat; Unknown brand: bad-brand";
        assertEquals(expected, results.get(0).errorString());

        // Verify individual error order
        List<String> errors = results.get(0).errors();
        assertEquals(3, errors.size());
        assertEquals("Invalid integer value for stock: XYZ", errors.get(0));
        assertEquals("Unknown category: bad-cat", errors.get(1));
        assertEquals("Unknown brand: bad-brand", errors.get(2));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 5: Unknown-SKU row (SKU belongs to another product)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: SKU belonging to another product produces conflict error")
    void skuBelongsToAnotherProduct_conflictError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);

        // SKU-CONFLICT belongs to Product A, but the CSV references Product B
        ProductEntity productA = new ProductEntity();
        productA.setId(UUID.randomUUID());
        productA.setName("Product A");
        ProductVariantEntity variantA = new ProductVariantEntity();
        variantA.setId(UUID.randomUUID());
        variantA.setProduct(productA);
        variantA.setStockQuantity(10);
        when(productVariantRepository.findBySku("SKU-CONFLICT")).thenReturn(variantA);
        when(productImageRepository.findByVariantId(variantA.getId())).thenReturn(List.of());

        ProductEntity productB = new ProductEntity();
        productB.setId(UUID.randomUUID());
        productB.setName("Product B");
        productB.setSlug("product-b");
        when(productRepository.findBySlugIgnoreCase("product-b")).thenReturn(productB);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "product-b,SKU-CONFLICT,Product B,Desc,apparel,Short,10,nike,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());
        assertEquals("SKU SKU-CONFLICT already belongs to another product", results.get(0).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 6: Empty file — no rows parsed, no errors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: empty file (header only) produces no results")
    void emptyFile_noResults() throws Exception
    {
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertTrue(results.isEmpty(), "Empty file should produce no validation results");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 7: Multiple errors per row — joining format uses "; " separator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: multiple validation errors joined with '; ' separator")
    void multipleErrors_joinedWithSemicolonSpace() throws Exception
    {
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row with missing category and brand (both null/blank)
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "test-prod,SKU-006,Test,Desc,,Short,10,,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());

        // Pin the joining format: "; " separator, order is category then brand
        assertEquals("category is required; brand is required", results.get(0).errorString());

        List<String> errors = results.get(0).errors();
        assertEquals(2, errors.size());
        assertEquals("category is required", errors.get(0));
        assertEquals("brand is required", errors.get(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 8: Mixed file with valid, invalid, and malformed rows
    //             Proves per-row error isolation in the decomposed pipeline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: mixed file preserves per-row error isolation and order")
    void mixedFile_preservesPerRowIsolation() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(categoryRepository.findBySlugIgnoreCase("bad-cat")).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(brandRepository.findBySlugIgnoreCase("bad-brand")).thenReturn(null);
        when(productVariantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row 1: valid, Row 2: malformed stock, Row 3: unknown category + brand, Row 4: valid
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,10,nike,,\n"
                + "tee-2,SKU-B,Tee B,Desc B,apparel,Short B,BAD_STOCK,nike,,\n"
                + "tee-3,SKU-C,Tee C,Desc C,bad-cat,Short C,5,bad-brand,,\n"
                + "tee-4,SKU-D,Tee D,Desc D,apparel,Short D,15,nike,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(4, results.size());

        // Row 1: valid
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(0).status());
        assertNull(results.get(0).errorString());

        // Row 2: parse error only (stock is malformed but category/brand are valid)
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(1).status());
        assertEquals("Invalid integer value for stock: BAD_STOCK", results.get(1).errorString());

        // Row 3: unknown category + unknown brand
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(2).status());
        assertEquals("Unknown category: bad-cat; Unknown brand: bad-brand", results.get(2).errorString());

        // Row 4: valid
        assertEquals(ProductImportValidationStatusEn.VALID, results.get(3).status());
        assertNull(results.get(3).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 9: Missing required fields produce errors in the correct order
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decomposed pipeline: missing category, brand, and SKU produce errors in order")
    void missingRequiredFields_errorsInOrder() throws Exception
    {
        when(productVariantRepository.findBySku(null)).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row with missing category, brand, and sku (blank fields)
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "test-prod,,Test,Desc,,Short,10,,,\n";

        List<ValidationResult> results = runDecomposedPipeline(csv);

        assertEquals(1, results.size());
        assertEquals(ProductImportValidationStatusEn.INVALID, results.get(0).status());

        // Pin: category required → brand required → sku required
        assertEquals("category is required; brand is required; sku is required",
                results.get(0).errorString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pipeline helper — runs the decomposed steps explicitly
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Runs the decomposed pipeline: parser.parse → validator.validateAndDiff →
     * validator.applyValidationResults. This is the same sequence the orchestrating
     * service performs, but called directly on the extracted collaborators.
     */
    private List<ValidationResult> runDecomposedPipeline(String csv) throws IOException
    {
        InputStream inputStream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<StagedProductCsvRow> parsedRows = parser.parse(inputStream);

        List<ValidationResult> results = new ArrayList<>();
        for (StagedProductCsvRow row : parsedRows) {
            ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
            staged.setProductSlug(row.productSlug());
            staged.setSku(row.sku());
            staged.setName(row.name());
            staged.setDescription(row.description());
            staged.setCategorySlug(normalizeCategorySlugs(row.categorySlug()));
            staged.setShortDescription(row.shortDescription());
            staged.setStock(row.stock());
            staged.setBrandSlug(row.brandSlug());
            staged.setImages(row.images());
            staged.setAttributes(row.attributes());

            // Start with parse errors from the parser step
            List<String> validationErrors = new ArrayList<>(row.validationErrors());

            // Run validation + diff
            validator.validateAndDiff(staged, validationErrors, row.stock(), row.brandSlug(), row.images(), row.attributes());

            // Apply results (sets status + joins errors)
            validator.applyValidationResults(staged, validationErrors);

            results.add(new ValidationResult(
                    staged.getValidationStatus(),
                    staged.getValidationErrors(),
                    List.copyOf(validationErrors)
            ));
        }

        return results;
    }

    /**
     * Replicates the service's normalizeCategorySlugs logic.
     * The service normalizes category slugs before passing to the validator.
     */
    private String normalizeCategorySlugs(String value)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> normalized = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    /**
     * Captures the output of the decomposed pipeline for a single row.
     */
    private record ValidationResult(
            ProductImportValidationStatusEn status,
            String errorString,
            List<String> errors
    )
    {
    }

    private void setField(Object target, String fieldName, Object value) throws Exception
    {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
