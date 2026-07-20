package org.ecommerce.backend.service;

import static org.ecommerce.common.util.CsvImportUtils.normalizeSlug;
import static org.ecommerce.common.util.CsvImportUtils.normalizeCategorySlugs;
import static org.ecommerce.common.util.CsvImportUtils.normalizeImagePaths;
import static org.ecommerce.common.util.CsvImportUtils.splitCategorySlugs;
import static org.ecommerce.common.util.CsvImportUtils.splitImageNames;
import static org.ecommerce.common.util.CsvImportUtils.trimToNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.mapper.ProductImportParser;
import org.ecommerce.backend.mapper.ProductImportParser.StagedProductCsvRow;
import org.ecommerce.backend.mapper.ProductImportValidator;
import org.ecommerce.backend.mapper.UploadBatchDtoMapper;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.dto.UploadBatchProcessStatusDto;
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
public class ProductImportService implements ImportBatchService<ProductComparisonDto, UploadBatchProcessStatusDto, ProductUploadBatchEntity>, AsyncImportOperations {

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

    @Inject
    ChunkedImportStateMachine stateMachine;

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
    public UploadBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId) {
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
        List<StagedProductCsvRow> chunk = new ArrayList<>(ChunkedImportStateMachine.STAGING_CHUNK_SIZE);
        int[] counts = new int[2];
        ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductUploadStagedEntity, ProductUploadBatchEntity> strategy = createStrategy();

        productImportParser.forEachRow(is, row -> {
            chunk.add(row);
            if (chunk.size() == ChunkedImportStateMachine.STAGING_CHUNK_SIZE) {
                ChunkedImportStateMachine.StagingChunkResult result = stateMachine.stageRowsChunk(batchId, chunk, strategy);
                counts[0] += result.rowCount();
                counts[1] += result.validationErrorCount();
                chunk.clear();
            }
        });

        if (!chunk.isEmpty()) {
            ChunkedImportStateMachine.StagingChunkResult result = stateMachine.stageRowsChunk(batchId, chunk, strategy);
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
        ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductUploadStagedEntity, ProductUploadBatchEntity> strategy = createStrategy();

        while (true) {
            int handledRows = stateMachine.processNextStagedChunk(batchId, strategy);
            if (handledRows == 0) {
                break;
            }
        }

        stateMachine.synchronizeBatchProgress(batchId, strategy);
    }

    private void completeProductCsvUpload(UUID batchId, int totalRows, int validationErrorCount) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
            batch.totalRows = totalRows;
            batch.validationErrorCount = validationErrorCount;
            batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        });
    }

    private ProductUploadBatchEntity getRequiredProductBatch(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        return batch;
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
    public UploadBatchProcessStatusDto getProductImportBatchProcessStatus(UUID batchId) {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        UploadBatchProcessStatusDto status = new UploadBatchProcessStatusDto();
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
    // Strategy for the shared ChunkedImportStateMachine
    // ──────────────────────────────────────────────────────────────────────────

    private ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductUploadStagedEntity, ProductUploadBatchEntity> createStrategy() {
        return new ChunkedImportStateMachine.ChunkImportStrategy<>() {
            @Override
            public ProductUploadBatchEntity loadBatch(UUID batchId) {
                return getRequiredProductBatch(batchId);
            }

            @Override
            public int stageRow(ProductUploadBatchEntity batch, StagedProductCsvRow row) {
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
                // CSVs exported by WordPress commonly use /04/image.jpg. Persist
                // image paths in the one storage-relative form used everywhere:
                // 04/image.jpg. This is also what the image validator resolves.
                staged.images = normalizeImagePaths(row.images());
                staged.attributes = row.attributes();

                List<String> validationErrors = new ArrayList<>(row.validationErrors());
                productImportValidator.validateAndDiff(staged, validationErrors, row.stock(), row.brandSlug(), staged.images, row.attributes());
                productImportValidator.validateImages(staged, validationErrors);
                productImportValidator.applyValidationResults(staged, validationErrors);

                if (!validationErrors.isEmpty()) {
                    LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.sku, staged.validationErrors);
                }

                productUploadStagedRepository.persist(staged);
                return validationErrors.size();
            }

            @Override
            public List<ProductUploadStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit) {
                return productUploadStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
            }

            @Override
            public boolean isValid(ProductUploadStagedEntity staged) {
                return staged.validationStatus == ProductImportValidationStatusEn.VALID;
            }

            @Override
            public void applyRow(ProductUploadStagedEntity staged) {
                applyValidProductStagedRow(staged);
            }

            @Override
            public void markProcessed(ProductUploadStagedEntity staged) {
                staged.processed = true;
            }

            @Override
            public long countByBatchId(UUID batchId) {
                return productUploadStagedRepository.countByBatchId(batchId);
            }

            @Override
            public long countProcessedValidByBatchId(UUID batchId) {
                return productUploadStagedRepository.countProcessedValidByBatchId(batchId);
            }

            @Override
            public long countProcessedInvalidByBatchId(UUID batchId) {
                return productUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
            }

            @Override
            public Integer getTotalRows(ProductUploadBatchEntity batch) {
                return batch.totalRows;
            }

            @Override
            public void setTotalRows(ProductUploadBatchEntity batch, Integer value) {
                batch.totalRows = value;
            }

            @Override
            public Integer getProcessedRows(ProductUploadBatchEntity batch) {
                return batch.processedRows;
            }

            @Override
            public void setProcessedRows(ProductUploadBatchEntity batch, Integer value) {
                batch.processedRows = value;
            }

            @Override
            public Integer getSkippedRows(ProductUploadBatchEntity batch) {
                return batch.skippedRows;
            }

            @Override
            public void setSkippedRows(ProductUploadBatchEntity batch, Integer value) {
                batch.skippedRows = value;
            }

            @Override
            public Integer getValidationErrorCount(ProductUploadBatchEntity batch) {
                return batch.validationErrorCount;
            }

            @Override
            public void setValidationErrorCount(ProductUploadBatchEntity batch, Integer value) {
                batch.validationErrorCount = value;
            }
        };
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
}
