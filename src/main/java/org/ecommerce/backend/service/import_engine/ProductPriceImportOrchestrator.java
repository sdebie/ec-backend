package org.ecommerce.backend.service.import_engine;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ImportSourceTypeEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates product price imports. Implements both batch operations and legacy service interface.
 */
@ApplicationScoped
public class ProductPriceImportOrchestrator extends BaseImportOrchestrator
{
    private static final Logger LOG = Logger.getLogger(ProductPriceImportOrchestrator.class);

    @Inject
    ProductPriceImportBatchRepository batchRepository;

    @Inject
    ProductPriceImportStagedRepository stagedRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    VariantPricesRepository pricesRepository;

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
            int processed;
            try {
                processed = QuarkusTransaction.requiringNew().call(() -> processNextChunk(batchId, limit));
            } catch (Exception ex) {
                throw new RuntimeException("Failed to process price import chunk for batch " + batchId, ex);
            }
            if (processed == 0) {
                break;
            }
        }
    }

    int processNextChunk(UUID batchId, int limit) {
        List<ProductPriceImportStagedEntity> chunk = stagedRepository.findNextUnprocessedByBatchId(batchId, limit);
        if (chunk.isEmpty()) {
            return 0;
        }
        ProductPriceImportBatchEntity batch = batchRepository.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Price batch not found: " + batchId);
        }

        int processed = 0;
        int skipped = 0;
        for (ProductPriceImportStagedEntity staged : chunk) {
            if (staged.getValidationStatus() == ProductImportValidationStatusEn.VALID) {
                applyPriceRow(staged);
                processed++;
            } else {
                skipped++;
            }
            staged.setProcessed(true);
        }
        batch.setProcessedRows(nullToZero(batch.getProcessedRows()) + processed);
        batch.setSkippedRows(nullToZero(batch.getSkippedRows()) + skipped);
        return chunk.size();
    }

    public void overlayMissingProgress(ProductPriceImportBatchEntity batch, ProductImportBatchDto dto) {
        if (!shouldOverlayMissingProgress(batch.getProductUploadStatusEn())) {
            return;
        }
        if (nullToZero(dto.getProcessedRows()) > 0 || nullToZero(dto.getSkippedRows()) > 0) {
            return;
        }
        dto.setProcessedRows((int) stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batch.getId(),
                ProductImportValidationStatusEn.VALID));
        dto.setSkippedRows((int) stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batch.getId(),
                ProductImportValidationStatusEn.INVALID));
    }

    private static boolean shouldOverlayMissingProgress(ProductUploadStatusEn status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case PROCESSED, FAILED -> true;
            case IMPORTING, PENDING, PROCESSING -> false;
        };
    }

    private static int nullToZero(Integer value) {
        return value != null ? value : 0;
    }

    @Override
    protected Object getChunkedImportStateMachine() {
        return null;
    }

    @Transactional
    public ProductPriceImportBatchEntity createPendingBatch(String filename, StaffUserEntity admin) {
        return createPendingBatch(filename, admin, ImportSourceTypeEn.FILE);
    }

    @Transactional
    public ProductPriceImportBatchEntity createPendingBatch(String filename, StaffUserEntity admin, ImportSourceTypeEn sourceType) {
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setFilename(filename);
        batch.setImportSourceTypeEn(sourceType);
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

    public List<ProductPriceImportBatchEntity> listAllOrderByCreatedAtDesc() {
        return batchRepository.listAllOrderByCreatedAtDesc();
    }

    public List<ProductPriceImportStagedEntity> findByBatchId(UUID batchId) {
        return stagedRepository.findByBatchId(batchId);
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
            price.setPriceEndDate(Instant.now());
            pricesRepository.persist(price);
        }

        price = new VariantPricesEntity();
        price.setVariant(variant);
        price.setPriceType(priceType);
        price.setPrice(priceValue);
        price.setPriceEndDate(Instant.parse("2099-01-01T00:00:00Z"));
        price.setPriceStartDate(Instant.now());
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
