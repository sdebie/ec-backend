package org.ecommerce.backend.service.import_engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

/**
 * Initializes the import engine on startup.
 * Registers all available strategies and orchestrators.
 */
@ApplicationScoped
public class ImportEngineConfiguration {
    private static final Logger LOG = Logger.getLogger(ImportEngineConfiguration.class);

    @Inject
    GenericImportAsyncService asyncService;

    @Inject
    ProductCsvImportStrategy productCsvStrategy;

    @Inject
    PriceCsvImportStrategy priceCsvStrategy;

    @Inject
    ProductImportOrchestrator productOrchestrator;

    @Inject
    ProductPriceImportOrchestrator priceOrchestrator;

    @Inject
    SagePriceImportStrategyImpl sagePriceStrategy;

    @Inject
    SageItemImportStrategyImpl sageItemStrategy;

    public void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing import engine...");

        // Register CSV import strategies
        asyncService.registerStrategy("product-csv", productCsvStrategy);
        asyncService.registerStrategy("price-csv", priceCsvStrategy);

        // Register Sage import strategies
        asyncService.registerStrategy("sage", sagePriceStrategy);           // Sage prices
        asyncService.registerStrategy("sage-items", sageItemStrategy);      // Sage items

        // Register orchestrators
        asyncService.registerOrchestrator("product-csv", productOrchestrator);
        asyncService.registerOrchestrator("price-csv", priceOrchestrator);
        asyncService.registerOrchestrator("sage", priceOrchestrator);        // Reuse price orchestrator for Sage prices
        asyncService.registerOrchestrator("sage-items", productOrchestrator); // Reuse product orchestrator for Sage items

        LOG.info("Import engine initialized with strategies: product-csv, price-csv, sage (prices), sage-items");
        LOG.info("Sage API integration enabled - uses SageApiClient for authentication and API calls");
        LOG.info("Ready to add other import sources via: asyncService.registerStrategy(type, strategy)");
    }
}
