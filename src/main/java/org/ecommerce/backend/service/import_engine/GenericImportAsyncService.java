package org.ecommerce.backend.service.import_engine;

import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal async orchestrator for any import strategy.
 * Handles CSV, Sage, or any future import source without duplication.
 */
@ApplicationScoped
public class GenericImportAsyncService {
    private static final Logger LOG = Logger.getLogger(GenericImportAsyncService.class);
    private static final String CACHED_PLAN_ERROR = "cached plan must not change result type";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Inject
    DataSource dataSource;

    // Registry of available strategies
    private final Map<String, ImportStrategy> strategyRegistry = new java.util.HashMap<>();

    // Registry of orchestrators (strategy type -> orchestrator)
    private final Map<String, ImportBatchOrchestrator> orchestratorRegistry = new java.util.HashMap<>();

    public void registerStrategy(String type, ImportStrategy strategy) {
        strategyRegistry.put(type, strategy);
        LOG.infof("Registered import strategy: %s", type);
    }

    public ImportStrategy getStrategy(String type) {
        ImportStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for type: " + type);
        }
        return strategy;
    }

    public void registerOrchestrator(String strategyType, ImportBatchOrchestrator orchestrator) {
        orchestratorRegistry.put(strategyType, orchestrator);
        LOG.infof("Registered orchestrator for strategy: %s", strategyType);
    }

    public ImportBatchOrchestrator getOrchestrator(String strategyType) {
        ImportBatchOrchestrator orchestrator = orchestratorRegistry.get(strategyType);
        if (orchestrator == null) {
            throw new IllegalArgumentException("No orchestrator registered for strategy: " + strategyType);
        }
        return orchestrator;
    }

    /**
     * Asynchronously stage rows from the given source using the appropriate strategy.
     */
    public void stageRowsAsync(String strategyType, InputStream source, UUID batchId) {
        executor.submit(() -> {
            try (InputStream inputStream = source) {
                ImportStrategy strategy = getStrategy(strategyType);
                ImportBatchOrchestrator orchestrator = getOrchestrator(strategyType);

                // Stage rows - each chunk creates its own transaction for independence
                // Do NOT wrap in outer transaction to avoid holding it open for the entire import
                ImportStrategy.ImportStageResult result;
                try {
                    result = strategy.stageRowsFromSource(inputStream, batchId);
                } catch (Exception ex) {
                    LOG.errorf(ex, "Failed to stage rows for batch %s using strategy %s", batchId, strategyType);
                    markBatchFailedSafely(strategyType, batchId);
                    return;
                }

                // Complete staging in its own transaction
                QuarkusTransaction.requiringNew().run(() -> {
                    try {
                        orchestrator.completeStaging(batchId, result.totalRows(), result.validationErrorCount());
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to complete staging", ex);
                    }
                });
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed async staging for batch %s using strategy %s", batchId, strategyType);
                markBatchFailedSafely(strategyType, batchId);
            }
        });
    }

    /**
     * Asynchronously process all staged rows using the appropriate strategy.
     */
    public void processRowsAsync(String strategyType, UUID batchId) {
        executor.submit(() -> {
            boolean retriedCachedPlanFailure = false;

            while (true) {
                try {
                    ImportStrategy strategy = getStrategy(strategyType);
                    ImportBatchOrchestrator orchestrator = getOrchestrator(strategyType);
                    orchestrator.processStagedRows(batchId, strategy);
                    QuarkusTransaction.requiringNew().run(() -> orchestrator.markBatchAsProcessed(batchId));
                    return;
                } catch (Exception ex) {
                    if (!retriedCachedPlanFailure && isCachedPlanError(ex)) {
                        retriedCachedPlanFailure = true;
                        LOG.warnf(ex, "Detected PostgreSQL cached-plan mismatch for batch %s, flushing datasource and retrying", batchId);
                        flushDatasourceConnections();
                        continue;
                    }

                    LOG.errorf(ex, "Failed processing batch %s with strategy %s", batchId, strategyType);
                    markBatchFailedSafely(strategyType, batchId);
                    return;
                }
            }
        });
    }

    private void markBatchFailedSafely(String strategyType, UUID batchId) {
        try {
            ImportBatchOrchestrator orchestrator = getOrchestrator(strategyType);
            QuarkusTransaction.requiringNew().run(() -> orchestrator.markBatchAsFailed(batchId));
        } catch (Exception statusEx) {
            LOG.errorf(statusEx, "Failed to mark batch %s as FAILED", batchId);
        }
    }

    private boolean isCachedPlanError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(CACHED_PLAN_ERROR)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void flushDatasourceConnections() {
        if (dataSource instanceof AgroalDataSource agroalDataSource) {
            agroalDataSource.flush(AgroalDataSource.FlushMode.ALL);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
