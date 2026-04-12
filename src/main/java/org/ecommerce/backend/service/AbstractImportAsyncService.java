package org.ecommerce.backend.service;

import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared async orchestration for product import-like flows.
 */
public abstract class AbstractImportAsyncService
{
    private static final String CACHED_PLAN_RESULT_TYPE_ERROR = "cached plan must not change result type";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    protected abstract Logger logger();

    protected abstract AsyncImportOperations importOperations();

    protected abstract DataSource dataSource();

    protected void runCsvStagingAsync(InputStream is, UUID batchId)
    {
        executor.submit(() -> {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    try {
                        importOperations().handleCsvUploadForBatch(is, batchId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception ex) {
                logger().errorf(ex, "Failed async CSV staging for batch %s", batchId);
                markFailedSafely(batchId);
            }
        });
    }

    protected void runRowProcessingAsync(UUID batchId)
    {
        executor.submit(() -> {
            boolean retriedCachedPlanFailure = false;

            while (true) {
                try {
                    QuarkusTransaction.requiringNew().run(() -> importOperations().processStagedRowsForBatch(batchId));
                    QuarkusTransaction.requiringNew().run(() -> importOperations().markBatchAsProcessed(batchId));
                    return;
                } catch (Exception ex) {
                    if (!retriedCachedPlanFailure && isCachedPlanResultTypeError(ex)) {
                        retriedCachedPlanFailure = true;
                        logger().warnf(ex, "Detected PostgreSQL cached-plan mismatch for batch %s, flushing datasource and retrying once", batchId);
                        flushDatasourceConnections();
                        continue;
                    }

                    logger().errorf(ex, "Failed staged product upload for batch %s", batchId);
                    markFailedSafely(batchId);
                    return;
                }
            }
        });
    }

    private void markFailedSafely(UUID batchId)
    {
        try {
            QuarkusTransaction.requiringNew().run(() -> importOperations().markBatchAsFailed(batchId));
        } catch (Exception statusEx) {
            logger().errorf(statusEx, "Failed to mark batch %s as FAILED", batchId);
        }
    }

    private boolean isCachedPlanResultTypeError(Throwable throwable)
    {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(CACHED_PLAN_RESULT_TYPE_ERROR)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void flushDatasourceConnections()
    {
        if (dataSource() instanceof AgroalDataSource agroalDataSource) {
            agroalDataSource.flush(AgroalDataSource.FlushMode.ALL);
        }
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdown();
    }
}

