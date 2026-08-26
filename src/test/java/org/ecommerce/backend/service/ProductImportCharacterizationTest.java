package org.ecommerce.backend.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.ecommerce.backend.csv.ProductImportParser;
import org.ecommerce.backend.csv.ProductImportParser.StagedProductCsvRow;
import org.ecommerce.backend.csv.ProductImportValidator;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for ProductImportService.
 * <p>
 * Pins the current behaviour of CSV parsing, row validation, error accumulation,
 * and successful-row outcomes before the service is decomposed into
 * parser/validator/orchestrator (Tasks 7.2–7.4).
 * <p>
 * Test cases:
 * - All-valid import rows
 * - Some-invalid rows (missing required fields)
 * - Malformed rows (invalid stock values)
 * - Unknown-SKU rows (SKU belongs to another product)
 * - Empty file
 * <p>
 * Requirements: 4.2, 4.4
 */
class ProductImportCharacterizationTest
{
    private ProductImportService service;
    private ProductImportParser parser;
    private ProductImportValidator validator;
    private ProductImportBatchRepository batchRepository;
    private ProductImportStagedRepository stagedRepository;
    private CategoryRepository categoryRepository;
    private BrandRepository brandRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private ProductImageRepository imageRepository;
    private ImageService imageService;

    @BeforeEach
    void setUp() throws Exception
    {
        service = new ProductImportService();
        parser = new ProductImportParser();
        validator = new ProductImportValidator();

        batchRepository = mock(ProductImportBatchRepository.class);
        stagedRepository = mock(ProductImportStagedRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        brandRepository = mock(BrandRepository.class);
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        imageRepository = mock(ProductImageRepository.class);
        imageService = mock(ImageService.class);

        // Wire up the service
        setField(service, "productImportBatchRepository", batchRepository);
        setField(service, "productImportStagedRepository", stagedRepository);
        setField(service, "categoryRepository", categoryRepository);
        setField(service, "brandRepository", brandRepository);
        setField(service, "productRepository", productRepository);
        setField(service, "productVariantRepository", variantRepository);
        setField(service, "productImageRepository", imageRepository);
        setField(service, "imageService", imageService);
        setField(service, "productImportParser", parser);
        setField(service, "productImportValidator", validator);

        // Wire up the validator
        setField(validator, "categoryRepository", categoryRepository);
        setField(validator, "brandRepository", brandRepository);
        setField(validator, "productRepository", productRepository);
        setField(validator, "productVariantRepository", variantRepository);
        setField(validator, "productImageRepository", imageRepository);
        setField(validator, "storagePath", "/tmp/test-storage");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // parseProductCsvRow — pins CSV field mapping and stock parsing
    // (now delegated to ProductImportParser)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseProductCsvRow: valid row maps all fields correctly")
    void parseProductCsvRow_validRow_mapsAllFields() throws Exception
    {
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "blue-tee,SKU-001,Blue Tee,A blue t-shirt,apparel,Short desc,50,nike,img1.jpg,{\"color\":\"blue\"}";

        CSVRecord record = parseSingleRecord(csv);
        StagedProductCsvRow row = parser.parseRow(record);

        assertEquals("blue-tee", row.productSlug());
        assertEquals("SKU-001", row.sku());
        assertEquals("Blue Tee", row.name());
        assertEquals("A blue t-shirt", row.description());
        assertEquals("apparel", row.categorySlug());
        assertEquals("Short desc", row.shortDescription());
        assertEquals(50, row.stock());
        assertEquals("nike", row.brandSlug());
        assertEquals("img1.jpg", row.images());
        assertEquals("{\"color\":\"blue\"}", row.attributes());
        assertTrue(row.validationErrors().isEmpty(), "Valid row should have no parse errors");
    }

    @Test
    @DisplayName("parseProductCsvRow: invalid stock produces parse error")
    void parseProductCsvRow_invalidStock_producesValidationError() throws Exception
    {
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "blue-tee,SKU-001,Blue Tee,A blue t-shirt,apparel,Short desc,NOT_A_NUMBER,nike,img1.jpg,{}";

        CSVRecord record = parseSingleRecord(csv);
        StagedProductCsvRow row = parser.parseRow(record);

        assertNull(row.stock(), "Invalid stock should result in null");
        assertEquals(1, row.validationErrors().size());
        assertEquals("Invalid integer value for stock: NOT_A_NUMBER", row.validationErrors().get(0));
    }

    @Test
    @DisplayName("parseProductCsvRow: blank stock defaults to zero")
    void parseProductCsvRow_blankStock_defaultsToZero() throws Exception
    {
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "blue-tee,SKU-001,Blue Tee,A blue t-shirt,apparel,Short desc,,nike,img1.jpg,{}";

        CSVRecord record = parseSingleRecord(csv);
        StagedProductCsvRow row = parser.parseRow(record);

        assertEquals(0, row.stock(), "Blank stock should default to 0");
        assertTrue(row.validationErrors().isEmpty(), "Blank stock should not produce error");
    }

    @Test
    @DisplayName("parseProductCsvRow: alternate header names are recognized")
    void parseProductCsvRow_alternateHeaders_recognized() throws Exception
    {
        String csv = "product-slug,SKU,Name,description,Category,short_description,stock_quantity,Brand,images,attributes\n"
                + "red-hat,SKU-ALT,Red Hat,Description,hats,Short,25,adidas,hat.png,{}";

        CSVRecord record = parseSingleRecord(csv);
        StagedProductCsvRow row = parser.parseRow(record);

        assertEquals("red-hat", row.productSlug());
        assertEquals("SKU-ALT", row.sku());
        assertEquals("Red Hat", row.name());
        assertEquals("hats", row.categorySlug());
        assertEquals(25, row.stock());
        assertEquals("adidas", row.brandSlug());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // validateAndDiff — pins validation error content and order
    // (now delegated to ProductImportValidator)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateAndDiff: all-valid row with known category and brand produces no errors")
    void validateAndDiff_allValid_noErrors() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");

        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku("SKU-001")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("blue-tee")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Blue Tee")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("blue-tee");
        staged.setSku("SKU-001");
        staged.setName("Blue Tee");
        staged.setDescription("A blue t-shirt");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug("nike");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 50, "nike", "img1.jpg", "{}");

        assertTrue(errors.isEmpty(), "All-valid row should produce no validation errors");
        assertTrue(staged.getIsNewProduct(), "Should be flagged as new product");
        assertTrue(staged.getIsNewVariant(), "Should be flagged as new variant");
        assertTrue(staged.getIsValidCategory());
        assertTrue(staged.getIsValidBrand());
        assertTrue(staged.getHasChanges(), "New product always has changes");
    }

    @Test
    @DisplayName("blank images cell preserves existing associations and retries delayed bulk-image linking")
    void upsertVariantImages_blankImages_preservesAssociationsAndRetriesBulkLinking() throws Exception
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());
        variant.setSku("SKU-001");

