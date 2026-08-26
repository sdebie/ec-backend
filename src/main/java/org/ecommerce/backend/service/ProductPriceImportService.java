package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.csv.ProductPriceImportParser;
import org.ecommerce.backend.csv.ProductPriceImportParser.ParsedPriceRow;
import org.ecommerce.backend.csv.ProductPriceImportValidator;
import org.ecommerce.backend.csv.ProductPriceImportValidator.ValidationResult;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.time.LocalDateTime.now;

@ApplicationScoped
public class ProductPriceImportService implements ImportBatchService<ProductPriceComparisonDto, ImportBatchProcessStatusDto, ProductPriceImportBatchEntity>, AsyncImportOperations
{
    @Inject
    ProductPriceImportBatchRepository productPriceImportBatchRepository;

    @Inject
    ProductPriceImportStagedRepository productPriceImportStagedRepository;

    @Inject
    org.ecommerce.backend.mapper.ProductPriceComparisonMapper productPriceComparisonMapper;

    @Inject
    ImportBatchDtoMapper importBatchDtoMapper;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductPriceImportParser productPriceImportParser;

    @Inject
    ProductPriceImportValidator productPriceImportValidator;

    @Inject
    ChunkedImportStateMachine stateMachine;

    private static final Logger LOG = Logger.getLogger(ProductPriceImportService.class);

    private final ChunkedImportStateMachine.ChunkImportStrategy<ParsedPriceRow, ProductPriceImportStagedEntity, ProductPriceImportBatchEntity> strategy =
            new ChunkedImportStateMachine.ChunkImportStrategy<>()
            {

                @Override
                public ProductPriceImportBatchEntity loadBatch(UUID batchId)
                {
                    return getRequiredProductPriceBatch(batchId);
                }

                @Override
                public int stageRow(ProductPriceImportBatchEntity batch, ParsedPriceRow row)
                {
                    ProductPriceImportStagedEntity staged = new ProductPriceImportStagedEntity();
                    staged.setBatch(batch);
                    staged.setSku(row.sku());
                    staged.setRetailPrice(row.retailPrice());
                    staged.setWholesalePrice(row.wholesalePrice());

                    ValidationResult result = productPriceImportValidator.validateAndDiff(row.sku(), row.retailPrice(), row.wholesalePrice(), row.validationErrors());

                    staged.setHasChanges(result.hasChanges());
                    staged.setCurrentRetailPrice(result.currentRetailPrice());
                    staged.setCurrentWholesalePrice(result.currentWholesalePrice());

                    productPriceImportValidator.applyValidationResults(staged, result.validationErrors());
                    if (!result.validationErrors().isEmpty()) {
                        LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.getSku(), staged.getValidationErrors());
                    }

                    productPriceImportStagedRepository.persist(staged);
                    return result.validationErrors().size();
                }

