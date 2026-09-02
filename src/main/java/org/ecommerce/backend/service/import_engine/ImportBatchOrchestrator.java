package org.ecommerce.backend.service.import_engine;

import java.util.UUID;

/**
 * Batch-level operations for any import type.
 * Handles status tracking, staging completion, and row processing.
 */
public interface ImportBatchOrchestrator {

    /**
     * Mark staging as complete with row and validation error counts.
     */
    void completeStaging(UUID batchId, int totalRows, int validationErrorCount);

    /**
     * Process all staged rows using the given strategy.
     */
    void processStagedRows(UUID batchId, ImportStrategy strategy);

    /**
     * Mark batch as successfully processed.
     */
    void markBatchAsProcessed(UUID batchId);

    /**
     * Mark batch as failed.
     */
    void markBatchAsFailed(UUID batchId);

    /**
     * Get batch status for polling.
     */
    Object getBatchStatus(UUID batchId);
}
