package org.ecommerce.backend.service;

import java.io.InputStream;
import java.util.UUID;

/**
 * Common async import operations used by upload async services.
 */
public interface AsyncImportOperations
{
    void handleCsvUploadForBatch(InputStream is, UUID batchId) throws Exception;

    void processStagedRowsForBatch(UUID batchId);

    void markBatchAsProcessed(UUID batchId);

    void markBatchAsFailed(UUID batchId);
}

