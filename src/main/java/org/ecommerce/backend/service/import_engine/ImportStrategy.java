package org.ecommerce.backend.service.import_engine;

import java.io.InputStream;
import java.util.UUID;

/**
 * Strategy for handling different import sources (CSV, Sage, API, etc).
 * Defines how to parse and process data from a specific source.
 */
public interface ImportStrategy {

    /**
     * Parse and stage rows from input stream into the batch.
     * Implementations should validate and store staging entities.
     *
     * @return total rows parsed and validation error count
     */
    ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception;

    /**
     * Apply a single staged row to the database.
     * Called per-row during processing phase.
     */
    void applyStagedRow(UUID batchId, Object stagedRowId);

    /**
     * Get the type name for this strategy (e.g., "product-csv", "price-csv", "sage").
     */
    String getType();

    record ImportStageResult(int totalRows, int validationErrorCount) {}
}
