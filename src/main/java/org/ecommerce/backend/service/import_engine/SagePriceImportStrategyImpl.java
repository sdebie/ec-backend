package org.ecommerce.backend.service.import_engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.service.sage.SageApiClient;
import org.ecommerce.backend.service.sage.SageApiException;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.entity.ProductPriceImportStagedEntity;
import org.ecommerce.common.entity.SageSettingsEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sage ERP price import strategy.
 * Fetches price data from Sage API and stages it for import.
 * Uses SageApiClient to handle all API communication.
 */
@ApplicationScoped
public class SagePriceImportStrategyImpl implements ImportStrategy {
    private static final Logger LOG = Logger.getLogger(SagePriceImportStrategyImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SageApiClient sageApiClient;

    @Inject
    ProductPriceImportBatchRepository batchRepository;

    @Inject
    ProductPriceImportStagedRepository stagedRepository;

    @Override
    public ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception {
        // Wrap entire operation in transaction context - allows nested transactions to work
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductPriceImportBatchEntity batch = batchRepository.findById(batchId);
            if (batch == null) {
                throw new IllegalArgumentException("Batch not found: " + batchId);
            }

            try {
                return fetchAndStageSagePrices(batch);
            } catch (SageApiException ex) {
                LOG.errorf(ex, "Failed to fetch prices from Sage API for batch %s. Status: %d", batchId, ex.getStatusCode());
                throw ex;
            } catch (Exception ex) {
                LOG.errorf(ex, "Unexpected error fetching Sage prices for batch %s", batchId);
                throw ex;
            }
        });
    }

    @Override
    public void applyStagedRow(UUID batchId, Object stagedRowId) {
        // Not used in current flow; processing handled by orchestrator
    }

    @Override
    public String getType() {
        return "sage";
    }

    /**
     * Fetch price data from Sage and stage it for the batch.
     * Calls Item/Get API endpoint and extracts prices using configured price list IDs.
     */
    private ImportStageResult fetchAndStageSagePrices(ProductPriceImportBatchEntity batch) throws SageApiException {
        // Note: batch is already fetched, just use it as-is
        LOG.infof("Fetching prices from Sage API for batch %s", batch.getId());

        // Get Sage settings for price list ID mapping
        SageSettingsEntity settings = SageSettingsEntity.findAll().firstResult();
        if (settings == null) {
            throw new IllegalStateException("Sage settings not configured");
        }

        // Build Sage API query parameters
        Map<String, String> params = new HashMap<>();
        params.put("$top", "1000");  // Fetch up to 1000 items per call
        // You can add filters like:
        // params.put("$filter", "Modified ge datetime'2026-08-01'");
        // params.put("$select", "Code,Description,PriceListReportLineItems");

        try {
            // Call Sage API using SageApiClient
            String sageResponse = sageApiClient.call("Item/Get", params);
            LOG.debugf("Sage API response received for batch %s", batch.getId());

            // Parse response and stage prices
            List<SagePrice> sageItems = parseSageResponse(sageResponse, settings.getRetailId(), settings.getWholesaleId());
            LOG.infof("Parsed %d items from Sage response", sageItems.size());

            // Stage prices in its own transaction
            int totalRows = stagePricesInTransaction(batch, sageItems);
            int validationErrors = 0;

            // Update batch progress for UI tracking
            updateBatchProgress(batch, totalRows);

            LOG.infof("Staged %d prices from Sage for batch %s", totalRows, batch.getId());
            return new ImportStageResult(totalRows, validationErrors);

        } catch (SageApiException ex) {
            throw ex;
        } catch (Exception ex) {
            LOG.errorf(ex, "Failed to parse Sage response for batch %s", batch.getId());
            throw new RuntimeException("Failed to parse Sage API response: " + ex.getMessage(), ex);
        }
    }

    /**
     * Update batch progress for UI tracking.
     */
    private void updateBatchProgress(ProductPriceImportBatchEntity batch, int totalRows) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // Re-fetch batch in this transaction since the original is detached
                ProductPriceImportBatchEntity refreshedBatch = batchRepository.findById(batch.getId());
                if (refreshedBatch != null) {
                    refreshedBatch.setTotalRows(totalRows);
                    batchRepository.persist(refreshedBatch);
                }
            });
        } catch (Exception ex) {
            LOG.warnf(ex, "Failed to update batch progress for %s", batch.getId());
        }
    }

    /**
     * Stage prices from Sage in its own transaction.
     * This allows the transaction to be committed independently, preventing large transaction holdups.
     */
    private int stagePricesInTransaction(ProductPriceImportBatchEntity batch, List<SagePrice> sageItems) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                int rowCount = 0;
                for (SagePrice sageItem : sageItems) {
                    ProductPriceImportStagedEntity staged = new ProductPriceImportStagedEntity();
                    staged.setBatch(batch);
                    staged.setSku(sageItem.sku());
                    staged.setRetailPrice(sageItem.retailPrice());
                    staged.setWholesalePrice(sageItem.wholesalePrice());

                    // Mark as valid (Sage provides authoritative data)
                    staged.setValidationStatus(ProductImportValidationStatusEn.VALID);

                    stagedRepository.persist(staged);
                    rowCount++;
                }
                return rowCount;
            });
        } catch (Exception ex) {
            throw new RuntimeException("Failed to stage Sage prices", ex);
        }
    }

    /**
     * Parse Sage API price response into price objects.
     * Sage response is a direct array with PriceListReportLineItems containing prices by price list ID.
     * Retail and wholesale prices are identified by matching AdditionalPriceListID with configured IDs.
     */
    private List<SagePrice> parseSageResponse(String sageResponse, String retailId, String wholesaleId) throws Exception {
        List<SagePrice> prices = new ArrayList<>();

        try {
            JsonNode root = MAPPER.readTree(sageResponse);

            // Response is a direct array, not nested in an object
            if (!root.isArray()) {
                LOG.warnf("Sage price response is not an array. Response: %s", sageResponse);
                return prices;
            }

            for (JsonNode item : root) {
                String sku = item.has("Code") ? item.get("Code").asText() : null;
                if (sku == null || sku.isBlank()) {
                    LOG.debugf("Skipping Sage item without Code field");
                    continue;
                }

                // Extract prices from PriceListReportLineItems
                BigDecimal retailPrice = BigDecimal.ZERO;
                BigDecimal wholesalePrice = BigDecimal.ZERO;

                JsonNode priceListItems = item.get("PriceListReportLineItems");
                if (priceListItems != null && priceListItems.isArray()) {
                    for (JsonNode priceItem : priceListItems) {
                        String priceListId = priceItem.has("AdditionalPriceListID")
                                ? priceItem.get("AdditionalPriceListID").asText()
                                : null;
                        BigDecimal unitPrice = priceItem.has("UnitPrice")
                                ? new BigDecimal(priceItem.get("UnitPrice").asDouble())
                                : BigDecimal.ZERO;

                        if (retailId != null && retailId.equals(priceListId)) {
                            retailPrice = unitPrice;
                        } else if (wholesaleId != null && wholesaleId.equals(priceListId)) {
                            wholesalePrice = unitPrice;
                        }
                    }
                }

                prices.add(new SagePrice(sku, retailPrice, wholesalePrice));
            }

            LOG.debugf("Parsed %d prices from Sage response", prices.size());
            return prices;

        } catch (Exception ex) {
            LOG.errorf(ex, "Failed to parse Sage JSON response. Response: %s", sageResponse);
            throw ex;
        }
    }

    /**
     * Record representing a price from Sage API.
     */
    private record SagePrice(
            String sku,
            BigDecimal retailPrice,
            BigDecimal wholesalePrice
    ) {
    }
}
