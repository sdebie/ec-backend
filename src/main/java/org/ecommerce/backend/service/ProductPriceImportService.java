package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.mapper.ProductPriceImportParser;
import org.ecommerce.backend.mapper.ProductPriceImportParser.ParsedPriceRow;
import org.ecommerce.backend.mapper.ProductPriceImportValidator;
import org.ecommerce.backend.mapper.ProductPriceImportValidator.ValidationResult;
import org.ecommerce.backend.mapper.UploadBatchDtoMapper;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductPriceUploadBatchProcessStatusDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
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
public class ProductPriceImportService implements ImportBatchService<ProductPriceComparisonDto, ProductPriceUploadBatchProcessStatusDto, ProductPriceUploadBatchEntity>, AsyncImportOperations {

    @Inject
    ProductPriceUploadBatchRepository productPriceUploadBatchRepository;

    @Inject
    ProductPriceUploadStagedRepository productPriceUploadStagedRepository;

    @Inject
    org.ecommerce.backend.mapper.ProductPriceComparisonMapper productPriceComparisonMapper;

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

    // ──────────────────────────────────────────────────────────────────────────
    // Strategy implementation for the shared state machine
    // ──────────────────────────────────────────────────────────────────────────

    private final ChunkedImportStateMachine.ChunkImportStrategy<ParsedPriceRow, ProductPriceUploadStagedEntity, ProductPriceUploadBatchEntity> strategy =
            new ChunkedImportStateMachine.ChunkImportStrategy<>() {

                @Override
                public ProductPriceUploadBatchEntity loadBatch(UUID batchId) {
                    return getRequiredProductPriceBatch(batchId);
                }

                @Override
                public int stageRow(ProductPriceUploadBatchEntity batch, ParsedPriceRow row) {
                    ProductPriceUploadStagedEntity staged = new ProductPriceUploadStagedEntity();
                    staged.batch = batch;
                    staged.sku = row.sku();
                    staged.retailPrice = row.retailPrice();
                    staged.wholesalePrice = row.wholesalePrice();

                    ValidationResult result = productPriceImportValidator.validateAndDiff(
                            row.sku(), row.retailPrice(), row.wholesalePrice(), row.validationErrors());

                    staged.hasChanges = result.hasChanges();
                    staged.currentRetailPrice = result.currentRetailPrice();
                    staged.currentWholesalePrice = result.currentWholesalePrice();

                    productPriceImportValidator.applyValidationResults(staged, result.validationErrors());
                    if (!result.validationErrors().isEmpty()) {
                        LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.sku, staged.validationErrors);
                    }

                    productPriceUploadStagedRepository.persist(staged);
                    return result.validationErrors().size();
                }

                @Override
                public List<ProductPriceUploadStagedEntity> fetchNextUnprocessedChunk(UUID batchId, int limit) {
                    return productPriceUploadStagedRepository.findNextUnprocessedByBatchId(batchId, limit);
                }

                @Override
                public boolean isValid(ProductPriceUploadStagedEntity staged) {
                    return staged.validationStatus == ProductImportValidationStatusEn.VALID;
                }

                @Override
                public void applyRow(ProductPriceUploadStagedEntity staged) {
                    applyValidProductPriceStagedRow(staged);
                }

                @Override
                public void markProcessed(ProductPriceUploadStagedEntity staged) {
                    staged.processed = true;
                }

                @Override
                public long countByBatchId(UUID batchId) {
                    return productPriceUploadStagedRepository.countByBatchId(batchId);
                }

                @Override
                public long countProcessedValidByBatchId(UUID batchId) {
                    return productPriceUploadStagedRepository.countProcessedValidByBatchId(batchId);
                }

                @Override
                public long countProcessedInvalidByBatchId(UUID batchId) {
                    return productPriceUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
                }

                @Override
                public Integer getTotalRows(ProductPriceUploadBatchEntity batch) {
                    return batch.totalRows;
                }

