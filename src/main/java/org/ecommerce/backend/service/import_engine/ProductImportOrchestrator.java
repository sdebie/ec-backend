package org.ecommerce.backend.service.import_engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.csv.ProductImportParser;
import org.ecommerce.backend.csv.ProductImportValidator;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates product imports. Implements both batch operations and legacy service interface.
 */
@ApplicationScoped
public class ProductImportOrchestrator extends BaseImportOrchestrator {
    private static final Logger LOG = Logger.getLogger(ProductImportOrchestrator.class);

    @Inject
    ProductImportBatchRepository batchRepository;

    @Inject
    ProductImportStagedRepository stagedRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    ProductImportValidator validator;

    @Inject
    ImportBatchDtoMapper dtoMapper;

    @Override
    protected Logger logger() {
        return LOG;
    }

    @Override
    protected ImportBatchEntity getBatchRequired(UUID batchId) {
        ProductImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Product batch not found: " + batchId);
        }
        return batch;
    }

    @Override
    protected void processStagedRowsImpl(UUID batchId, ImportStrategy strategy) {
        // Note: Current implementation doesn't use the strategy during processing
        // as the strategy is mostly for parsing/staging. This can be extended.
        LOG.debugf("Processing product batch: %s", batchId);

        int limit = 1000;
        while (true) {
            List<ProductImportStagedEntity> chunk = stagedRepository.findNextUnprocessedByBatchId(batchId, limit);
            if (chunk.isEmpty()) {
                break;
            }

            for (ProductImportStagedEntity staged : chunk) {
                if (staged.getValidationStatus() == ProductImportValidationStatusEn.VALID) {
                    applyProductRow(staged);
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
    public ProductImportBatchEntity createPendingBatch(String filename, StaffUserEntity admin) {
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setFilename(filename);
        batch.setProductUploadStatusEn(org.ecommerce.common.enums.ProductUploadStatusEn.IMPORTING);
        batch.setUploadedBy(admin);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        batchRepository.persist(batch);
        return batch;
    }

    @Transactional
    public void markAsProcessing(UUID batchId) {
        ProductImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Product batch not found: " + batchId);
        }
        if (batch.getProductUploadStatusEn() == org.ecommerce.common.enums.ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = stagedRepository.countByBatchId(batchId);
        batch.setProductUploadStatusEn(org.ecommerce.common.enums.ProductUploadStatusEn.PROCESSING);
        batch.setTotalRows((int) totalRows);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
    }

    public ImportBatchProcessStatusDto getStatus(UUID batchId) {
        ProductImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Product batch not found: " + batchId);
        }

        ImportBatchProcessStatusDto status = new ImportBatchProcessStatusDto();
        status.setBatchId(batch.getId());
        status.setStatus(batch.getProductUploadStatusEn().name());
        status.setTotalRows(batch.getTotalRows() != null ? batch.getTotalRows() : 0);
        status.setStagedRows(stagedRepository.countByBatchId(batchId));
        status.setProcessedRows(batch.getProcessedRows() != null ? (long) batch.getProcessedRows() : 0L);
        status.setSkippedRows(batch.getSkippedRows() != null ? (long) batch.getSkippedRows() : 0L);
        status.setValidationErrorCount(batch.getValidationErrorCount() != null ? batch.getValidationErrorCount() : 0);
        status.setCompleted(batch.getProductUploadStatusEn() != org.ecommerce.common.enums.ProductUploadStatusEn.PROCESSING);
        return status;
    }

    public List<ProductImportBatchDto> listBatches() {
        return batchRepository.listAll()
                .stream()
                .map(dtoMapper::fromProductBatch)
                .collect(Collectors.toList());
    }

    private void applyProductRow(ProductImportStagedEntity staged) {
        ProductVariantEntity variant = variantRepository.findBySku(staged.getSku());
        if (variant == null) {
            LOG.warnf("Skipped SKU '%s': variant no longer exists", staged.getSku());
            return;
        }

        // Apply the product data to the variant
        // (Implementation depends on what fields ProductImportStagedEntity has)
    }
}
