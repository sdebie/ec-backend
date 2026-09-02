package org.ecommerce.backend.service.import_engine;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.csv.ProductPriceImportParser;
import org.ecommerce.backend.csv.ProductPriceImportValidator;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.entity.ProductPriceImportStagedEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CSV import strategy for product prices.
 * Handles parsing, validation, and staging of price CSV data.
 */
@ApplicationScoped
public class PriceCsvImportStrategy implements ImportStrategy {
    private static final Logger LOG = Logger.getLogger(PriceCsvImportStrategy.class);
    private static final int STAGING_CHUNK_SIZE = 500;

    @Inject
    ProductPriceImportParser parser;

    @Inject
    ProductPriceImportValidator validator;

    @Inject
    ProductPriceImportBatchRepository batchRepository;

    @Inject
    ProductPriceImportStagedRepository stagedRepository;

    @Override
    public ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception {
        // Fetch batch in a quick transaction
        ProductPriceImportBatchEntity batch = QuarkusTransaction.requiringNew().call(() -> batchRepository.findById(batchId));
        if (batch == null) {
            throw new IllegalArgumentException("Batch not found: " + batchId);
        }

        LOG.infof("Starting CSV price import for batch %s", batchId);
        List<ProductPriceImportParser.ParsedPriceRow> allRows = parser.parseAll(source);
        LOG.infof("Parsed %d rows from CSV", allRows.size());

        int totalRows = 0;
        int validationErrors = 0;
        int chunkCount = 0;

        List<ProductPriceImportParser.ParsedPriceRow> chunk = new ArrayList<>(STAGING_CHUNK_SIZE);

        for (ProductPriceImportParser.ParsedPriceRow row : allRows) {
            chunk.add(row);
            if (chunk.size() >= STAGING_CHUNK_SIZE) {
                var result = stageChunkInTransaction(batch, chunk);
                totalRows += result.rowCount();
                validationErrors += result.errorCount();
                chunkCount++;
                updateBatchProgress(batch, totalRows, validationErrors);
                LOG.infof("Staged chunk %d: %d rows, %d errors (total: %d rows)", chunkCount, result.rowCount(), result.errorCount(), totalRows);
                chunk = new ArrayList<>(STAGING_CHUNK_SIZE);
            }
        }

        if (!chunk.isEmpty()) {
            var result = stageChunkInTransaction(batch, chunk);
            totalRows += result.rowCount();
            validationErrors += result.errorCount();
            chunkCount++;
            updateBatchProgress(batch, totalRows, validationErrors);
            LOG.infof("Staged chunk %d (final): %d rows, %d errors (total: %d rows)", chunkCount, result.rowCount(), result.errorCount(), totalRows);
        }

        LOG.infof("CSV price import completed for batch %s: %d total rows, %d validation errors", batchId, totalRows, validationErrors);
        return new ImportStageResult(totalRows, validationErrors);
    }

    @Override
    public void applyStagedRow(UUID batchId, Object stagedRowId) {
        // Not used in current flow; processing handled by orchestrator
    }

    @Override
    public String getType() {
        return "price-csv";
    }

    private void updateBatchProgress(ProductPriceImportBatchEntity batch, int totalRows, int validationErrors) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // Re-fetch batch in this transaction since the original is detached
                ProductPriceImportBatchEntity refreshedBatch = batchRepository.findById(batch.getId());
                if (refreshedBatch != null) {
                    refreshedBatch.setTotalRows(totalRows);
                    refreshedBatch.setValidationErrorCount(validationErrors);
                    batchRepository.persist(refreshedBatch);
                }
            });
        } catch (Exception ex) {
            LOG.warnf(ex, "Failed to update batch progress for %s", batch.getId());
        }
    }

    private ChunkResult stageChunkInTransaction(ProductPriceImportBatchEntity batch, List<ProductPriceImportParser.ParsedPriceRow> chunk) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageChunk(batch, chunk));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to stage chunk", ex);
        }
    }

    private ChunkResult stageChunk(ProductPriceImportBatchEntity batch, List<ProductPriceImportParser.ParsedPriceRow> chunk) {
        int rowCount = 0;
        int errorCount = 0;

        for (ProductPriceImportParser.ParsedPriceRow row : chunk) {
            ProductPriceImportStagedEntity staged = new ProductPriceImportStagedEntity();
            staged.setBatch(batch);
            staged.setSku(row.sku());
            staged.setRetailPrice(row.retailPrice());
            staged.setWholesalePrice(row.wholesalePrice());

            // Validate the row
            var validationResult = validator.validateAndDiff(
                    row.sku(),
                    row.retailPrice(),
                    row.wholesalePrice(),
                    row.validationErrors()
            );

            staged.setHasChanges(validationResult.hasChanges());
            staged.setCurrentRetailPrice(validationResult.currentRetailPrice());
            staged.setCurrentWholesalePrice(validationResult.currentWholesalePrice());

            validator.applyValidationResults(staged, validationResult.validationErrors());

            if (!validationResult.validationErrors().isEmpty()) {
                staged.setValidationStatus(ProductImportValidationStatusEn.INVALID);
                errorCount++;
                LOG.warnf("CSV validation failed at row %d (sku=%s): %s",
                        row.recordNumber(), staged.getSku(), staged.getValidationErrors());
            } else {
                staged.setValidationStatus(ProductImportValidationStatusEn.VALID);
            }

            stagedRepository.persist(staged);
            rowCount++;
        }

        return new ChunkResult(rowCount, errorCount);
    }

    private record ChunkResult(int rowCount, int errorCount) {}
}