                @Override
                public void setTotalRows(ProductPriceUploadBatchEntity batch, Integer value) {
                    batch.totalRows = value;
                }

                @Override
                public Integer getProcessedRows(ProductPriceUploadBatchEntity batch) {
                    return batch.processedRows;
                }

                @Override
                public void setProcessedRows(ProductPriceUploadBatchEntity batch, Integer value) {
                    batch.processedRows = value;
                }

                @Override
                public Integer getSkippedRows(ProductPriceUploadBatchEntity batch) {
                    return batch.skippedRows;
                }

                @Override
                public void setSkippedRows(ProductPriceUploadBatchEntity batch, Integer value) {
                    batch.skippedRows = value;
                }

                @Override
                public Integer getValidationErrorCount(ProductPriceUploadBatchEntity batch) {
                    return batch.validationErrorCount;
                }

                @Override
                public void setValidationErrorCount(ProductPriceUploadBatchEntity batch, Integer value) {
                    batch.validationErrorCount = value;
                }
            };

    // ──────────────────────────────────────────────────────────────────────────
    // ImportBatchService interface
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public ProductPriceUploadBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin) {
        return createProductPriceImportPendingBatch(filename, admin);
    }

    @Override
    public void markImportBatchAsProcessing(UUID batchId) {
        markProductPriceImportBatchAsProcessing(batchId, null);
    }

    @Override
    public void markImportBatchAsProcessed(UUID batchId) {
        markProductPriceBatchAsProcessed(batchId);
    }

    @Override
    public void markImportBatchAsFailed(UUID batchId) {
        markProductPriceBatchAsFailed(batchId);
    }

    @Override
    public ProductPriceUploadBatchProcessStatusDto getImportBatchProcessStatus(UUID batchId) {
        return getProductPriceImportBatchProcessStatus(batchId);
    }

    @Override
    public List<ProductPriceComparisonDto> getImportRows(UUID batchId) {
        return getProductPriceImportRows(batchId);
    }

    @Override
    public List<ProductUploadBatchDto> getUploadBatches() {
        return getProductPriceUploadBatches();
    }

    @Override
    public void handleCsvUploadForBatch(InputStream is, UUID batchId) throws Exception {
        handleProductPriceCsvUploadForBatch(is, batchId);
    }

    @Override
    public void processStagedRowsForBatch(UUID batchId) {
        processProductPriceStagedRowsForBatch(batchId);
    }

    @Override
    public void markBatchAsProcessed(UUID batchId) {
        markProductPriceBatchAsProcessed(batchId);
    }

    @Override
    public void markBatchAsFailed(UUID batchId) {
        markProductPriceBatchAsFailed(batchId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CSV upload and processing (delegating to state machine)
    // ──────────────────────────────────────────────────────────────────────────

    public void handleProductPriceCsvUploadForBatch(InputStream is, UUID batchId) throws IOException {
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

    public void processProductPriceStagedRowsForBatch(UUID batchId) {
        LOG.debug("DEBUG:: Processing Price Batch: " + batchId);
        while (true) {
            int handledRows = stateMachine.processNextStagedChunk(batchId, strategy);
            if (handledRows == 0) {
                break;
            }
        }

        stateMachine.synchronizeBatchProgress(batchId, strategy);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Batch lifecycle methods (kept unchanged)
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public void markProductPriceImportBatchAsProcessing(UUID batchId, StaffUserEntity approvedBy) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        if (batch.productUploadStatusEn == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Price Batch is already processing");
        }

        long totalRows = productPriceUploadStagedRepository.countByBatchId(batchId);
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSING;
        batch.totalRows = (int) totalRows;
        batch.processedRows = 0;
        batch.skippedRows = 0;
        batch.approvedBy = approvedBy;
    }

    @Transactional
    public ProductPriceUploadBatchEntity createProductPriceImportPendingBatch(String filename, StaffUserEntity admin) {
        ProductPriceUploadBatchEntity batch = new ProductPriceUploadBatchEntity();
        batch.filename = filename;
        batch.productUploadStatusEn = ProductUploadStatusEn.IMPORTING;
        batch.uploadedBy = admin;
        batch.totalRows = 0;
        batch.processedRows = 0;
        batch.skippedRows = 0;
        batch.validationErrorCount = 0;
        productPriceUploadBatchRepository.persist(batch);
        return batch;
    }

    @Transactional
    public void markProductPriceBatchAsFailed(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.FAILED;
        batch.completedAt = LocalDateTime.now();
    }

    @Transactional
    public void markProductPriceBatchAsProcessed(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSED;
        batch.completedAt = LocalDateTime.now();
    }

    @Transactional(value = Transactional.TxType.SUPPORTS)
    public ProductPriceUploadBatchProcessStatusDto getProductPriceImportBatchProcessStatus(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

        ProductPriceUploadBatchProcessStatusDto status = new ProductPriceUploadBatchProcessStatusDto();
        status.batchId = batch.id;
        status.status = batch.productUploadStatusEn != null ? batch.productUploadStatusEn.name() : null;
        status.totalRows = batch.totalRows;
        status.stagedRows = productPriceUploadStagedRepository.countByBatchId(batchId);
        status.processedRows = batch.processedRows != null ? (long) batch.processedRows : 0L;
        status.skippedRows = batch.skippedRows != null ? (long) batch.skippedRows : 0L;
        status.validationErrorCount = batch.validationErrorCount;
        status.completed = batch.productUploadStatusEn != ProductUploadStatusEn.PROCESSING;
        return status;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers retained (batch loading, row application, query/DTO assembly)
    // ──────────────────────────────────────────────────────────────────────────

    private void completeProductPriceCsvUpload(UUID batchId, int totalRows, int validationErrorCount) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
            batch.totalRows = totalRows;
            batch.validationErrorCount = validationErrorCount;
            batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        });
    }

    private ProductPriceUploadBatchEntity getRequiredProductPriceBatch(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        return batch;
    }

    private void applyValidProductPriceStagedRow(ProductPriceUploadStagedEntity staged) {

        ProductVariantEntity variant = productVariantRepository.findBySku(staged.sku);

        if (variant == null) {
            //TODO::SDB ERRRO
            return;
        }

        upsertVariantPrice(variant, PriceTypeEn.RETAIL_PRICE, staged.retailPrice);
        upsertVariantPrice(variant, PriceTypeEn.WHOLESALE_PRICE, staged.wholesalePrice);
    }

    private void upsertVariantPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal priceValue) {
        if (variant == null || variant.id == null || priceType == null || priceValue == null) {
            return;
        }

        VariantPricesEntity price = variantPricesRepository.findLatestByVariantAndType(variant.id, priceType);
        if (price != null) {
            // Expire the current row without altering its historical amount
            price.priceEndDate = now();
            variantPricesRepository.persist(price);
        }
        // Create the new price row
        price = new VariantPricesEntity();
        price.variant = variant;
        price.priceType = priceType;
        price.price = priceValue;
        price.priceEndDate = LocalDateTime.of(2099, 1, 1, 0, 0, 0);
        price.priceStartDate = now();
        variantPricesRepository.persist(price);
    }

    public List<ProductPriceComparisonDto> getProductPriceImportRows(UUID batchId) {
        return productPriceComparisonMapper.toDtos(productPriceUploadStagedRepository.findByBatchId(batchId));
    }

    public List<ProductUploadBatchDto> getProductPriceUploadBatches() {
        List<ProductPriceUploadBatchEntity> batches = productPriceUploadBatchRepository.listAll();
        return batches.stream().map(UploadBatchDtoMapper::fromProductPriceBatch).collect(Collectors.toList());
    }

}
