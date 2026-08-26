package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.csv.ProductImportParser;
import org.ecommerce.backend.csv.ProductImportParser.StagedProductCsvRow;
import org.ecommerce.backend.csv.ProductImportValidator;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
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

import static org.ecommerce.common.util.CsvImportUtils.*;

@ApplicationScoped
public class ProductImportService implements ImportBatchService<ProductComparisonDto, ImportBatchProcessStatusDto, ProductImportBatchEntity>, AsyncImportOperations
{
    @Inject
    ProductImportBatchRepository productImportBatchRepository;

    @Inject
    ProductImportStagedRepository productImportStagedRepository;

    @Inject
    org.ecommerce.backend.mapper.ProductComparisonMapper productComparisonMapper;

    @Inject
    ImportBatchDtoMapper importBatchDtoMapper;

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
    public ProductImportBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin)
    {
        return createProductImportPendingBatch(filename, admin);
    }

    @Override
    public void markImportBatchAsProcessing(UUID batchId)
    {
        markProductImportBatchAsProcessing(batchId);
    }

    @Override
    public void markImportBatchAsProcessed(UUID batchId)
    {
        markProductBatchAsProcessed(batchId);
    }

    @Override
    public void markImportBatchAsFailed(UUID batchId)
    {
        markProductImportBatchAsFailed(batchId);
    }

    @Override
    public ImportBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId)
    {
        return getProductImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductComparisonDto> getImportRows(UUID batchId)
    {
        return getProductImportRows(batchId);
    }

    @Override
    public List<ProductImportBatchDto> getImportBatches()
    {
        return getProductImportBatches();
    }

    @Override
    public void processStagedRowsForBatch(UUID batchId)
    {
        processProductStagedRowsForBatch(batchId);
    }

    @Override
    public void markBatchAsProcessed(UUID batchId)
    {
        markProductBatchAsProcessed(batchId);
    }

    @Override
    public void markBatchAsFailed(UUID batchId)
    {
        markProductImportBatchAsFailed(batchId);
    }

    /**
     * Creates and persists the batch record immediately (status=IMPORTING) so the
     * caller can return a batch ID to the client without waiting for CSV parsing.
     */
    @Transactional
    public ProductImportBatchEntity createProductImportPendingBatch(String filename, StaffUserEntity admin)
    {
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productImportBatchRepository.persist(batch);
        return batch;
    }

    /**
     * Parses the CSV and stages all rows for an already-created batch.
     * Marks the batch PENDING when done.
     * Intended to be called from a background thread.
     */
    public void handleCsvUploadForBatch(InputStream is, UUID batchId) throws IOException
    {
        List<StagedProductCsvRow> chunk = new ArrayList<>(ChunkedImportStateMachine.STAGING_CHUNK_SIZE);
        int[] counts = new int[2];
        ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductImportStagedEntity, ProductImportBatchEntity> strategy = createStrategy();

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
    public void markProductImportBatchAsProcessing(UUID batchId)
    {
        ProductImportBatchEntity batch = productImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = productImportStagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
    }

    @Transactional
    public void markProductBatchAsProcessed(UUID batchId)
    {
        ProductImportBatchEntity batch = productImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
    }

    @Transactional
    public void markProductImportBatchAsFailed(UUID batchId)
    {
        ProductImportBatchEntity batch = productImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.FAILED);
    }

    public void processProductStagedRowsForBatch(UUID batchId)
    {
        LOG.debug("DEBUG:: Processing Batch: " + batchId);
        ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductImportStagedEntity, ProductImportBatchEntity> strategy = createStrategy();

        while (true) {
            int handledRows = stateMachine.processNextStagedChunk(batchId, strategy);
            if (handledRows == 0) {
                break;
            }
        }

        stateMachine.synchronizeBatchProgress(batchId, strategy);
    }

    private void completeProductCsvUpload(UUID batchId, int totalRows, int validationErrorCount)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductImportBatchEntity batch = getRequiredProductBatch(batchId);
            batch.setTotalRows(totalRows);
            batch.setValidationErrorCount(validationErrorCount);
            batch.setProductUploadStatusEn(ProductUploadStatusEn.PENDING);
        });
    }

    private ProductImportBatchEntity getRequiredProductBatch(UUID batchId)
    {
        ProductImportBatchEntity batch = productImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        return batch;
    }

    private void applyValidProductStagedRow(ProductImportStagedEntity staged)
    {
        List<CategoryEntity> categories = new ArrayList<>();
        BrandEntity brand = null;

        if (!isBlank(staged.getCategorySlug())) {
            for (String slug : splitCategorySlugs(staged.getCategorySlug())) {
                CategoryEntity category = categoryRepository.findBySlugIgnoreCase(slug);
                if (category != null) {
                    categories.add(category);
                }
            }
        }
        if (!isBlank(staged.getBrandSlug())) {
            brand = brandRepository.findBySlugIgnoreCase(staged.getBrandSlug());
        }

        ProductVariantEntity variant = productVariantRepository.findBySku(staged.getSku());
        ProductEntity product;

        if (variant != null) {
            product = variant.getProduct();
        } else {
            product = findExistingProduct(staged.getProductSlug(), staged.getName());
            if (product == null) {
                product = new ProductEntity();
                product.setSlug(normalizeSlug(staged.getProductSlug()));
                product.setName(staged.getName());
                product.setDescription(staged.getDescription());
                product.setShortDescription(staged.getShortDescription());
                product.setProductType(ProductTypeEn.VARIABLE);
                product.setStatus(ProductStatusEn.ACTIVE);
                productRepository.persist(product);
            }

            variant = new ProductVariantEntity();
            variant.setProduct(product);
            variant.setSku(staged.getSku());
            variant.setStatus(ProductStatusEn.ACTIVE);
            productVariantRepository.persist(variant);
        }

        product.setName(staged.getName().trim());
        product.setDescription(staged.getDescription());
        product.setShortDescription(staged.getShortDescription());

        if (!categories.isEmpty()) {
            product.getCategories().clear();
            product.getCategories().addAll(categories);
        }
        if (brand != null) {
            product.setBrand(brand);
        }

        // Determine product type based on variant count
        int variantCount = (int) productVariantRepository.findByVariantsForProductId(product.getId()).size();
        product.setProductType(variantCount == 1 ? ProductTypeEn.SIMPLE : ProductTypeEn.VARIABLE);

        variant.setStockQuantity(staged.getStock() != null ? staged.getStock() : 0);
        variant.setAttributesJson(trimToNull(staged.getAttributes()));

        upsertVariantImages(variant, staged.getImages());
    }

    private void upsertVariantImages(ProductVariantEntity variant, String stagedImages)
    {
        List<String> imageNames = splitImageNames(stagedImages);
        if (imageNames.isEmpty()) {
            // An empty CSV cell means "leave images unchanged". Bulk image uploads may
            // already have linked images, or may have run before this variant existed.
            // In the latter case retry SKU-based linking now that the variant is present.
            imageService.linkExistingBulkImagesForVariant(variant);
            return;
        }

        productImageRepository.deleteByVariantId(variant.getId());
        for (int i = 0; i < imageNames.size(); i++) {
            ProductImageEntity image = new ProductImageEntity();
            image.setProductVariant(variant);
            image.setImageUrl(imageNames.get(i));
            image.setSortOrder(i);
            image.setIsFeatured(i == 0);
            productImageRepository.persist(image);
        }
    }

    @Transactional(value = TxType.SUPPORTS)
    public ImportBatchProcessStatusDto getProductImportBatchProcessStatus(UUID batchId)
    {
        ProductImportBatchEntity batch = productImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        ImportBatchProcessStatusDto status = new ImportBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().name() : null);
        status.setTotalRows(batch.getTotalRows());
        status.setStagedRows(productImportStagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount());
        status.setCompleted(batch.getProductUploadStatusEn() != ProductUploadStatusEn.PROCESSING);
        return status;
    }

    public List<ProductComparisonDto> getProductImportRows(UUID batchId)
    {
        return productComparisonMapper.toDtos(productImportStagedRepository.findByBatchId(batchId));
    }

    public List<ProductImportBatchDto> getProductImportBatches()
    {
        List<ProductImportBatchEntity> batches = productImportBatchRepository.listAllOrderByCreatedAtDesc();
        return batches.stream().map(importBatchDtoMapper::fromProductBatch).collect(Collectors.toList());
    }

    private ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductImportStagedEntity, ProductImportBatchEntity> createStrategy()
    {
        return new ChunkedImportStateMachine.ChunkImportStrategy<>()
        {
            @Override
            public ProductImportBatchEntity loadBatch(UUID batchId)
            {
                return getRequiredProductBatch(batchId);
            }

            @Override
            public int stageRow(ProductImportBatchEntity batch, StagedProductCsvRow row)
            {
                ProductImportStagedEntity staged = new ProductImportStagedEntity();
                staged.setBatch(batch);
                staged.setProductSlug(row.productSlug());
                staged.setSku(row.sku());
                staged.setName(row.name());
                staged.setDescription(row.description());
                staged.setCategorySlug(normalizeCategorySlugs(row.categorySlug()));
                staged.setShortDescription(row.shortDescription());
                staged.setStock(row.stock());
                staged.setBrandSlug(row.brandSlug());
                // CSVs exported by WordPress commonly use /04/image.jpg. Persist
                // image paths in the one storage-relative form used everywhere:
                // 04/image.jpg. This is also what the image validator resolves.
                staged.setImages(normalizeImagePaths(row.images()));
                staged.setAttributes(row.attributes());

                List<String> validationErrors = new ArrayList<>(row.validationErrors());
                productImportValidator.validateAndDiff(staged, validationErrors, row.stock(), row.brandSlug(), staged.getImages(), staged.getAttributes());
                productImportValidator.validateImages(staged, validationErrors);
                productImportValidator.applyValidationResults(staged, validationErrors);

                if (!validationErrors.isEmpty()) {
                    LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.getSku(), staged.getValidationErrors());
                }

                productImportStagedRepository.persist(staged);
                return validationErrors.size();
            }

            @Override
            public List<ProductImportStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit)
            {
                return productImportStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
            }

            @Override
            public boolean isValid(ProductImportStagedEntity staged)
            {
                return staged.getValidationStatus() == ProductImportValidationStatusEn.VALID;
            }

            @Override
            public void applyRow(ProductImportStagedEntity staged)
            {
                applyValidProductStagedRow(staged);
            }

            @Override
            public void markProcessed(ProductImportStagedEntity staged)
            {
                staged.setProcessed(true);
            }

            @Override
            public long countByBatchId(UUID batchId)
            {
                return productImportStagedRepository.countByBatchId(batchId);
            }

            @Override
            public long countProcessedValidByBatchId(UUID batchId)
            {
                return productImportStagedRepository.countProcessedValidByBatchId(batchId);
            }

            @Override
            public long countProcessedInvalidByBatchId(UUID batchId)
            {
                return productImportStagedRepository.countProcessedInvalidByBatchId(batchId);
            }

            @Override
            public Integer getTotalRows(ProductImportBatchEntity batch)
            {
                return batch.getTotalRows();
            }

            @Override
            public void setTotalRows(ProductImportBatchEntity batch, Integer value)
            {
                batch.setTotalRows(value);
            }

            @Override
            public Integer getProcessedRows(ProductImportBatchEntity batch)
            {
                return batch.getProcessedRows();
            }

            @Override
            public void setProcessedRows(ProductImportBatchEntity batch, Integer value)
            {
                batch.setProcessedRows(value);
            }

            @Override
            public Integer getSkippedRows(ProductImportBatchEntity batch)
            {
                return batch.getSkippedRows();
            }

            @Override
            public void setSkippedRows(ProductImportBatchEntity batch, Integer value)
            {
                batch.setSkippedRows(value);
            }

            @Override
            public Integer getValidationErrorCount(ProductImportBatchEntity batch)
            {
                return batch.getValidationErrorCount();
            }

            @Override
            public void setValidationErrorCount(ProductImportBatchEntity batch, Integer value)
            {
                batch.setValidationErrorCount(value);
            }
        };
    }

    private ProductEntity findExistingProduct(String productSlug, String productName)
    {
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
}
