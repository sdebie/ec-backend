package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductPriceUploadBatchProcessStatusDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.time.LocalDateTime.now;
import static org.ecommerce.common.util.CsvImportUtils.*;

@ApplicationScoped
public class ProductPriceImportService {

    @Inject
    EntityManager entityManager;

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);

    @Transactional
    public void handleProductPriceCsvUploadForBatch(InputStream is, UUID batchId) throws IOException {
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

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
                ProductPriceUploadStagedEntity staged = new ProductPriceUploadStagedEntity();
                staged.batch = batch;

                List<String> validationErrors = new ArrayList<>();

                staged.sku = getValue(record, "sku", "SKU");
                staged.retailPrice = parseBigDecimal(record, validationErrors, "retail_price", "Retail Price");
                staged.wholesalePrice = parseBigDecimal(record, validationErrors, "wholesale_price", "Wholesale Price");

                findExistingVariant(staged.sku, validationErrors);

                validationErrorCount += validationErrors.size();
                if (!validationErrors.isEmpty()) {
                    LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", record.getRecordNumber(), staged.sku, staged.validationErrors);
                }

                staged.persist();
                rowCount++;

                if (rowCount % 500 == 0) {
                    entityManager.flush();
                }
            }
        }

        entityManager.flush();
        batch.totalRows = rowCount;
        batch.validationErrorCount = validationErrorCount;
        batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        entityManager.flush();
    }

    @Transactional
    public void markProductPriceImportBatchAsProcessing(UUID batchId) {
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        if (batch.productUploadStatusEn == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Price Batch is already processing");
        }

        long totalRows = ProductPriceUploadStagedEntity.count("batch.id", batchId);
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
        batch.persist();
        return batch;
    }

    @Transactional
    public void markProductPriceBatchAsFailed(UUID batchId) {
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.FAILED;
    }

    @Transactional
    public void markProductPriceBatchAsProcessed(UUID batchId) {
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSED;
    }

    @Transactional(value = Transactional.TxType.SUPPORTS)
    public ProductPriceUploadBatchProcessStatusDto getProductPriceImportBatchProcessStatus(UUID batchId) {
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

        ProductPriceUploadBatchProcessStatusDto status = new ProductPriceUploadBatchProcessStatusDto();
        status.batchId = batch.id;
        status.status = batch.productUploadStatusEn != null ? batch.productUploadStatusEn.name() : null;
        status.totalRows = batch.totalRows;
        status.stagedRows = ProductUploadStagedEntity.count("batch.id", batchId);
        status.processedRows = batch.processedRows != null ? (long) batch.processedRows : 0L;
        status.skippedRows = batch.skippedRows != null ? (long) batch.skippedRows : 0L;
        status.validationErrorCount = batch.validationErrorCount;
        status.completed = batch.productUploadStatusEn != ProductUploadStatusEn.PROCESSING;
        return status;
    }


    @Transactional
    public void processProductPriceStagedRowsForBatch(UUID batchId) {
        LOG.debug("DEBUG:: Processing Price Batch: " + batchId);
        ProductPriceUploadBatchEntity batch = ProductPriceUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price Batch not found: " + batchId);
        }

        List<ProductPriceUploadStagedEntity> stagedRows = ProductPriceUploadStagedEntity.list("batch.id = ?1 and processed = false", batchId);
        long processedCount = 0;
        long skippedCount = 0;

        for (ProductPriceUploadStagedEntity staged : stagedRows) {
            if (staged.validationStatus == ProductImportValidationStatusEn.VALID) {
                applyValidProductPriceStagedRow(staged);
                processedCount++;
            } else {
                skippedCount++;
            }

            staged.processed = true;
            LOG.debugf("DEBUG:: Price processed=%d skipped=%d", processedCount, skippedCount);

            if ((processedCount + skippedCount) % 500 == 0) {
                entityManager.flush();
            }
        }

        entityManager.flush();

        long totalRows = ProductPriceUploadStagedEntity.count("batch.id", batchId);
        long totalProcessedRows = ProductUploadStagedEntity.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.VALID
        );
        long totalSkippedRows = ProductPriceUploadStagedEntity.count(
                "batch.id = ?1 and processed = true and (validationStatus is null or validationStatus <> ?2)",
                batchId,
                ProductImportValidationStatusEn.VALID
        );
        batch.totalRows = (int) totalRows;
        batch.processedRows = (int) totalProcessedRows;
        batch.skippedRows = (int) totalSkippedRows;
    }

    private void applyValidProductPriceStagedRow(ProductPriceUploadStagedEntity staged) {

        ProductVariantEntity variant = ProductVariantEntity.find("sku", staged.sku).firstResult();

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

        VariantPricesEntity price = VariantPricesEntity.findLatestByVariantAndType(variant.id, priceType);
        if (price != null) {
            //Expire current row
            price.price = priceValue;
            price.priceEndDate = now();
            price.persist();
        }
        //Create nwe price
        price = new VariantPricesEntity();
        price.variant = variant;
        price.priceType = priceType;
        price.price = priceValue;
        price.priceEndDate = LocalDateTime.of(2099, 1, 1, 0, 0, 0);
        price.priceStartDate = now();
        price.persist();
    }

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors) {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        ProductVariantEntity variant = ProductVariantEntity.find("sku", sku.trim()).firstResult();
        if (variant == null) {
            validationErrors.add("variant with sku '" + sku + "' not found");
        }
        return variant;
    }

    public List<ProductPriceComparisonDto> getProductPriceImportRows(UUID batchId) {
        List<ProductPriceUploadStagedEntity> stagedList = ProductPriceUploadStagedEntity.find("batch.id = ?1", batchId).list();

        return stagedList.stream().map(staged -> {
            ProductPriceComparisonDto dto = new ProductPriceComparisonDto();
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
            dto.validationErrors = staged.validationErrors;
            dto.validationStatus = staged.validationStatus;
            dto.proposedRetailPrice = staged.retailPrice;
            dto.proposedWholesalePrice = staged.wholesalePrice;

            ProductVariantEntity variant = ProductVariantEntity.find("sku", staged.sku).firstResult();
            if (variant != null) {
                BigDecimal retailPrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.RETAIL_PRICE);
                BigDecimal wholesalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.WHOLESALE_PRICE);

                dto.currentRetailPrice = retailPrice;
                dto.currentWholesalePrice = wholesalePrice;
            }

            dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);

            return dto;
        }).collect(Collectors.toList());
    }

    public List<ProductUploadBatchDto> getProductPriceUploadBatches() {
        List<ProductPriceUploadBatchEntity> batches = ProductPriceUploadBatchEntity.listAll();
        return batches.stream().map(batch -> {
            ProductUploadBatchDto dto = new ProductUploadBatchDto();
            dto.id = batch.id;
            dto.filename = batch.filename;
            dto.status = batch.productUploadStatusEn.toString();
            dto.totalRows = batch.totalRows;
            dto.processedRows = batch.processedRows;
            dto.skippedRows = batch.skippedRows;
            dto.validationErrorCount = batch.validationErrorCount;
            dto.createdAt = batch.createdAt;
            dto.uploadedByUsername = batch.uploadedBy != null ? batch.uploadedBy.email : null;
            return dto;
        }).collect(Collectors.toList());
    }

}
