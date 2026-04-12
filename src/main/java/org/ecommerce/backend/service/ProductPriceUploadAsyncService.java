package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.UUID;

@ApplicationScoped
public class ProductPriceUploadAsyncService extends AbstractImportAsyncService {
    private static final Logger LOG = Logger.getLogger(ProductPriceUploadAsyncService.class);

    @Inject
    ProductPriceImportService productPriceImportService;

    @Inject
    DataSource dataSource;

    public void handleProductPriceCsvUploadAsync(InputStream is, UUID batchId) {
        runCsvStagingAsync(is, batchId);
    }

    public void processProductPriceImportRowsAsync(UUID batchId) {
        runRowProcessingAsync(batchId);
    }

    @Override
    protected Logger logger() {
        return LOG;
    }

    @Override
    protected AsyncImportOperations importOperations() {
        return productPriceImportService;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