        Method method = ProductImportService.class.getDeclaredMethod("upsertVariantImages", ProductVariantEntity.class, String.class);
        method.setAccessible(true);
        method.invoke(service, variant, "   ");

        verify(imageRepository, never()).deleteByVariantId(variant.getId());
        verify(imageRepository, never()).persist(any(ProductImageEntity.class));
        verify(imageService).linkExistingBulkImagesForVariant(variant);
    }

    @Test
    @DisplayName("upsertVariantImages: WordPress-style leading slashes are stored as storage-relative paths")
    void upsertVariantImages_leadingSlashes_areNormalized() throws Exception
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());

        Method method = ProductImportService.class.getDeclaredMethod("upsertVariantImages", ProductVariantEntity.class, String.class);
        method.setAccessible(true);
        method.invoke(service, variant, "/04/first.jpg, /04/second.jpg");

        var images = ArgumentCaptor.forClass(ProductImageEntity.class);
        verify(imageRepository).deleteByVariantId(variant.getId());
        verify(imageRepository, times(2)).persist(images.capture());
        assertEquals(List.of("04/first.jpg", "04/second.jpg"), images.getAllValues().stream().map(image -> image.getImageUrl()).toList());
    }

    @Test
    @DisplayName("validateAndDiff: missing category produces 'category is required' error")
    void validateAndDiff_missingCategory_producesError() throws Exception
    {
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku("SKU-002")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test Prod")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-002");
        staged.setName("Test Prod");
        staged.setCategorySlug(null);

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "nike", "", "");

        assertEquals(1, errors.size());
        assertEquals("category is required", errors.get(0));
    }

    @Test
    @DisplayName("validateAndDiff: missing brand produces 'brand is required' error")
    void validateAndDiff_missingBrand_producesError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(variantRepository.findBySku("SKU-003")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test Prod")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-003");
        staged.setName("Test Prod");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug(null);

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, null, "", "");

        assertEquals(1, errors.size());
        assertEquals("brand is required", errors.get(0));
    }

    @Test
    @DisplayName("validateAndDiff: missing SKU produces 'sku is required' error")
    void validateAndDiff_missingSku_producesError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku(null)).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test Prod")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku(null);
        staged.setName("Test Prod");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug("nike");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "nike", "", "");

        assertEquals(1, errors.size());
        assertEquals("sku is required", errors.get(0));
    }

    @Test
    @DisplayName("validateAndDiff: unknown category produces 'Unknown category: <slug>' error")
    void validateAndDiff_unknownCategory_producesError() throws Exception
    {
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("nonexistent-cat")).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku("SKU-004")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test Prod")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-004");
        staged.setName("Test Prod");
        staged.setCategorySlug("nonexistent-cat");
        staged.setBrandSlug("nike");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "nike", "", "");

        assertEquals(1, errors.size());
        assertEquals("Unknown category: nonexistent-cat", errors.get(0));
        assertFalse(staged.getIsValidCategory());
    }

    @Test
    @DisplayName("validateAndDiff: unknown brand produces 'Unknown brand: <slug>' error")
    void validateAndDiff_unknownBrand_producesError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("unknown-brand")).thenReturn(null);
        when(variantRepository.findBySku("SKU-005")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test Prod")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-005");
        staged.setName("Test Prod");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug("unknown-brand");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "unknown-brand", "", "");

        assertEquals(1, errors.size());
        assertEquals("Unknown brand: unknown-brand", errors.get(0));
        assertFalse(staged.getIsValidBrand());
    }

    @Test
    @DisplayName("validateAndDiff: SKU belonging to another product produces conflict error")
    void validateAndDiff_skuBelongsToAnotherProduct_producesError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);

        // Existing variant belongs to a different product
        ProductEntity otherProduct = new ProductEntity();
        otherProduct.setId(UUID.randomUUID());
        otherProduct.setName("Other Product");

        ProductVariantEntity existingVariant = new ProductVariantEntity();
        existingVariant.setId(UUID.randomUUID());
        existingVariant.setProduct(otherProduct);
        existingVariant.setStockQuantity(10);

        when(variantRepository.findBySku("SKU-CONFLICT")).thenReturn(existingVariant);

        // The product referenced in the CSV row is different
        ProductEntity csvProduct = new ProductEntity();
        csvProduct.setId(UUID.randomUUID());
        csvProduct.setName("CSV Product");
        csvProduct.setSlug("csv-product");
        when(productRepository.findBySlugIgnoreCase("csv-product")).thenReturn(csvProduct);

        when(imageRepository.findByVariantId(existingVariant.getId())).thenReturn(List.of());

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("csv-product");
        staged.setSku("SKU-CONFLICT");
        staged.setName("CSV Product");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug("nike");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "nike", "", "");

        assertEquals(1, errors.size());
        assertEquals("SKU SKU-CONFLICT already belongs to another product", errors.get(0));
    }

    @Test
    @DisplayName("validateAndDiff: SKU exists for new product slug produces error")
    void validateAndDiff_skuExistsForNewProduct_producesError() throws Exception
    {
        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);

        // Variant exists and belongs to a different product
        ProductEntity existingProduct = new ProductEntity();
        existingProduct.setId(UUID.randomUUID());
        existingProduct.setName("Existing Product");

        ProductVariantEntity existingVariant = new ProductVariantEntity();
        existingVariant.setId(UUID.randomUUID());
        existingVariant.setProduct(existingProduct);
        existingVariant.setStockQuantity(5);

        when(variantRepository.findBySku("SKU-EXISTS")).thenReturn(existingVariant);
        // Product slug doesn't match and name doesn't match → isNewProduct = true
        when(productRepository.findBySlugIgnoreCase("new-product")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("New Product")).thenReturn(null);
        when(imageRepository.findByVariantId(existingVariant.getId())).thenReturn(List.of());

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("new-product");
        staged.setSku("SKU-EXISTS");
        staged.setName("New Product");
        staged.setCategorySlug("apparel");
        staged.setBrandSlug("nike");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "nike", "", "");

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("SKU SKU-EXISTS already exists for product"));
        assertTrue(errors.get(0).contains("Existing Product"));
    }

    @Test
    @DisplayName("validateAndDiff: multiple errors accumulate in order — category then brand then SKU")
    void validateAndDiff_multipleErrors_accumulateInOrder() throws Exception
    {
        when(categoryRepository.findBySlugIgnoreCase("bad-cat")).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase("bad-brand")).thenReturn(null);
        when(variantRepository.findBySku("SKU-MULTI")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-MULTI");
        staged.setName("Test");
        staged.setCategorySlug("bad-cat");
        staged.setBrandSlug("bad-brand");

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, "bad-brand", "", "");

        // Pin the error order: category errors come first, then brand errors
        assertEquals(2, errors.size());
        assertEquals("Unknown category: bad-cat", errors.get(0));
        assertEquals("Unknown brand: bad-brand", errors.get(1));
    }

    @Test
    @DisplayName("validateAndDiff: missing both category and brand produces both errors in order")
    void validateAndDiff_missingCategoryAndBrand_bothErrors() throws Exception
    {
        when(variantRepository.findBySku("SKU-006")).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase("test-prod")).thenReturn(null);
        when(productRepository.findByNameIgnoreCase("Test")).thenReturn(null);

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setProductSlug("test-prod");
        staged.setSku("SKU-006");
        staged.setName("Test");
        staged.setCategorySlug(null);
        staged.setBrandSlug(null);

        List<String> errors = new ArrayList<>();
        validator.validateAndDiff(staged, errors, 10, null, "", "");

        // Pin the error order: category required first, then brand required
        assertEquals(2, errors.size());
        assertEquals("category is required", errors.get(0));
        assertEquals("brand is required", errors.get(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // applyValidationResults — pins error aggregation format
    // (now delegated to ProductImportValidator)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("applyValidationResults: no errors sets VALID status and null message")
    void applyValidationResults_noErrors_setsValid() throws Exception
    {
        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        List<String> errors = new ArrayList<>();

        validator.applyValidationResults(staged, errors);

        assertEquals(ProductImportValidationStatusEn.VALID, staged.getValidationStatus());
        assertNull(staged.getValidationErrors());
    }

    @Test
    @DisplayName("applyValidationResults: single error sets INVALID and stores message")
    void applyValidationResults_singleError_setsInvalid() throws Exception
    {
        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        List<String> errors = new ArrayList<>();
        errors.add("Unknown category: apparel");

        validator.applyValidationResults(staged, errors);

        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());
        assertEquals("Unknown category: apparel", staged.getValidationErrors());
    }

    @Test
    @DisplayName("applyValidationResults: multiple errors joined with semicolon-space separator")
    void applyValidationResults_multipleErrors_joinedWithSemicolonSpace() throws Exception
    {
        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        List<String> errors = new ArrayList<>();
        errors.add("category is required");
        errors.add("brand is required");
        errors.add("sku is required");

        validator.applyValidationResults(staged, errors);

        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());
        assertEquals("category is required; brand is required; sku is required", staged.getValidationErrors());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // End-to-end staging — pins full pipeline for representative inputs
    // (orchestration still in ProductImportService, delegates to parser + validator)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: all-valid rows produce VALID status for all")
    void stageChunk_allValidRows_allValid() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,10,nike,,\n"
                + "tee-2,SKU-B,Tee B,Desc B,apparel,Short B,20,nike,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        // Capture persisted staged entities
        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(2, persisted.size());
        for (ProductImportStagedEntity staged : persisted) {
            assertEquals(ProductImportValidationStatusEn.VALID, staged.getValidationStatus());
            assertNull(staged.getValidationErrors());
        }
        assertEquals(2, batch.getTotalRows());
        assertEquals(Integer.valueOf(0), batch.getValidationErrorCount());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: some-invalid rows pin errors per row")
    void stageChunk_someInvalidRows_pinsErrorsPerRow() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(categoryRepository.findBySlugIgnoreCase("bad-cat")).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(brandRepository.findBySlugIgnoreCase("bad-brand")).thenReturn(null);
        when(variantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row 1: valid, Row 2: bad category, Row 3: bad brand
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,10,nike,,\n"
                + "tee-2,SKU-B,Tee B,Desc B,bad-cat,Short B,20,nike,,\n"
                + "tee-3,SKU-C,Tee C,Desc C,apparel,Short C,30,bad-brand,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(3, persisted.size());

        // Row 1: valid
        assertEquals(ProductImportValidationStatusEn.VALID, persisted.get(0).getValidationStatus());
        assertNull(persisted.get(0).getValidationErrors());

        // Row 2: unknown category
        assertEquals(ProductImportValidationStatusEn.INVALID, persisted.get(1).getValidationStatus());
        assertEquals("Unknown category: bad-cat", persisted.get(1).getValidationErrors());

        // Row 3: unknown brand
        assertEquals(ProductImportValidationStatusEn.INVALID, persisted.get(2).getValidationStatus());
        assertEquals("Unknown brand: bad-brand", persisted.get(2).getValidationErrors());

        // Batch error count = errors from row 2 (1) + row 3 (1) = 2
        assertEquals(Integer.valueOf(2), batch.getValidationErrorCount());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: malformed stock row pins parse error carried through")
    void stageChunk_malformedStock_pinsParseErrorCarriedThrough() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row with malformed stock value
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "tee-1,SKU-A,Tee A,Desc A,apparel,Short A,INVALID_NUMBER,nike,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(1, persisted.size());
        ProductImportStagedEntity staged = persisted.get(0);

        // The parse error from parseProductCsvRow is carried through to the final error
        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());
        assertEquals("Invalid integer value for stock: INVALID_NUMBER",
                staged.getValidationErrors());
        assertEquals(Integer.valueOf(1), batch.getValidationErrorCount());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: unknown-SKU row (SKU belongs to another product)")
    void stageChunk_unknownSkuRow_pinsConflictError() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        CategoryEntity category = new CategoryEntity();
        category.setSlug("apparel");
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(categoryRepository.findBySlugIgnoreCase("apparel")).thenReturn(category);
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);

        // SKU-CONFLICT belongs to Product A, but the CSV row references Product B
        ProductEntity productA = new ProductEntity();
        productA.setId(UUID.randomUUID());
        productA.setName("Product A");
        ProductVariantEntity variantA = new ProductVariantEntity();
        variantA.setId(UUID.randomUUID());
        variantA.setProduct(productA);
        variantA.setStockQuantity(10);
        when(variantRepository.findBySku("SKU-CONFLICT")).thenReturn(variantA);
        when(imageRepository.findByVariantId(variantA.getId())).thenReturn(List.of());

        // CSV product slug maps to a different product
        ProductEntity productB = new ProductEntity();
        productB.setId(UUID.randomUUID());
        productB.setName("Product B");
        productB.setSlug("product-b");
        when(productRepository.findBySlugIgnoreCase("product-b")).thenReturn(productB);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "product-b,SKU-CONFLICT,Product B,Desc,apparel,Short,10,nike,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(1, persisted.size());
        ProductImportStagedEntity staged = persisted.get(0);
        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());
        assertEquals("SKU SKU-CONFLICT already belongs to another product",
                staged.getValidationErrors());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: empty file (no data rows) produces no staged entities")
    void stageChunk_emptyFile_noStagedEntities() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        // CSV with header only, no data rows
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);
        assertTrue(parsedRows.isEmpty(), "Empty file should produce no parsed rows");

        // stageChunkInTransaction should not be called with empty list, but verify
        // the batch remains at zero counts
        assertEquals(Integer.valueOf(0), batch.getTotalRows());
        assertEquals(Integer.valueOf(0), batch.getValidationErrorCount());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: multiple categories validated independently")
    void stageChunk_multipleCategorySlugs_validatedIndependently() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        CategoryEntity cat1 = new CategoryEntity();
        cat1.setSlug("shoes");
        when(categoryRepository.findBySlugIgnoreCase("shoes")).thenReturn(cat1);
        when(categoryRepository.findBySlugIgnoreCase("unknown")).thenReturn(null);
        BrandEntity brand = new BrandEntity();
        brand.setSlug("nike");
        when(brandRepository.findBySlugIgnoreCase("nike")).thenReturn(brand);
        when(variantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        // Row with multiple categories where one is unknown
        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "sneaker-1,SKU-X,Sneaker,Desc,\"shoes,unknown\",Short,5,nike,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(1, persisted.size());
        ProductImportStagedEntity staged = persisted.get(0);
        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());
        assertEquals("Unknown category: unknown", staged.getValidationErrors());
    }

    @Test
    @DisplayName("stageProductRowsChunkInTransaction: parse error + validation error both appear in final errors")
    void stageChunk_parseAndValidationErrors_bothAppearInFinalErrors() throws Exception
    {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = createBatch(batchId);
        when(batchRepository.findById(batchId)).thenReturn(batch);

        // Category not found, brand not found, stock malformed
        when(categoryRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(brandRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(variantRepository.findBySku(any())).thenReturn(null);
        when(productRepository.findBySlugIgnoreCase(any())).thenReturn(null);
        when(productRepository.findByNameIgnoreCase(any())).thenReturn(null);

        String csv = "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                + "bad-prod,SKU-BAD,Bad Prod,Desc,bad-cat,Short,XYZ,bad-brand,,\n";

        List<StagedProductCsvRow> parsedRows = parseAllRows(csv);

        List<ProductImportStagedEntity> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return null;
        }).when(stagedRepository).persist(any(ProductImportStagedEntity.class));

        invokeStageChunkInTransaction(batchId, parsedRows);

        assertEquals(1, persisted.size());
        ProductImportStagedEntity staged = persisted.get(0);
        assertEquals(ProductImportValidationStatusEn.INVALID, staged.getValidationStatus());

        // Pin the full error string: parse error (stock) + validation errors (category, brand)
        // Order: parse error carried first, then category, then brand
        String expectedErrors = "Invalid integer value for stock: XYZ; Unknown category: bad-cat; Unknown brand: bad-brand";
        assertEquals(expectedErrors, staged.getValidationErrors());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ══════════════════════════════════════════════════════════════════════════

    private CSVRecord parseSingleRecord(String csv) throws IOException
    {
        try (CSVParser parser = new CSVParser(
                new StringReader(csv),
                CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build())) {
            return parser.getRecords().get(0);
        }
    }

    private List<CSVRecord> parseAllRecords(String csv) throws IOException
    {
        try (CSVParser parser = new CSVParser(
                new StringReader(csv),
                CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build())) {
            return parser.getRecords();
        }
    }

    private List<StagedProductCsvRow> parseAllRows(String csv) throws Exception
    {
        List<CSVRecord> records = parseAllRecords(csv);
        List<StagedProductCsvRow> rows = new ArrayList<>();
        for (CSVRecord record : records) {
            rows.add(parser.parseRow(record));
        }
        return rows;
    }

    private void invokeStageChunkInTransaction(UUID batchId, List<StagedProductCsvRow> rows) throws Exception
    {
        // After the state-machine refactor, stageProductRowsChunkInTransaction no longer
        // exists on ProductImportService. The equivalent logic now lives in
        // ChunkedImportStateMachine.stageRowsChunkInTransaction which delegates to the
        // strategy's stageRow(). We call the service's createStrategy() and replicate
        // the per-row staging + batch counter update that the state machine does.
        Method createStrategyMethod = ProductImportService.class.getDeclaredMethod("createStrategy");
        createStrategyMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductImportStagedEntity, ProductImportBatchEntity> strategy =
                (ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductImportStagedEntity, ProductImportBatchEntity>) createStrategyMethod.invoke(service);

        ProductImportBatchEntity batch = strategy.loadBatch(batchId);
        int validationErrorCount = 0;
        for (StagedProductCsvRow row : rows) {
            validationErrorCount += strategy.stageRow(batch, row);
        }
        // Mirror the state machine's counter update logic
        Integer currentTotal = strategy.getTotalRows(batch);
        strategy.setTotalRows(batch, (currentTotal != null ? currentTotal : 0) + rows.size());
        Integer currentErrors = strategy.getValidationErrorCount(batch);
        strategy.setValidationErrorCount(batch, (currentErrors != null ? currentErrors : 0) + validationErrorCount);
    }

    private ProductImportBatchEntity createBatch(UUID batchId)
    {
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        return batch;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