                @Override
                public List<ProductPriceImportStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit)
                {
                    return productPriceImportStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
                }

                @Override
                public boolean isValid(ProductPriceImportStagedEntity staged)
                {
                    return staged.getValidationStatus() == ProductImportValidationStatusEn.VALID;
                }

                @Override
                public void applyRow(ProductPriceImportStagedEntity staged)
                {
                    applyValidProductPriceStagedRow(staged);
                }

                @Override
                public void markProcessed(ProductPriceImportStagedEntity staged)
                {
                    staged.setProcessed(true);
                }

                @Override
                public long countByBatchId(UUID batchId)
                {
                    return productPriceImportStagedRepository.countByBatchId(batchId);
                }

                @Override
                public long countProcessedValidByBatchId(UUID batchId)
                {
                    return productPriceImportStagedRepository.countProcessedValidByBatchId(batchId);
                }

                @Override
                public long countProcessedInvalidByBatchId(UUID batchId)
                {
                    return productPriceImportStagedRepository.countProcessedInvalidByBatchId(batchId);
                }

                @Override
                public Integer getTotalRows(ProductPriceImportBatchEntity batch)
                {
                    return batch.getTotalRows();
                }

                @Override
                public void setTotalRows(ProductPriceImportBatchEntity batch, Integer value)
                {
                    batch.setTotalRows(value);
                }

                @Override
                public Integer getProcessedRows(ProductPriceImportBatchEntity batch)
                {
                    return batch.getProcessedRows();
                }

                @Override
                public void setProcessedRows(ProductPriceImportBatchEntity batch, Integer value)
                {
                    batch.setProcessedRows(value);
                }

                @Override
                public Integer getSkippedRows(ProductPriceImportBatchEntity batch)
                {
                    return batch.getSkippedRows();
                }

                @Override
                public void setSkippedRows(ProductPriceImportBatchEntity batch, Integer value)
                {
                    batch.setSkippedRows(value);
                }

                @Override
                public Integer getValidationErrorCount(ProductPriceImportBatchEntity batch)
                {
                    return batch.getValidationErrorCount();
                }

                @Override
                public void setValidationErrorCount(ProductPriceImportBatchEntity batch, Integer value)
                {
                    batch.setValidationErrorCount(value);
                }
            };

    @Override
    public ProductPriceImportBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin)
    {
        return createProductPriceImportPendingBatch(filename, admin);
    }

    @Override
    public void markImportBatchAsProcessing(UUID batchId)
    {
        markProductPriceImportBatchAsProcessing(batchId, null);
    }

    @Override
    public void markImportBatchAsProcessed(UUID batchId)
    {
        markProductPriceBatchAsProcessed(batchId);
    }

    @Override
    public void markImportBatchAsFailed(UUID batchId)
    {
        markProductPriceBatchAsFailed(batchId);
    }

    @Override
    public ImportBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId)
    {
        return getProductPriceImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductPriceComparisonDto> getImportRows(UUID batchId)
    {
        return getProductPriceImportRows(batchId);
    }

    @Override
    public List<ProductImportBatchDto> getImportBatches()
    {
        return getProductPriceImportBatches();
    }

    @Override
    public void handleCsvUploadForBatch(InputStream is, UUID batchId) throws Exception
    {
        handleProductPriceCsvUploadForBatch(is, batchId);
    }

    @Override
    public void processStagedRowsForBatch(UUID batchId)
    {
        processProductPriceStagedRowsForBatch(batchId);
    }

    @Override
    public void markBatchAsProcessed(UUID batchId)
    {
        markProductPriceBatchAsProcessed(batchId);
    }

    @Override
    public void markBatchAsFailed(UUID batchId)
    {
        markProductPriceBatchAsFailed(batchId);
    }

    public void handleProductPriceCsvUploadForBatch(InputStream is, UUID batchId) throws IOException
    {
        List<ParsedPriceRow> chunk = new ArrayList<>(ChunkedImportStateMachine.STAGING_CHUNK_SIZE);
        int rowCount = 0;
        int validationErrorCount = 0;

        List<ParsedPriceRow> allRows = productPriceImportParser.parseAll(is);

        for (ParsedPriceRow row : allRows) {
            chunk.add(row);
            if (chunk.size() == ChunkedImportStateMachine.STAGING_CHUNK_SIZE) {
                ChunkedImportStateMachine.StagingChunkResult result = stateMachine.stageRowsChunk(batchId, chunk, strategy);
                rowCount += result.rowCount();
                validationErrorCount += result.validationErrorCount();
                chunk = new ArrayList<>(ChunkedImportStateMachine.STAGING_CHUNK_SIZE);
            }
        }

        if (!chunk.isEmpty()) {
            ChunkedImportStateMachine.StagingChunkResult result = stateMachine.stageRowsChunk(batchId, chunk, strategy);
            rowCount += result.rowCount();
            validationErrorCount += result.validationErrorCount();
        }

        completeProductPriceCsvUpload(batchId, rowCount, validationErrorCount);
    }

    public void processProductPriceStagedRowsForBatch(UUID batchId)
    {
        LOG.debug("DEBUG:: Processing Price Batch: " + batchId);
        while (true) {
            int handledRows = stateMachine.processNextStagedChunk(batchId, strategy);
            if (handledRows == 0) {
                break;
            }
        }

        stateMachine.synchronizeBatchProgress(batchId, strategy);
    }

    @Transactional
    public void markProductPriceImportBatchAsProcessing(UUID batchId, StaffUserEntity approvedBy)
    {
        ProductPriceImportBatchEntity batch = productPriceImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Price Batch is already processing");
        }

        long totalRows = productPriceImportStagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setApprovedBy(approvedBy);
    }

    @Transactional
    public ProductPriceImportBatchEntity createProductPriceImportPendingBatch(String filename, StaffUserEntity admin)
    {
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productPriceImportBatchRepository.persist(batch);
        return batch;
    }

    @Transactional
    public void markProductPriceBatchAsFailed(UUID batchId)
    {
        ProductPriceImportBatchEntity batch = productPriceImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.FAILED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Transactional
    public void markProductPriceBatchAsProcessed(UUID batchId)
    {
        ProductPriceImportBatchEntity batch = productPriceImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Transactional(value = Transactional.TxType.SUPPORTS)
    public ImportBatchProcessStatusDto getProductPriceImportBatchProcessStatus(UUID batchId)
    {
        ProductPriceImportBatchEntity batch = productPriceImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

        ImportBatchProcessStatusDto status = new ImportBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().name() : null);
        status.setTotalRows(batch.getTotalRows());
        status.setStagedRows(productPriceImportStagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount());
        status.setCompleted(batch.getProductUploadStatusEn() != ProductUploadStatusEn.PROCESSING);
        return status;
    }

    private void completeProductPriceCsvUpload(UUID batchId, int totalRows, int validationErrorCount)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductPriceImportBatchEntity batch = getRequiredProductPriceBatch(batchId);
            batch.setTotalRows(totalRows);
            batch.setValidationErrorCount(validationErrorCount);
            batch.setProductUploadStatusEn(ProductUploadStatusEn.PENDING);
        });
    }

    private ProductPriceImportBatchEntity getRequiredProductPriceBatch(UUID batchId)
    {
        ProductPriceImportBatchEntity batch = productPriceImportBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        return batch;
    }

    private void applyValidProductPriceStagedRow(ProductPriceImportStagedEntity staged)
    {

        ProductVariantEntity variant = productVariantRepository.findBySku(staged.getSku());

        if (variant == null) {
            // Validation already rejects an unknown SKU, so reaching here means the variant
            // was deleted between validating the batch and applying it. Skipping the row is
            // right — there is nothing left to price — but doing it silently is not: the
            // batch would report every row applied while one was quietly dropped.
            LOG.warnf("Skipped pricing SKU '%s': its variant no longer exists (deleted after validation)",
                    staged.getSku());
            return;
        }

        upsertVariantPrice(variant, PriceTypeEn.RETAIL_PRICE, staged.getRetailPrice());
        upsertVariantPrice(variant, PriceTypeEn.WHOLESALE_PRICE, staged.getWholesalePrice());
    }

    private void upsertVariantPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal priceValue)
    {
        if (variant == null || variant.getId() == null || priceType == null || priceValue == null) {
            return;
        }

        VariantPricesEntity price = variantPricesRepository.findLatestByVariantAndType(variant.getId(), priceType);
        if (price != null) {
            // Expire the current row without altering its historical amount
            price.setPriceEndDate(now());
            variantPricesRepository.persist(price);
        }
        // Create the new price row
        price = new VariantPricesEntity();
        price.setVariant(variant);
        price.setPriceType(priceType);
        price.setPrice(priceValue);
        price.setPriceEndDate(LocalDateTime.of(2099, 1, 1, 0, 0, 0));
        price.setPriceStartDate(now());
        variantPricesRepository.persist(price);
    }

    public List<ProductPriceComparisonDto> getProductPriceImportRows(UUID batchId)
    {
        return productPriceComparisonMapper.toDtos(productPriceImportStagedRepository.findByBatchId(batchId));
    }

    public List<ProductImportBatchDto> getProductPriceImportBatches()
    {
        List<ProductPriceImportBatchEntity> batches = productPriceImportBatchRepository.listAll();
        return batches.stream().map(importBatchDtoMapper::fromProductPriceBatch).collect(Collectors.toList());
    }

}
