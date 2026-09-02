package org.ecommerce.backend.service.import_engine;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.UUID;

/**
 * Placeholder for Sage ERP price import strategy.
 * Demonstrates how to add a new import source.
 *
 * Implementation should:
 * 1. Connect to Sage API
 * 2. Fetch price data
 * 3. Parse and validate
 * 4. Stage rows into ProductPriceImportStagedEntity
 */
@ApplicationScoped
public class SagePriceImportStrategy implements ImportStrategy {
    private static final Logger LOG = Logger.getLogger(SagePriceImportStrategy.class);

    @Override
    public ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception {
        // TODO: Implement Sage API integration
        // 1. Authenticate with Sage API (credentials from config)
        // 2. Fetch price updates for the batch period
        // 3. Transform Sage data format to ProductPriceImportStagedEntity
        // 4. Validate prices before staging
        // 5. Persist to staging table

        LOG.info("SagePriceImportStrategy not yet implemented. Stub only.");
        return new ImportStageResult(0, 0);
    }

    @Override
    public void applyStagedRow(UUID batchId, Object stagedRowId) {
        // Strategy pattern allows Sage to define custom row application logic
        // Reuse default price application from ProductPriceImportOrchestrator
    }

    @Override
    public String getType() {
        return "sage";
    }
}
