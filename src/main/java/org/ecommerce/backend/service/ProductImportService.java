package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.dto.ProductUploadBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.*;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.ecommerce.common.util.CsvImportUtils.getValue;
import static org.ecommerce.common.util.CsvImportUtils.isBlank;

@ApplicationScoped
public class ProductImportService implements ImportBatchService<ProductComparisonDto, ProductUploadBatchProcessStatusDto, ProductUploadBatchEntity>, AsyncImportOperations {

    private static final int STAGING_CHUNK_SIZE = 200;
    private static final int PROCESSING_CHUNK_SIZE = 100;

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    EntityManager entityManager;

    @Inject
    ProductUploadBatchRepository productUploadBatchRepository;

    @Inject
    ProductUploadStagedRepository productUploadStagedRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    BrandRepository brandRepository;

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);

    @Override
    public ProductUploadBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin) {
        return createProductImportPendingBatch(filename, admin);
    }

    @Override
    public void markImportBatchAsProcessing(UUID batchId) {
        markProductImportBatchAsProcessing(batchId);
    }

    @Override
    public void markImportBatchAsProcessed(UUID batchId) {
        markProductBatchAsProcessed(batchId);
    }

    @Override
    public void markImportBatchAsFailed(UUID batchId) {
        markProductImportBatchAsFailed(batchId);
    }

    @Override
    public ProductUploadBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId) {
        return getProductImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductComparisonDto> getImportRows(UUID batchId) {
        return getProductImportRows(batchId);
    }

    @Override
    public List<ProductUploadBatchDto> getUploadBatches() {
        return getProductUploadBatches();
    }

    @Override
    public void processStagedRowsForBatch(UUID batchId) {
        processProductStagedRowsForBatch(batchId);
    }

    @Override
    public void markBatchAsProcessed(UUID batchId) {
        markProductBatchAsProcessed(batchId);
    }

    @Override
    public void markBatchAsFailed(UUID batchId) {
        markProductImportBatchAsFailed(batchId);
    }

    /**
     * Creates and persists the batch record immediately (status=IMPORTING) so the
     * caller can return a batch ID to the client without waiting for CSV parsing.
     */
    @Transactional
    public ProductUploadBatchEntity createProductImportPendingBatch(String filename, StaffUserEntity admin) {
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.filename = filename;
        batch.productUploadStatusEn = ProductUploadStatusEn.IMPORTING;
        batch.uploadedBy = admin;
        batch.totalRows = 0;
        batch.processedRows = 0;
        batch.skippedRows = 0;
        batch.validationErrorCount = 0;
        productUploadBatchRepository.persist(batch);
        return batch;
    }

    /**
     * Parses the CSV and stages all rows for an already-created batch.
     * Marks the batch PENDING when done.
     * Intended to be called from a background thread.
     */
    public void handleCsvUploadForBatch(InputStream is, UUID batchId) throws IOException {
        List<StagedProductCsvRow> chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
        int rowCount = 0;
        int validationErrorCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is));
             CSVParser csvParser = new CSVParser(
                     reader,
                     CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setIgnoreHeaderCase(true)
                             .setTrim(true)
                             .build()
             )) {

            for (CSVRecord record : csvParser) {
                chunk.add(parseProductCsvRow(record));
                if (chunk.size() == STAGING_CHUNK_SIZE) {
                    StagingChunkResult result = stageProductRowsChunk(batchId, chunk);
                    rowCount += result.rowCount();
                    validationErrorCount += result.validationErrorCount();
                    chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
                }
            }
        }

        if (!chunk.isEmpty()) {
            StagingChunkResult result = stageProductRowsChunk(batchId, chunk);
            rowCount += result.rowCount();
            validationErrorCount += result.validationErrorCount();
        }

        completeProductCsvUpload(batchId, rowCount, validationErrorCount);
    }

    @Transactional
    public void markProductImportBatchAsProcessing(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        if (batch.productUploadStatusEn == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = productUploadStagedRepository.countByBatchId(batchId);
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSING;
        batch.totalRows = (int) totalRows;
        batch.processedRows = 0;
        batch.skippedRows = 0;
    }

    @Transactional
    public void markProductBatchAsProcessed(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSED;
    }

    @Transactional
    public void markProductImportBatchAsFailed(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.FAILED;
    }

    public void processProductStagedRowsForBatch(UUID batchId) {
        LOG.debug("DEBUG:: Processing Batch: " + batchId);
        while (true) {
            int handledRows = processNextProductStagedChunk(batchId);
            if (handledRows == 0) {
                break;
            }
        }

        synchronizeProductBatchProgress(batchId);
    }

    private StagedProductCsvRow parseProductCsvRow(CSVRecord record) {
        List<String> validationErrors = new ArrayList<>();
        Integer stock = parseStockInteger(getValue(record, "stock", "stock_quantity"), validationErrors);

        return new StagedProductCsvRow(
                record.getRecordNumber(),
                normalizeSlug(getValue(record, "product_slug", "product-slug")),
                getValue(record, "sku", "SKU"),
                getValue(record, "name", "Name"),
                getValue(record, "description", "description"),
                getValue(record, "category_slug", "Category", "category_name"),
                getValue(record, "short_description", "short_description"),
                stock,
                getValue(record, "brand_slug", "brand_name", "Brand"),
                getValue(record, "images"),
                getValue(record, "attributes"),
                List.copyOf(validationErrors));
    }

    private StagingChunkResult stageProductRowsChunk(UUID batchId, List<StagedProductCsvRow> rows) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageProductRowsChunkInTransaction(batchId, List.copyOf(rows)));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private StagingChunkResult stageProductRowsChunkInTransaction(UUID batchId, List<StagedProductCsvRow> rows) {
        ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
        int validationErrorCount = 0;

        for (StagedProductCsvRow row : rows) {
            ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
            staged.batch = batch;
            staged.productSlug = row.productSlug();
            staged.sku = row.sku();
            staged.name = row.name();
            staged.description = row.description();
            staged.categorySlug = row.categorySlug();
            staged.shortDescription = row.shortDescription();
            staged.stock = row.stock();
            staged.brandSlug = row.brandSlug();
            staged.images = row.images();
            staged.attributes = row.attributes();

            List<String> validationErrors = new ArrayList<>(row.validationErrors());
            validateAndDiff(staged, validationErrors, row.stock(), row.brandSlug(), row.images(), row.attributes());
            validateImages(staged, validationErrors);
            applyValidationResults(staged, validationErrors);
            validationErrorCount += validationErrors.size();

            if (!validationErrors.isEmpty()) {
                LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.sku, staged.validationErrors);
            }

            productUploadStagedRepository.persist(staged);
        }

        batch.totalRows = safeInt(batch.totalRows) + rows.size();
        batch.validationErrorCount = safeInt(batch.validationErrorCount) + validationErrorCount;
        entityManager.flush();
        entityManager.clear();
        return new StagingChunkResult(rows.size(), validationErrorCount);
    }

    private void completeProductCsvUpload(UUID batchId, int totalRows, int validationErrorCount) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
            batch.totalRows = totalRows;
            batch.validationErrorCount = validationErrorCount;
            batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        });
    }

    private int processNextProductStagedChunk(UUID batchId) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> processNextProductStagedChunkInTransaction(batchId));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private int processNextProductStagedChunkInTransaction(UUID batchId) {
        ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
        List<ProductUploadStagedEntity> stagedRows = productUploadStagedRepository.findNextUnprocessedByBatchId(batchId, PROCESSING_CHUNK_SIZE);
        if (stagedRows.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        int skippedCount = 0;

        for (ProductUploadStagedEntity staged : stagedRows) {
            if (staged.validationStatus == ProductImportValidationStatusEn.VALID) {
                applyValidProductStagedRow(staged);
                processedCount++;
            } else {
                skippedCount++;
            }

            staged.processed = true;
        }

        batch.totalRows = batch.totalRows != null ? batch.totalRows : (int) productUploadStagedRepository.countByBatchId(batchId);
        batch.processedRows = safeInt(batch.processedRows) + processedCount;
        batch.skippedRows = safeInt(batch.skippedRows) + skippedCount;

        LOG.debugf("DEBUG:: processed=%d skipped=%d", processedCount, skippedCount);
        entityManager.flush();
        entityManager.clear();
        return stagedRows.size();
    }

    private void synchronizeProductBatchProgress(UUID batchId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
            long totalRows = productUploadStagedRepository.countByBatchId(batchId);
            long totalProcessedRows = productUploadStagedRepository.countProcessedValidByBatchId(batchId);
            long totalSkippedRows = productUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
            batch.totalRows = (int) totalRows;
            batch.processedRows = (int) totalProcessedRows;
            batch.skippedRows = (int) totalSkippedRows;
        });
    }

    private ProductUploadBatchEntity getRequiredProductBatch(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        return batch;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private void applyValidProductStagedRow(ProductUploadStagedEntity staged) {
        CategoryEntity category = null;
        BrandEntity brand = null;

        if (!isBlank(staged.categorySlug)) {
            category = categoryRepository.findBySlugIgnoreCase(staged.categorySlug);
        }
        if (!isBlank(staged.brandSlug)) {
            brand = brandRepository.findBySlugIgnoreCase(staged.brandSlug);
        }

        ProductVariantEntity variant = productVariantRepository.findBySku(staged.sku);
        ProductEntity product;

        if (variant != null) {
            product = variant.product;
        } else {
            product = findExistingProduct(staged.productSlug, staged.name);
            if (product == null) {
                product = new ProductEntity();
                product.slug = normalizeSlug(staged.productSlug);
                product.name = staged.name;
                product.description = staged.description;
                product.shorDescription = staged.shortDescription;
                product.productType = ProductTypeEn.VARIABLE;
                productRepository.persist(product);
            }

            variant = new ProductVariantEntity();
            variant.product = product;
            variant.sku = staged.sku;
            productVariantRepository.persist(variant);
        }

        product.name = staged.name.trim();
        product.description = staged.description;
        product.shorDescription = staged.shortDescription;

        if (category != null) {
            product.category = category;
        }
        if (brand != null) {
            product.brand = brand;
        }

        // Determine product type based on variant count
        int variantCount = (int) productVariantRepository.findByVariantsForProductId(product.id).size();
        product.productType = variantCount == 1 ? ProductTypeEn.SIMPLE : ProductTypeEn.VARIABLE;

        variant.stockQuantity = staged.stock != null ? staged.stock : 0;
        variant.attributesJson = trimToNull(staged.attributes);

        upsertVariantImages(variant, staged.images);
    }

    private void upsertVariantImages(ProductVariantEntity variant, String stagedImages) {
        productImageRepository.deleteByVariantId(variant.id);

        List<String> imageNames = splitImageNames(stagedImages);
        for (int i = 0; i < imageNames.size(); i++) {
            ProductImageEntity image = new ProductImageEntity();
            image.productVariant = variant;
            image.imageUrl = imageNames.get(i);
            image.sortOrder = i;
            image.isFeatured = i == 0;
            productImageRepository.persist(image);
        }
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductUploadBatchProcessStatusDto getProductImportBatchProcessStatus(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        ProductUploadBatchProcessStatusDto status = new ProductUploadBatchProcessStatusDto();
        status.batchId = batch.id;
        status.status = batch.productUploadStatusEn != null ? batch.productUploadStatusEn.name() : null;
        status.totalRows = batch.totalRows;
        status.stagedRows = productUploadStagedRepository.countByBatchId(batchId);
        status.processedRows = batch.processedRows != null ? (long) batch.processedRows : 0L;
        status.skippedRows = batch.skippedRows != null ? (long) batch.skippedRows : 0L;
        status.validationErrorCount = batch.validationErrorCount;
        status.completed = batch.productUploadStatusEn != ProductUploadStatusEn.PROCESSING;
        return status;
    }

    private void validateAndDiff(
            ProductUploadStagedEntity staged,
            List<String> validationErrors,
            Integer stock,
            String brandSlug,
            String imagesValue,
            String attributesJson
    ) {
        CategoryEntity category = findExistingCategory(staged.categorySlug, validationErrors);
        BrandEntity brand = findExistingBrand(brandSlug, validationErrors);
        ProductEntity existingProduct = findExistingProduct(staged.productSlug, staged.name);
        ProductVariantEntity existingVariant = findExistingVariant(staged.sku, validationErrors);

        staged.isValidCategory = category != null;
        staged.isValidBrand = brand != null;

        staged.isNewProduct = existingProduct == null;
        staged.isNewVariant = existingVariant == null;

        //Variant Exist
        if (!staged.isNewVariant) {
            //New Product
            if (staged.isNewProduct) {
                validationErrors.add("SKU " + staged.sku + " already exists for product " + safeProductName(existingVariant));
            } else if (existingVariant.product != null && !Objects.equals(existingVariant.product.id, existingProduct.id)) {
                validationErrors.add("SKU " + staged.sku + " already belongs to another product");
            }
        }

        staged.hasChanges = determineHasChanges(
                staged,
                existingProduct,
                existingVariant,
                stock,
                imagesValue,
                attributesJson
        );

        // Capture current (live) values for comparison display
        if (existingVariant != null) {
            staged.currentStock = existingVariant.stockQuantity;
            staged.currentAttributes = existingVariant.attributesJson;
            List<String> existingImageNames = productImageRepository.findByVariantId(existingVariant.id).stream()
                    .map(img -> extractFileName(img.imageUrl))
                    .filter(name -> !isBlank(name))
                    .toList();
            staged.currentImages = existingImageNames.isEmpty() ? null : String.join(",", existingImageNames);
        }
        if (existingProduct != null) {
            staged.currentName = existingProduct.name;
            staged.currentDescription = existingProduct.description;
            staged.currentShortDescription = existingProduct.shorDescription; // note: typo in ProductEntity field name
        }
    }

    private void validateImages(ProductUploadStagedEntity staged, List<String> validationErrors) {
        if (isBlank(staged.images)) {
            return;
        }

        String[] images = staged.images.split(",");
        List<String> missing = new ArrayList<>();

        for(String fileName :images) {
            java.io.File file = new java.io.File(storagePath, fileName.trim());
            if (!file.exists()) {
                LOG.warnf("Image Not Found %s%s", storagePath, fileName.trim());
                missing.add(fileName.trim());
            }
        }

        if(!missing.isEmpty()) {
            staged.imageErrors = "Missing Images: " + String.join(", ", missing);
            validationErrors.add("Image not foud: " + String.join(", ", missing));
        }
    }

    private CategoryEntity findExistingCategory(String categorySlug, List<String> validationErrors) {
        if (isBlank(categorySlug)) {
            validationErrors.add("category is required");
            return null;
        }

        CategoryEntity category = categoryRepository.findBySlugIgnoreCase(categorySlug);
        if (category == null) {
            validationErrors.add("Unknown category: " + categorySlug.trim());
        }
        return category;
    }

    private BrandEntity findExistingBrand(String brandSlug, List<String> validationErrors) {
        if (isBlank(brandSlug)) {
            validationErrors.add("brand is required");
            return null;
        }

        BrandEntity brand = brandRepository.findBySlugIgnoreCase(brandSlug);
        if (brand == null) {
            validationErrors.add("Unknown brand: " + brandSlug.trim());
            return null;
        }
        return brand;
    }

    private ProductEntity findExistingProduct(String productSlug, String productName) {
        String normalizedSlug = normalizeSlug(productSlug);
        if (normalizedSlug != null) {
            ProductEntity slugMatch = productRepository.findBySlugIgnoreCase(normalizedSlug);
            if (slugMatch != null) {
                return slugMatch;
            }
        }

        if (isBlank(productName)) {
            return null;
        }

        return productRepository.findByNameIgnoreCase(productName);
    }

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors) {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        return productVariantRepository.findBySku(sku);
    }

    private boolean determineHasChanges(
            ProductUploadStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant,
            Integer stock,
            String imagesValue,
            String attributesJson
    ) {
        if (existingProduct == null || existingVariant == null) {
            return true;
        }

        boolean nameChanged = !Objects.equals(trimToNull(staged.name), trimToNull(existingProduct.name));
        boolean productSlugChanged = !Objects.equals(normalizeSlug(staged.productSlug), normalizeSlug(existingProduct.slug));
        boolean categoryChanged = !Objects.equals(trimToNull(staged.categorySlug), trimToNull(existingProduct.category != null ? existingProduct.category.slug : null));
        boolean brandChanged = !Objects.equals(trimToNull(staged.brandSlug), trimToNull(existingProduct.brand != null ? existingProduct.brand.slug : null));
        boolean stockChanged = !Objects.equals(stock, existingVariant.stockQuantity);
        boolean attributesChanged = !Objects.equals(trimToNull(attributesJson), trimToNull(existingVariant.attributesJson));
        boolean imagesChanged = !sameImageNames(imagesValue, existingVariant);

        return nameChanged || productSlugChanged || categoryChanged || brandChanged || stockChanged || attributesChanged || imagesChanged;
    }


    private boolean sameImageNames(String stagedImages, ProductVariantEntity variant) {
        if (variant == null || variant.id == null) {
            return false;
        }

        List<String> existing = productImageRepository.findByVariantId(variant.id).stream()
                .map(img -> extractFileName(img.imageUrl))
                .filter(name -> !isBlank(name))
                .toList();

        List<String> proposed = splitImageNames(stagedImages);
        return existing.equals(proposed);
    }

    private List<String> splitImageNames(String imagesValue) {
        if (isBlank(imagesValue)) {
            return List.of();
        }
        return Arrays.stream(imagesValue.split(","))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private String extractFileName(String imageUrl) {
        String normalized = trimToNull(imageUrl);
        if (normalized == null) {
            return null;
        }
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private String safeProductName(ProductVariantEntity variant) {
        if (variant == null || variant.product == null || isBlank(variant.product.name)) {
            return "<unknown>";
        }
        return variant.product.name;
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String normalizeSlug(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private Integer parseStockInteger(String value, List<String> validationErrors) {
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid integer value for stock: " + value);
            return null;
        }
    }


    public List<ProductComparisonDto> getProductImportRows(UUID batchId) {
        List<ProductUploadStagedEntity> stagedList = productUploadStagedRepository.findByBatchId(batchId);

        return stagedList.stream().map(staged -> {
            ProductComparisonDto dto = new ProductComparisonDto();
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
            dto.proposedName = staged.name;
            dto.proposedDescription = staged.description;
            dto.proposedShortDescription = staged.shortDescription;
            dto.categorySlug = staged.categorySlug;
            dto.brandSlug = staged.brandSlug;
            dto.proposedImages = staged.images;
            dto.proposedStock = staged.stock;
            dto.proposedAttributes = staged.attributes;
            dto.validationErrors = staged.validationErrors;
            dto.validationStatus = staged.validationStatus;
            dto.imageErrors = staged.imageErrors;
            dto.isValidCategory = staged.isValidCategory;
            dto.isValidBrand = staged.isValidBrand;
            dto.isNewProduct = staged.isNewProduct;
            dto.isNewVariant = staged.isNewVariant;
            dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);

            // Use persisted current values captured at import time
            dto.currentName = staged.currentName;
            dto.currentDescription = staged.currentDescription;
            dto.currentShortDescription = staged.currentShortDescription;
            dto.currentStock = staged.currentStock;
            dto.currentImages = staged.currentImages;
            dto.currentAttributes = staged.currentAttributes;

            return dto;
        }).collect(Collectors.toList());
    }

    public List<ProductUploadBatchDto> getProductUploadBatches() {
        List<ProductUploadBatchEntity> batches = productUploadBatchRepository.listAllOrderByCreatedAtDesc();
        return batches.stream().map(UploadBatchDtoMapper::fromProductBatch).collect(Collectors.toList());
    }

    private void applyValidationResults(ProductUploadStagedEntity staged, List<String> validationErrors) {
        staged.validationStatus = validationErrors.isEmpty()
                ? ProductImportValidationStatusEn.VALID
                : ProductImportValidationStatusEn.INVALID;
        staged.validationErrors = validationErrors.isEmpty() ? null : String.join("; ", validationErrors);
    }

    private record StagedProductCsvRow(
            long recordNumber,
            String productSlug,
            String sku,
            String name,
            String description,
            String categorySlug,
            String shortDescription,
            Integer stock,
            String brandSlug,
            String images,
            String attributes,
            List<String> validationErrors
    ) {
    }

    private record StagingChunkResult(int rowCount, int validationErrorCount) {
    }

}
