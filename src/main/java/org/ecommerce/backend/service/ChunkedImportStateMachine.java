package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Single-source chunk state machine for batch imports.
 * <p>
 * Owns the transactional choreography (per-chunk {@code QuarkusTransaction.requiringNew()}),
 * counter arithmetic, progress synchronization, and per-chunk {@code flush()}/{@code clear()}.
 * Per-type variation (CSV parsing, row validation/staging, row application) is supplied via
 * a {@link ChunkImportStrategy} passed per call — the bean stays stateless.
 * <p>
 * Type parameters:
 * <ul>
 *   <li>{@code R} — parsed CSV row type (input to staging)</li>
 *   <li>{@code S} — staged entity type (iterated by processing)</li>
 *   <li>{@code B} — batch entity type</li>
 * </ul>
 */
@ApplicationScoped
public class ChunkedImportStateMachine {

    public static final int STAGING_CHUNK_SIZE = 200;
    public static final int PROCESSING_CHUNK_SIZE = 100;

    private static final Logger LOG = Logger.getLogger(ChunkedImportStateMachine.class);

    @Inject
    EntityManager entityManager;

    /**
     * Strategy interface defining per-type variation points for the import state machine.
     * Implemented by each import service and passed per call.
     */
    public interface ChunkImportStrategy<R, S, B> {
        B loadBatch(UUID batchId);

        int stageRow(B batch, R row);

        List<S> fetchNextUnprocessedChunk(UUID batchId, int limit);

        boolean isValid(S staged);

        void applyRow(S staged);

        void markProcessed(S staged);

        long countByBatchId(UUID batchId);

        long countProcessedValidByBatchId(UUID batchId);

        long countProcessedInvalidByBatchId(UUID batchId);

        Integer getTotalRows(B batch);

        void setTotalRows(B batch, Integer value);

        Integer getProcessedRows(B batch);

        void setProcessedRows(B batch, Integer value);

        Integer getSkippedRows(B batch);

        void setSkippedRows(B batch, Integer value);

        Integer getValidationErrorCount(B batch);

        void setValidationErrorCount(B batch, Integer value);
    }

    /**
     * Result of staging a chunk of rows.
     */
    public record StagingChunkResult(int rowCount, int validationErrorCount) {
    }

    /**
     * Stages a chunk of parsed rows within a new transaction.
     * <p>
     * Opens {@code QuarkusTransaction.requiringNew()}, iterates rows through
     * {@code strategy.stageRow()}, accumulates aggregate counters, updates batch staging
     * counters, flushes and clears the persistence context, and returns the chunk result.
     *
     * @param batchId  the batch identifier
     * @param rows     the parsed rows to stage
     * @param strategy the per-type strategy
     * @param <R>      parsed row type
     * @param <S>      staged entity type
     * @param <B>      batch entity type
     * @return the staging result with row count and validation error count
     */
    public <R, S, B> StagingChunkResult stageRowsChunk(UUID batchId, List<R> rows, ChunkImportStrategy<R, S, B> strategy) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> stageRowsChunkInTransaction(batchId, List.copyOf(rows), strategy));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Processes the next chunk of staged rows within a new transaction.
     * <p>
     * Opens {@code QuarkusTransaction.requiringNew()}, fetches the next unprocessed chunk of
     * staged entities, applies valid rows / skips invalid / marks all as processed, updates
     * batch counters, flushes and clears the persistence context.
     *
     * @param batchId  the batch identifier
     * @param strategy the per-type strategy
     * @param <R>      parsed row type
     * @param <S>      staged entity type
     * @param <B>      batch entity type
     * @return the number of staged rows handled (0 means done)
     */
    public <R, S, B> int processNextStagedChunk(UUID batchId, ChunkImportStrategy<R, S, B> strategy) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> processNextStagedChunkInTransaction(batchId, strategy));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Refreshes batch counters from the three count queries.
     *
     * @param batchId  the batch identifier
     * @param strategy the per-type strategy
     * @param <R>      parsed row type
     * @param <S>      staged entity type
     * @param <B>      batch entity type
     */
    public <R, S, B> void synchronizeBatchProgress(UUID batchId, ChunkImportStrategy<R, S, B> strategy) {
        QuarkusTransaction.requiringNew().run(() -> {
            B batch = strategy.loadBatch(batchId);
            long totalRows = strategy.countByBatchId(batchId);
            long totalProcessedRows = strategy.countProcessedValidByBatchId(batchId);
            long totalSkippedRows = strategy.countProcessedInvalidByBatchId(batchId);
            strategy.setTotalRows(batch, (int) totalRows);
            strategy.setProcessedRows(batch, (int) totalProcessedRows);
            strategy.setSkippedRows(batch, (int) totalSkippedRows);
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private transactional implementations
    // ──────────────────────────────────────────────────────────────────────────

    private <R, S, B> StagingChunkResult stageRowsChunkInTransaction(UUID batchId, List<R> rows, ChunkImportStrategy<R, S, B> strategy) {
        B batch = strategy.loadBatch(batchId);
        int validationErrorCount = 0;

        for (R row : rows) {
            validationErrorCount += strategy.stageRow(batch, row);
        }

        strategy.setTotalRows(batch, safeInt(strategy.getTotalRows(batch)) + rows.size());
        strategy.setValidationErrorCount(batch, safeInt(strategy.getValidationErrorCount(batch)) + validationErrorCount);
        entityManager.flush();
        entityManager.clear();
        return new StagingChunkResult(rows.size(), validationErrorCount);
    }

    private <R, S, B> int processNextStagedChunkInTransaction(UUID batchId, ChunkImportStrategy<R, S, B> strategy) {
        B batch = strategy.loadBatch(batchId);
        List<S> stagedRows = strategy.fetchNextUnprocessedChunk(batchId, PROCESSING_CHUNK_SIZE);
        if (stagedRows.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        int skippedCount = 0;

        for (S staged : stagedRows) {
            if (strategy.isValid(staged)) {
                strategy.applyRow(staged);
                processedCount++;
            } else {
                skippedCount++;
            }

            strategy.markProcessed(staged);
        }

        // Existing fallback logic: if totalRows is null, populate from the count query
        Integer currentTotal = strategy.getTotalRows(batch);
        strategy.setTotalRows(batch, currentTotal != null ? currentTotal : (int) strategy.countByBatchId(batchId));
        strategy.setProcessedRows(batch, safeInt(strategy.getProcessedRows(batch)) + processedCount);
        strategy.setSkippedRows(batch, safeInt(strategy.getSkippedRows(batch)) + skippedCount);

        LOG.debugf("processed=%d skipped=%d", processedCount, skippedCount);
        entityManager.flush();
        entityManager.clear();
        return stagedRows.size();
    }

    private static int safeInt(Integer v) {
        return v != null ? v : 0;
    }
}
