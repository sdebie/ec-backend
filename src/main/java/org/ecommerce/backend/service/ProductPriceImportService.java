package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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

    private static final int STAGING_CHUNK_SIZE = 200;
    private static final int PROCESSING_CHUNK_SIZE = 100;

    @Inject
    EntityManager entityManager;

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

    private static final Logger LOG = Logger.getLogger(ProductPriceImportService.class);

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

    public void handleProductPriceCsvUploadForBatch(InputStream is, UUID batchId) throws IOException {
        List<ParsedPriceRow> chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
        int rowCount = 0;
        int validationErrorCount = 0;

        List<ParsedPriceRow> allRows = productPriceImportParser.parseAll(is);

        for (ParsedPriceRow row : allRows) {
            chunk.add(row);
            if (chunk.size() == STAGING_CHUNK_SIZE) {
                StagingChunkResult result = stageProductPriceRowsChunk(batchId, chunk);
                rowCount += result.rowCount();
                validationErrorCount += result.validationErrorCount();
                chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
            }
        }

        if (!chunk.isEmpty()) {
            StagingChunkResult result = stageProductPriceRowsChunk(batchId, chunk);
            rowCount += result.rowCount();
            validationErrorCount += result.validationErrorCount();
        }

        completeProductPriceCsvUpload(batchId, rowCount, validationErrorCount);
    }

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


    public void processProductPriceStagedRowsForBatch(UUID batchId) {
        LOG.debug("DEBUG:: Processing Price Batch: " + batchId);
        while (true) {
            int handledRows = processNextProductPriceStagedChunk(batchId);
            if (handledRows == 0) {
                break;
            }
        }

        synchronizeProductPriceBatchProgress(batchId);
    }

    private StagingChunkResult stageProductPriceRowsChunk(UUID batchId, List<ParsedPriceRow> rows) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageProductPriceRowsChunkInTransaction(batchId, List.copyOf(rows)));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private StagingChunkResult stageProductPriceRowsChunkInTransaction(UUID batchId, List<ParsedPriceRow> rows) {
        ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
        int validationErrorCount = 0;

        for (ParsedPriceRow row : rows) {
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
            validationErrorCount += result.validationErrors().size();
            if (!result.validationErrors().isEmpty()) {
                LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", row.recordNumber(), staged.sku, staged.validationErrors);
            }

            productPriceUploadStagedRepository.persist(staged);
        }

        batch.totalRows = safeInt(batch.totalRows) + rows.size();
        batch.validationErrorCount = safeInt(batch.validationErrorCount) + validationErrorCount;
        entityManager.flush();
        entityManager.clear();
        return new StagingChunkResult(rows.size(), validationErrorCount);
    }

    private void completeProductPriceCsvUpload(UUID batchId, int totalRows, int validationErrorCount) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
            batch.totalRows = totalRows;
            batch.validationErrorCount = validationErrorCount;
            batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        });
    }

    private int processNextProductPriceStagedChunk(UUID batchId) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> processNextProductPriceStagedChunkInTransaction(batchId));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private int processNextProductPriceStagedChunkInTransaction(UUID batchId) {
        ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
        List<ProductPriceUploadStagedEntity> stagedRows = productPriceUploadStagedRepository.findNextUnprocessedByBatchId(batchId, PROCESSING_CHUNK_SIZE);
        if (stagedRows.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        int skippedCount = 0;

        for (ProductPriceUploadStagedEntity staged : stagedRows) {
            if (staged.validationStatus == ProductImportValidationStatusEn.VALID) {
                applyValidProductPriceStagedRow(staged);
                processedCount++;
            } else {
                skippedCount++;
            }

            staged.processed = true;
        }

        batch.totalRows = batch.totalRows != null ? batch.totalRows : (int) productPriceUploadStagedRepository.countByBatchId(batchId);
        batch.processedRows = safeInt(batch.processedRows) + processedCount;
        batch.skippedRows = safeInt(batch.skippedRows) + skippedCount;

        LOG.debugf("DEBUG:: Price processed=%d skipped=%d", processedCount, skippedCount);
        entityManager.flush();
        entityManager.clear();
        return stagedRows.size();
    }

    private void synchronizeProductPriceBatchProgress(UUID batchId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
            long totalRows = productPriceUploadStagedRepository.countByBatchId(batchId);
            long totalProcessedRows = productPriceUploadStagedRepository.countProcessedValidByBatchId(batchId);
            long totalSkippedRows = productPriceUploadStagedRepository.countProcessedInvalidByBatchId(batchId);
            batch.totalRows = (int) totalRows;
            batch.processedRows = (int) totalProcessedRows;
            batch.skippedRows = (int) totalSkippedRows;
        });
    }

    private ProductPriceUploadBatchEntity getRequiredProductPriceBatch(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        return batch;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
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

    private record StagingChunkResult(int rowCount, int validationErrorCount) {
    }

}
