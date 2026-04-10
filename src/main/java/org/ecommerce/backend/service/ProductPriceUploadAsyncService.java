package org.ecommerce.backend.service;

import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class ProductPriceUploadAsyncService {
    private static final Logger LOG = Logger.getLogger(ProductUploadAsyncService.class);
    private static final String CACHED_PLAN_RESULT_TYPE_ERROR = "cached plan must not change result type";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Inject
    ProductPriceImportService productPriceimportService;

    @Inject
    DataSource dataSource;

    public void handleProductPriceCsvUploadAsync(InputStream is, UUID batchId) {
        executor.submit(() -> {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    try {
                        productPriceimportService.handleProductPriceCsvUploadForBatch(is, batchId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed async CSV staging for batch %s", batchId);
                try {
                    QuarkusTransaction.requiringNew().run(() -> productPriceimportService.markProductPriceBatchAsFailed(batchId));
                } catch (Exception statusEx) {
                    LOG.errorf(statusEx, "Failed to mark batch %s as FAILED", batchId);
                }
            }
        });
    }

    public void processProductPriceImportRowsAsync(UUID batchId) {
        executor.submit(() -> {
            boolean retriedCachedPlanFailure = false;

            while (true) {
                try {
                    QuarkusTransaction.requiringNew().run(() -> productPriceimportService.processProductPriceStagedRowsForBatch(batchId));
                    QuarkusTransaction.requiringNew().run(() -> productPriceimportService.markProductPriceBatchAsProcessed(batchId));
                    return;
                } catch (Exception ex) {
                    if (!retriedCachedPlanFailure && isCachedPlanResultTypeError(ex)) {
                        retriedCachedPlanFailure = true;
                        LOG.warnf(ex, "Detected PostgreSQL cached-plan mismatch for batch %s, flushing datasource and retrying once", batchId);
                        flushDatasourceConnections();
                        continue;
                    }

                    LOG.errorf(ex, "Failed staged product upload for batch %s", batchId);
                    try {
                        QuarkusTransaction.requiringNew().run(() -> productPriceimportService.markProductPriceBatchAsFailed(batchId));
                    } catch (Exception statusEx) {
                        LOG.errorf(statusEx, "Failed to mark batch %s as REJECTED", batchId);
                    }
                    return;
                }
            }
        });
    }

    private boolean isCachedPlanResultTypeError(Throwable throwable) {
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
