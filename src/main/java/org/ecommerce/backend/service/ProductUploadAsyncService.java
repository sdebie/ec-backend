package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.UUID;

@ApplicationScoped
public class ProductUploadAsyncService extends AbstractImportAsyncService {

    private static final Logger LOG = Logger.getLogger(ProductUploadAsyncService.class);

    @Inject
    ProductImportService importService;

    @Inject
    DataSource dataSource;

    public void handleProductCsvUploadAsync(InputStream is, UUID batchId) {
        runCsvStagingAsync(is, batchId);
    }

    public void processProductImportRowsAsync(UUID batchId) {
        runRowProcessingAsync(batchId);
    }

    @Override
    protected Logger logger() {
        return LOG;
    }

    @Override
    protected AsyncImportOperations importOperations() {
        return importService;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
