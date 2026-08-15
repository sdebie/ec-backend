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
import org.ecommerce.backend.mapper.UploadBatchDtoMapper;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.dto.UploadBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceUploadBatchRepository;
import org.ecommerce.common.repository.ProductPriceUploadStagedRepository;
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
public class ProductPriceImportService implements ImportBatchService<ProductPriceComparisonDto, UploadBatchProcessStatusDto, ProductPriceUploadBatchEntity>, AsyncImportOperations
{
    @Inject
    ProductPriceUploadBatchRepository productPriceUploadBatchRepository;

    @Inject
    ProductPriceUploadStagedRepository productPriceUploadStagedRepository;

    @Inject
    org.ecommerce.backend.mapper.ProductPriceComparisonMapper productPriceComparisonMapper;

    @Inject
    UploadBatchDtoMapper uploadBatchDtoMapper;

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

    private final ChunkedImportStateMachine.ChunkImportStrategy<ParsedPriceRow, ProductPriceUploadStagedEntity, ProductPriceUploadBatchEntity> strategy =
            new ChunkedImportStateMachine.ChunkImportStrategy<>()
            {

                @Override
                public ProductPriceUploadBatchEntity loadBatch(UUID batchId)
                {
                    return getRequiredProductPriceBatch(batchId);
                }

                @Override
                public int stageRow(ProductPriceUploadBatchEntity batch, ParsedPriceRow row)
                {
                    ProductPriceUploadStagedEntity staged = new ProductPriceUploadStagedEntity();
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

                    productPriceUploadStagedRepository.persist(staged);
                    return result.validationErrors().size();
                }

                @Override
                public List<ProductPriceUploadStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit)
                {
                    return productPriceUploadStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
                }

                @Override
                public boolean isValid(ProductPriceUploadStagedEntity staged)
                {
                    return staged.getValidationStatus() == ProductImportValidationStatusEn.VALID;
                }

                @Override
                public void applyRow(ProductPriceUploadStagedEntity staged)
                {
                    applyValidProductPriceStagedRow(staged);
                }

                @Override
                public void markProcessed(ProductPriceUploadStagedEntity staged)
                {
                    staged.setProcessed(true);
                }

                @Override
                public long countByBatchId(UUID batchId)
                {
                    return productPriceUploadStagedRepository.countByBatchId(batchId);
                }

                @Override
                public long countProcessedValidByBatchId(UUID batchId)
                {
                    return productPriceUploadStagedRepository.countProcessedValidByBatchId(batchId);
                }

                @Override
                public long countProcessedInvalidByBatchId(UUID batchId)
                {
                    return productPriceUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
                }

                @Override
                public Integer getTotalRows(ProductPriceUploadBatchEntity batch)
                {
                    return batch.getTotalRows();
                }

                @Override
                public void setTotalRows(ProductPriceUploadBatchEntity batch, Integer value)
                {
                    batch.setTotalRows(value);
                }

                @Override
                public Integer getProcessedRows(ProductPriceUploadBatchEntity batch)
                {
                    return batch.getProcessedRows();
                }

                @Override
                public void setProcessedRows(ProductPriceUploadBatchEntity batch, Integer value)
                {
                    batch.setProcessedRows(value);
                }

                @Override
                public Integer getSkippedRows(ProductPriceUploadBatchEntity batch)
                {
                    return batch.getSkippedRows();
                }

                @Override
                public void setSkippedRows(ProductPriceUploadBatchEntity batch, Integer value)
                {
                    batch.setSkippedRows(value);
                }

                @Override
                public Integer getValidationErrorCount(ProductPriceUploadBatchEntity batch)
                {
                    return batch.getValidationErrorCount();
                }

                @Override
                public void setValidationErrorCount(ProductPriceUploadBatchEntity batch, Integer value)
                {
                    batch.setValidationErrorCount(value);
                }
            };

    @Override
    public ProductPriceUploadBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin)
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
    public UploadBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId)
    {
        return getProductPriceImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductPriceComparisonDto> getImportRows(UUID batchId)
    {
        return getProductPriceImportRows(batchId);
    }

    @Override
    public List<ProductUploadBatchDto> getUploadBatches()
    {
        return getProductPriceUploadBatches();
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
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Price Batch is already processing");
        }

        long totalRows = productPriceUploadStagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setApprovedBy(approvedBy);
    }

    @Transactional
    public ProductPriceUploadBatchEntity createProductPriceImportPendingBatch(String filename, StaffUserEntity admin)
    {
        ProductPriceUploadBatchEntity batch = new ProductPriceUploadBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productPriceUploadBatchRepository.persist(batch);
        return batch;
    }

    @Transactional
    public void markProductPriceBatchAsFailed(UUID batchId)
    {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.FAILED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Transactional
    public void markProductPriceBatchAsProcessed(UUID batchId)
    {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Transactional(value = Transactional.TxType.SUPPORTS)
    public UploadBatchProcessStatusDto getProductPriceImportBatchProcessStatus(UUID batchId)
    {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

        UploadBatchProcessStatusDto status = new UploadBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().name() : null);
        status.setTotalRows(batch.getTotalRows());
        status.setStagedRows(productPriceUploadStagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount());
        status.setCompleted(batch.getProductUploadStatusEn() != ProductUploadStatusEn.PROCESSING);
        return status;
    }

    private void completeProductPriceCsvUpload(UUID batchId, int totalRows, int validationErrorCount)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
            batch.setTotalRows(totalRows);
            batch.setValidationErrorCount(validationErrorCount);
            batch.setProductUploadStatusEn(ProductUploadStatusEn.PENDING);
        });
    }

    private ProductPriceUploadBatchEntity getRequiredProductPriceBatch(UUID batchId)
    {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        return batch;
    }

    private void applyValidProductPriceStagedRow(ProductPriceUploadStagedEntity staged)
    {

        ProductVariantEntity variant = productVariantRepository.findBySku(staged.getSku());

        if (variant == null) {
            //TODO::SDB ERRRO
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
        return productPriceComparisonMapper.toDtos(productPriceUploadStagedRepository.findByBatchId(batchId));
    }

    public List<ProductUploadBatchDto> getProductPriceUploadBatches()
    {
        List<ProductPriceUploadBatchEntity> batches = productPriceUploadBatchRepository.listAll();
        return batches.stream().map(uploadBatchDtoMapper::fromProductPriceBatch).collect(Collectors.toList());
    }

}
