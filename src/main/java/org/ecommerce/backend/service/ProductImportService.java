package org.ecommerce.backend.service;

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

import static org.ecommerce.common.util.CsvImportUtils.*;

@ApplicationScoped
public class ProductImportService implements ImportBatchService<ProductComparisonDto, UploadBatchProcessStatusDto, ProductUploadBatchEntity>, AsyncImportOperations
{
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
    public ProductUploadBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin)
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
    public UploadBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId)
    {
        return getProductImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductComparisonDto> getImportRows(UUID batchId)
    {
        return getProductImportRows(batchId);
    }

    @Override
    public List<ProductUploadBatchDto> getUploadBatches()
    {
        return getProductUploadBatches();
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
    public ProductUploadBatchEntity createProductImportPendingBatch(String filename, StaffUserEntity admin)
    {
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productUploadBatchRepository.persist(batch);
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
    public void markProductImportBatchAsProcessing(UUID batchId)
    {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = productUploadStagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
    }

    @Transactional
    public void markProductBatchAsProcessed(UUID batchId)
    {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
    }

    @Transactional
    public void markProductImportBatchAsFailed(UUID batchId)
    {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.FAILED);
    }

    public void processProductStagedRowsForBatch(UUID batchId)
    {
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

    private void completeProductCsvUpload(UUID batchId, int totalRows, int validationErrorCount)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductUploadBatchEntity batch = getRequiredProductBatch(batchId);
            batch.setTotalRows(totalRows);
            batch.setValidationErrorCount(validationErrorCount);
            batch.setProductUploadStatusEn(ProductUploadStatusEn.PENDING);
        });
    }

    private ProductUploadBatchEntity getRequiredProductBatch(UUID batchId)
    {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        return batch;
    }

    private void applyValidProductStagedRow(ProductUploadStagedEntity staged)
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
    public UploadBatchProcessStatusDto getProductImportBatchProcessStatus(UUID batchId)
    {
        ProductUploadBatchEntity batch = productUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        UploadBatchProcessStatusDto status = new UploadBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().name() : null);
        status.setTotalRows(batch.getTotalRows());
        status.setStagedRows(productUploadStagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount());
        status.setCompleted(batch.getProductUploadStatusEn() != ProductUploadStatusEn.PROCESSING);
        return status;
    }

    public List<ProductComparisonDto> getProductImportRows(UUID batchId)
    {
        return productComparisonMapper.toDtos(productUploadStagedRepository.findByBatchId(batchId));
    }

    public List<ProductUploadBatchDto> getProductUploadBatches()
    {
        List<ProductUploadBatchEntity> batches = productUploadBatchRepository.listAllOrderByCreatedAtDesc();
        return batches.stream().map(UploadBatchDtoMapper::fromProductBatch).collect(Collectors.toList());
    }

    private ChunkedImportStateMachine.ChunkImportStrategy<StagedProductCsvRow, ProductUploadStagedEntity, ProductUploadBatchEntity> createStrategy()
    {
        return new ChunkedImportStateMachine.ChunkImportStrategy<>()
        {
            @Override
            public ProductUploadBatchEntity loadBatch(UUID batchId)
            {
                return getRequiredProductBatch(batchId);
            }

            @Override
            public int stageRow(ProductUploadBatchEntity batch, StagedProductCsvRow row)
            {
                ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
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

                productUploadStagedRepository.persist(staged);
                return validationErrors.size();
            }

            @Override
            public List<ProductUploadStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit)
            {
                return productUploadStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
            }

            @Override
            public boolean isValid(ProductUploadStagedEntity staged)
            {
                return staged.getValidationStatus() == ProductImportValidationStatusEn.VALID;
            }

            @Override
            public void applyRow(ProductUploadStagedEntity staged)
            {
                applyValidProductStagedRow(staged);
            }

            @Override
            public void markProcessed(ProductUploadStagedEntity staged)
            {
                staged.setProcessed(true);
            }

            @Override
            public long countByBatchId(UUID batchId)
            {
                return productUploadStagedRepository.countByBatchId(batchId);
            }

            @Override
            public long countProcessedValidByBatchId(UUID batchId)
            {
                return productUploadStagedRepository.countProcessedValidByBatchId(batchId);
            }

            @Override
            public long countProcessedInvalidByBatchId(UUID batchId)
            {
                return productUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
            }

            @Override
            public Integer getTotalRows(ProductUploadBatchEntity batch)
            {
                return batch.getTotalRows();
            }

            @Override
            public void setTotalRows(ProductUploadBatchEntity batch, Integer value)
            {
                batch.setTotalRows(value);
            }

            @Override
            public Integer getProcessedRows(ProductUploadBatchEntity batch)
            {
                return batch.getProcessedRows();
            }

            @Override
            public void setProcessedRows(ProductUploadBatchEntity batch, Integer value)
            {
                batch.setProcessedRows(value);
            }

            @Override
            public Integer getSkippedRows(ProductUploadBatchEntity batch)
            {
                return batch.getSkippedRows();
            }

            @Override
            public void setSkippedRows(ProductUploadBatchEntity batch, Integer value)
            {
                batch.setSkippedRows(value);
            }

            @Override
            public Integer getValidationErrorCount(ProductUploadBatchEntity batch)
            {
                return batch.getValidationErrorCount();
            }

            @Override
            public void setValidationErrorCount(ProductUploadBatchEntity batch, Integer value)
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
