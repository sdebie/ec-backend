package org.ecommerce.backend.service.import_engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.time.LocalDateTime.now;

/**
 * Orchestrates product price imports. Implements both batch operations and legacy service interface.
 */
@ApplicationScoped
public class ProductPriceImportOrchestrator extends BaseImportOrchestrator {
    private static final Logger LOG = Logger.getLogger(ProductPriceImportOrchestrator.class);

    @Inject
    ProductPriceImportBatchRepository batchRepository;

    @Inject
    ProductPriceImportStagedRepository stagedRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    VariantPricesRepository pricesRepository;

    @Inject
    ImportBatchDtoMapper dtoMapper;

    @Override
    protected Logger logger() {
        return LOG;
    }

    @Override
    protected ImportBatchEntity getBatchRequired(UUID batchId) {
        ProductPriceImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price batch not found: " + batchId);
        }
        return batch;
    }

    @Override
    protected void processStagedRowsImpl(UUID batchId, ImportStrategy strategy) {
        LOG.debugf("Processing price batch: %s", batchId);

        int limit = 1000;
        while (true) {
            List<ProductPriceImportStagedEntity> chunk = stagedRepository.findNextUnprocessedByBatchId(batchId, limit);
            if (chunk.isEmpty()) {
                break;
            }

            for (ProductPriceImportStagedEntity staged : chunk) {
                if (staged.getValidationStatus() == ProductImportValidationStatusEn.VALID) {
                    applyPriceRow(staged);
                }
                staged.setProcessed(true);
            }
        }
    }

    @Override
    protected Object getChunkedImportStateMachine() {
        return null;
    }

    @Transactional
    public ProductPriceImportBatchEntity createPendingBatch(String filename, StaffUserEntity admin) {
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        batchRepository.persist(batch);
        return batch;
    }

    @Transactional
    public void markAsProcessing(UUID batchId, StaffUserEntity approvedBy) {
        ProductPriceImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = stagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setApprovedBy(approvedBy);
    }

    public ImportBatchProcessStatusDto getStatus(UUID batchId) {
        ProductPriceImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price batch not found: " + batchId);
        }

        ImportBatchProcessStatusDto status = new ImportBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn().name());
        status.setTotalRows(batch.getTotalRows() != null ? batch.getTotalRows() : 0);
        status.setStagedRows(stagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount() != null ? batch.getValidationErrorCount() : 0);
        status.setCompleted(batch.getProductUploadStatusEn() != ProductUploadStatusEn.PROCESSING);
        return status;
    }

    public List<ProductPriceComparisonDto> getImportRows(UUID batchId) {
        List<ProductPriceImportStagedEntity> staged = stagedRepository.findByBatchId(batchId);
        return staged.stream()
                .map(this::toComparisonDto)
                .collect(Collectors.toList());
    }

    public List<ProductImportBatchDto> listBatches() {
        return batchRepository.listAll()
                .stream()
                .map(dtoMapper::fromProductPriceBatch)
                .collect(Collectors.toList());
    }

    private void applyPriceRow(ProductPriceImportStagedEntity staged) {
        ProductVariantEntity variant = variantRepository.findBySku(staged.getSku());
        if (variant == null) {
            LOG.warnf("Skipped SKU '%s': variant no longer exists", staged.getSku());
            return;
        }

        upsertVariantPrice(variant, PriceTypeEn.RETAIL_PRICE, staged.getRetailPrice());
        upsertVariantPrice(variant, PriceTypeEn.WHOLESALE_PRICE, staged.getWholesalePrice());
    }

    private void upsertVariantPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal priceValue) {
        if (variant == null || variant.getId() == null || priceType == null || priceValue == null) {
            return;
        }

        VariantPricesEntity price = pricesRepository.findLatestByVariantAndType(variant.getId(), priceType);
        if (price != null) {
            price.setPriceEndDate(now());
            pricesRepository.persist(price);
        }

        price = new VariantPricesEntity();
        price.setVariant(variant);
        price.setPriceType(priceType);
        price.setPrice(priceValue);
        price.setPriceEndDate(LocalDateTime.of(2099, 1, 1, 0, 0, 0));
        price.setPriceStartDate(now());
        pricesRepository.persist(price);
    }

    private ProductPriceComparisonDto toComparisonDto(ProductPriceImportStagedEntity entity) {
        // Map staged entity to comparison DTO
        // Implementation depends on the structure of ProductPriceComparisonDto
        ProductPriceComparisonDto dto = new ProductPriceComparisonDto();
        // Set fields...
        return dto;
    }
}
