package org.ecommerce.backend.service.import_engine;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.csv.ProductImportParser;
import org.ecommerce.backend.csv.ProductImportValidator;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CSV import strategy for products.
 * Handles parsing, validation, and staging of product CSV data.
 */
@ApplicationScoped
public class ProductCsvImportStrategy implements ImportStrategy {
    private static final Logger LOG = Logger.getLogger(ProductCsvImportStrategy.class);
    private static final int STAGING_CHUNK_SIZE = 100;

    @Inject
    ProductImportParser parser;

    @Inject
    ProductImportValidator validator;

    @Inject
    ProductImportBatchRepository batchRepository;

    @Inject
    ProductImportStagedRepository stagedRepository;

    @Override
    public ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception {
        // Fetch batch in a quick transaction
        ProductImportBatchEntity batch = QuarkusTransaction.requiringNew().call(() -> batchRepository.findById(batchId));
        if (batch == null) {
            throw new IllegalArgumentException("Batch not found: " + batchId);
        }

        LOG.infof("Starting CSV product import for batch %s", batchId);
        List<ProductImportParser.StagedProductCsvRow> allRows = parser.parse(source);
        LOG.infof("Parsed %d rows from CSV", allRows.size());

        int totalRows = 0;
        int validationErrors = 0;
        int chunkCount = 0;

        List<ProductImportParser.StagedProductCsvRow> chunk = new ArrayList<>(STAGING_CHUNK_SIZE);

        for (ProductImportParser.StagedProductCsvRow row : allRows) {
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

        LOG.infof("CSV product import completed for batch %s: %d total rows, %d validation errors", batchId, totalRows, validationErrors);
        return new ImportStageResult(totalRows, validationErrors);
    }

    @Override
    public void applyStagedRow(UUID batchId, Object stagedRowId) {
        // Not used in current flow; processing handled by orchestrator
    }

    @Override
    public String getType() {
        return "product-csv";
    }

    private void updateBatchProgress(ProductImportBatchEntity batch, int totalRows, int validationErrors) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // Re-fetch batch in this transaction since the original is detached
                ProductImportBatchEntity refreshedBatch = batchRepository.findById(batch.getId());
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

    private ChunkResult stageChunkInTransaction(ProductImportBatchEntity batch, List<ProductImportParser.StagedProductCsvRow> chunk) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageChunk(batch, chunk));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to stage chunk", ex);
        }
    }

    private ChunkResult stageChunk(ProductImportBatchEntity batch, List<ProductImportParser.StagedProductCsvRow> chunk) {
        int rowCount = 0;
        int errorCount = 0;

        for (ProductImportParser.StagedProductCsvRow row : chunk) {
            ProductImportStagedEntity staged = new ProductImportStagedEntity();
            staged.setBatch(batch);

            // Map row fields to staged entity
            staged.setProductSlug(row.productSlug());
            staged.setSku(row.sku());
            staged.setName(row.name());
            staged.setDescription(row.description());
            staged.setCategorySlug(row.categorySlug());
            staged.setShortDescription(row.shortDescription());
            staged.setStock(row.stock());
            staged.setBrandSlug(row.brandSlug());
            staged.setImages(row.images());
            staged.setAttributes(row.attributes());

            // Validate the staged row and detect changes
            List<String> validationErrors = new ArrayList<>();
            validator.validateAndDiff(
                    staged,
                    validationErrors,
                    row.stock(),
                    row.brandSlug(),
                    row.images(),
                    row.attributes()
            );
            validator.validateImages(staged, validationErrors);
            validator.applyValidationResults(staged, validationErrors);

            stagedRepository.persist(staged);
            rowCount++;
            if (!validationErrors.isEmpty()) {
                errorCount++;
            }
        }

        return new ChunkResult(rowCount, errorCount);
    }

    private record ChunkResult(int rowCount, int errorCount) {}
}
