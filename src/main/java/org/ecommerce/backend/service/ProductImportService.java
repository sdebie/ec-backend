package org.ecommerce.backend.service;

import static org.ecommerce.common.util.CsvImportUtils.normalizeSlug;
import static org.ecommerce.common.util.CsvImportUtils.normalizeCategorySlugs;
import static org.ecommerce.common.util.CsvImportUtils.splitCategorySlugs;
import static org.ecommerce.common.util.CsvImportUtils.splitImageNames;
import static org.ecommerce.common.util.CsvImportUtils.trimToNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.mapper.ProductImportParser;
import org.ecommerce.backend.mapper.ProductImportParser.StagedProductCsvRow;
import org.ecommerce.backend.mapper.ProductImportValidator;
import org.ecommerce.backend.mapper.UploadBatchDtoMapper;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.dto.ProductUploadBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.*;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.ecommerce.common.util.CsvImportUtils.isBlank;

@ApplicationScoped
public class ProductImportService implements ImportBatchService<ProductComparisonDto, ProductUploadBatchProcessStatusDto, ProductUploadBatchEntity>, AsyncImportOperations {

    private static final int STAGING_CHUNK_SIZE = 200;
    private static final int PROCESSING_CHUNK_SIZE = 100;

    @Inject
    EntityManager entityManager;

    @Inject
    ProductUploadBatchRepository productUploadBatchRepository;

    @Inject
    ProductUploadStagedRepository productUploadStagedRepository;

    @Inject
    org.ecommerce.backend.mapper.ProductComparisonMapper productComparisonMapper;

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

    @Inject
    ImageService imageService;

    @Inject
    ProductImportParser productImportParser;

    @Inject
    ProductImportValidator productImportValidator;

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
        int[] counts = new int[2];

        productImportParser.forEachRow(is, row -> {
            chunk.add(row);
            if (chunk.size() == STAGING_CHUNK_SIZE) {
                StagingChunkResult result = stageProductRowsChunk(batchId, chunk);
                counts[0] += result.rowCount();
                counts[1] += result.validationErrorCount();
                chunk.clear();
            }
        });

        if (!chunk.isEmpty()) {
            StagingChunkResult result = stageProductRowsChunk(batchId, chunk);
            counts[0] += result.rowCount();
            counts[1] += result.validationErrorCount();
        }

        completeProductCsvUpload(batchId, counts[0], counts[1]);
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
            staged.categorySlug = normalizeCategorySlugs(row.categorySlug());
            staged.shortDescription = row.shortDescription();
            staged.stock = row.stock();
            staged.brandSlug = row.brandSlug();
            staged.images = row.images();
            staged.attributes = row.attributes();

            List<String> validationErrors = new ArrayList<>(row.validationErrors());
            productImportValidator.validateAndDiff(staged, validationErrors, row.stock(), row.brandSlug(), row.images(), row.attributes());
            productImportValidator.validateImages(staged, validationErrors);
            productImportValidator.applyValidationResults(staged, validationErrors);
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
        List<CategoryEntity> categories = new ArrayList<>();
        BrandEntity brand = null;

        if (!isBlank(staged.categorySlug)) {
            for (String slug : splitCategorySlugs(staged.categorySlug)) {
                CategoryEntity category = categoryRepository.findBySlugIgnoreCase(slug);
                if (category != null) {
                    categories.add(category);
                }
            }
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
                product.status = ProductStatusEn.ACTIVE;
                productRepository.persist(product);
            }

            variant = new ProductVariantEntity();
            variant.product = product;
            variant.sku = staged.sku;
            variant.status = ProductStatusEn.ACTIVE;
            productVariantRepository.persist(variant);
        }

        product.name = staged.name.trim();
        product.description = staged.description;
        product.shorDescription = staged.shortDescription;

        if (!categories.isEmpty()) {
            product.categories.clear();
            product.categories.addAll(categories);
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
        List<String> imageNames = splitImageNames(stagedImages);
        if (imageNames.isEmpty()) {
            // An empty CSV cell means "leave images unchanged". Bulk image uploads may
            // already have linked images, or may have run before this variant existed.
            // In the latter case retry SKU-based linking now that the variant is present.
            imageService.linkExistingBulkImagesForVariant(variant);
            return;
        }

        productImageRepository.deleteByVariantId(variant.id);
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

    public List<ProductComparisonDto> getProductImportRows(UUID batchId) {
        return productComparisonMapper.toDtos(productUploadStagedRepository.findByBatchId(batchId));
    }

    public List<ProductUploadBatchDto> getProductUploadBatches() {
        List<ProductUploadBatchEntity> batches = productUploadBatchRepository.listAllOrderByCreatedAtDesc();
        return batches.stream().map(UploadBatchDtoMapper::fromProductBatch).collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers retained for persistence/orchestration (not parsing or validation)
    // ──────────────────────────────────────────────────────────────────────────

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

    // String normalization/splitting lives in ImportStringUtils (pure helpers).

    private record StagingChunkResult(int rowCount, int validationErrorCount) {
    }
}
