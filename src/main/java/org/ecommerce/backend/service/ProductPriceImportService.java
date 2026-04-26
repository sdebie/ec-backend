package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.time.LocalDateTime.now;
import static org.ecommerce.common.util.CsvImportUtils.*;

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
    ProductVariantRepository productVariantRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);

    @Override
    public ProductPriceUploadBatchEntity createImportPendingBatch(String filename, StaffUserEntity admin) {
        return createProductPriceImportPendingBatch(filename, admin);
    }

    @Override
    public void markImportBatchAsProcessing(UUID batchId) {
        markProductPriceImportBatchAsProcessing(batchId);
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
        List<StagedProductPriceCsvRow> chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
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
                chunk.add(parseProductPriceCsvRow(record));
                if (chunk.size() == STAGING_CHUNK_SIZE) {
                    StagingChunkResult result = stageProductPriceRowsChunk(batchId, chunk);
                    rowCount += result.rowCount();
                    validationErrorCount += result.validationErrorCount();
                    chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
                }
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
    public void markProductPriceImportBatchAsProcessing(UUID batchId) {
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
    }

    @Transactional
    public void markProductPriceBatchAsProcessed(UUID batchId) {
        ProductPriceUploadBatchEntity batch = productPriceUploadBatchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSED;
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

    private StagedProductPriceCsvRow parseProductPriceCsvRow(CSVRecord record) {
        List<String> validationErrors = new ArrayList<>();
        return new StagedProductPriceCsvRow(
                record.getRecordNumber(),
                getValue(record, "sku", "SKU"),
                parseBigDecimal(record, validationErrors, "retail_price", "Retail Price"),
                parseBigDecimal(record, validationErrors, "wholesale_price", "Wholesale Price"),
                List.copyOf(validationErrors));
    }

    private StagingChunkResult stageProductPriceRowsChunk(UUID batchId, List<StagedProductPriceCsvRow> rows) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageProductPriceRowsChunkInTransaction(batchId, List.copyOf(rows)));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private StagingChunkResult stageProductPriceRowsChunkInTransaction(UUID batchId, List<StagedProductPriceCsvRow> rows) {
        ProductPriceUploadBatchEntity batch = getRequiredProductPriceBatch(batchId);
        int validationErrorCount = 0;

        for (StagedProductPriceCsvRow row : rows) {
            ProductPriceUploadStagedEntity staged = new ProductPriceUploadStagedEntity();
            staged.batch = batch;
            staged.sku = row.sku();
            staged.retailPrice = row.retailPrice();
            staged.wholesalePrice = row.wholesalePrice();

            List<String> validationErrors = new ArrayList<>(row.validationErrors());
            ProductVariantEntity existingVariant = findExistingVariant(staged.sku, validationErrors);

            boolean retailChanged = pricesDiffer(staged.retailPrice, findLatestPrice(existingVariant, PriceTypeEn.RETAIL_PRICE));
            boolean wholesaleChanged = pricesDiffer(staged.wholesalePrice, findLatestPrice(existingVariant, PriceTypeEn.WHOLESALE_PRICE));

            staged.hasChanges = retailChanged || wholesaleChanged;

            if (existingVariant != null) {
                staged.currentRetailPrice = findLatestPrice(existingVariant, PriceTypeEn.RETAIL_PRICE);
                staged.currentWholesalePrice = findLatestPrice(existingVariant, PriceTypeEn.WHOLESALE_PRICE);
            }

            applyValidationResults(staged, validationErrors);
            validationErrorCount += validationErrors.size();
            if (!validationErrors.isEmpty()) {
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

    private void applyValidationResults(ProductPriceUploadStagedEntity staged, List<String> validationErrors) {
        staged.validationStatus = validationErrors.isEmpty()
                ? ProductImportValidationStatusEn.VALID
                : ProductImportValidationStatusEn.INVALID;
        staged.validationErrors = validationErrors.isEmpty() ? null : String.join("; ", validationErrors);
    }

    private boolean pricesDiffer(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return false;
        }
        if (left == null || right == null) {
            return true;
        }
        return left.compareTo(right) != 0;
    }

    private BigDecimal findLatestPrice(ProductVariantEntity variant, PriceTypeEn priceType) {
        if (variant == null || variant.id == null) {
            return null;
        }
        VariantPricesEntity price = VariantPricesEntity.findLatestByVariantAndType(variant.id, priceType);
        return price != null ? price.price : null;
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
            //Expire current row
            price.price = priceValue;
            price.priceEndDate = now();
            variantPricesRepository.persist(price);
        }
        //Create nwe price
        price = new VariantPricesEntity();
        price.variant = variant;
        price.priceType = priceType;
        price.price = priceValue;
        price.priceEndDate = LocalDateTime.of(2099, 1, 1, 0, 0, 0);
        price.priceStartDate = now();
        variantPricesRepository.persist(price);
    }

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors) {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        ProductVariantEntity variant = productVariantRepository.findBySku(sku);
        if (variant == null) {
            validationErrors.add("variant with sku '" + sku + "' not found");
        }
        return variant;
    }

    public List<ProductPriceComparisonDto> getProductPriceImportRows(UUID batchId) {
        List<ProductPriceUploadStagedEntity> stagedList = productPriceUploadStagedRepository.findByBatchId(batchId);

        return stagedList.stream().map(staged -> {
            ProductPriceComparisonDto dto = new ProductPriceComparisonDto();
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
            dto.validationErrors = staged.validationErrors;
            dto.validationStatus = staged.validationStatus;
            dto.proposedRetailPrice = staged.retailPrice;
            dto.proposedWholesalePrice = staged.wholesalePrice;
            dto.currentRetailPrice = staged.currentRetailPrice;
            dto.currentWholesalePrice = staged.currentWholesalePrice;
            dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);

            return dto;
        }).collect(Collectors.toList());
    }

    public List<ProductUploadBatchDto> getProductPriceUploadBatches() {
        List<ProductPriceUploadBatchEntity> batches = productPriceUploadBatchRepository.listAll();
        return batches.stream().map(UploadBatchDtoMapper::fromProductPriceBatch).collect(Collectors.toList());
    }

    private record StagedProductPriceCsvRow(
            long recordNumber,
            String sku,
            BigDecimal retailPrice,
            BigDecimal wholesalePrice,
            List<String> validationErrors
    ) {
    }

    private record StagingChunkResult(int rowCount, int validationErrorCount) {
    }

}
