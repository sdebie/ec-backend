package org.ecommerce.backend.service.import_engine;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.common.entity.ImportBatchEntity;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Common orchestration logic for any import type.
 * Subclasses handle domain-specific staging and processing.
 */
public abstract class BaseImportOrchestrator implements ImportBatchOrchestrator {

    protected abstract Logger logger();

    /**
     * Get the batch entity by ID. Subclass implements domain-specific retrieval.
     */
    protected abstract ImportBatchEntity getBatchRequired(UUID batchId);

    /**
     * Process staged rows in chunks. Called by processStagedRows().
     * Subclass implements domain-specific row iteration and application.
     */
    protected abstract void processStagedRowsImpl(UUID batchId, ImportStrategy strategy);

    /**
     * Subclass provides chunked import state machine if needed.
     */
    protected abstract Object getChunkedImportStateMachine();

    @Override
    @Transactional
    public void completeStaging(UUID batchId, int totalRows, int validationErrorCount) {
        QuarkusTransaction.requiringNew().run(() -> {
            ImportBatchEntity batch = getBatchRequired(batchId);
            batch.setTotalRows(totalRows);
            batch.setValidationErrorCount(validationErrorCount);
            batch.setProductUploadStatusEn(ProductUploadStatusEn.PENDING);
        });
    }

    @Override
    public void processStagedRows(UUID batchId, ImportStrategy strategy) {
        processStagedRowsImpl(batchId, strategy);
    }

    @Override
    @Transactional
    public void markBatchAsProcessed(UUID batchId) {
        ImportBatchEntity batch = getBatchRequired(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Override
    @Transactional
    public void markBatchAsFailed(UUID batchId) {
        ImportBatchEntity batch = getBatchRequired(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.FAILED);
        batch.setCompletedAt(LocalDateTime.now());
    }

    @Override
    public Object getBatchStatus(UUID batchId) {
        // Subclasses should override to return domain-specific status DTOs
        throw new UnsupportedOperationException("Subclass must implement getBatchStatus()");
    }
}
