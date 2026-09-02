package org.ecommerce.backend.service.import_engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.csv.SageItemValidator;
import org.ecommerce.backend.service.sage.SageApiClient;
import org.ecommerce.backend.service.sage.SageApiException;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sage ERP item import strategy.
 * Fetches item data (SKU, name, description, etc) from Sage API and stages it for import.
 * Uses SageApiClient to handle all API communication and authentication.
 *
 * Handles Sage pagination automatically, fetching all pages of results.
 */
@ApplicationScoped
public class SageItemImportStrategyImpl implements SageItemImportStrategy {
    private static final Logger LOG = Logger.getLogger(SageItemImportStrategyImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 10;

    @Inject
    SageApiClient sageApiClient;

    @Inject
    ProductImportBatchRepository batchRepository;

    @Inject
    ProductImportStagedRepository stagedRepository;

    @Inject
    SageItemValidator sageItemValidator;

    @Override
    public ImportStageResult stageRowsFromSource(InputStream source, UUID batchId) throws Exception {
        // Wrap entire operation in transaction context - allows nested transactions to work
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductImportBatchEntity batch = batchRepository.findById(batchId);
            if (batch == null) {
                throw new IllegalArgumentException("Batch not found: " + batchId);
            }

            try {
                return fetchAndStageSageItems(batch);
            } catch (SageApiException ex) {
                LOG.errorf(ex, "Failed to fetch items from Sage API for batch %s. Status: %d", batchId, ex.getStatusCode());
                throw ex;
            } catch (Exception ex) {
                LOG.errorf(ex, "Unexpected error fetching Sage items for batch %s", batchId);
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
        return "sage-items";
    }

    /**
     * Fetch item data from Sage and stage it for the batch.
     * Handles pagination automatically to fetch all items.
     */
    private ImportStageResult fetchAndStageSageItems(ProductImportBatchEntity batch) throws SageApiException {
        // Note: batch is already fetched, just use it as-is
        LOG.infof("Fetching items from Sage API for batch %s", batch.getId());

        int totalRows = 0;
        int validationErrors = 0;
        int pageNumber = 1;
        boolean hasMorePages = true;

        while (hasMorePages) {
            try {
                // Build query parameters for this page
                Map<String, String> params = new HashMap<>();
                params.put("$top", String.valueOf(PAGE_SIZE));
                params.put("$skip", String.valueOf((pageNumber - 1) * PAGE_SIZE));
                // Add filters as needed:
                // params.put("$filter", "Modified ge datetime'2026-08-01'");
                // params.put("$select", "ItemNumber,ItemName,ItemDescription");

                // Call Sage API
                LOG.debugf("Fetching Sage items - Page %d (skip %d, top %d)", pageNumber, (pageNumber - 1) * PAGE_SIZE, PAGE_SIZE);
                String sageResponse = sageApiClient.call("Item/Get", params);

                // Parse response and stage items
                List<SageItem> sageItems = parseSageResponse(sageResponse);
                LOG.infof("Parsed %d items from Sage page %d", sageItems.size(), pageNumber);

                if (sageItems.isEmpty()) {
                    hasMorePages = false;
                    break;
                }

                // Stage items for this page in its own transaction
                int pageRows = stageItemsInTransaction(batch, sageItems);
                totalRows += pageRows;

                // Update batch progress for UI tracking
                updateBatchProgress(batch, totalRows);

                // Check if there are more pages
                hasMorePages = sageItems.size() == PAGE_SIZE;
                pageNumber++;

            } catch (SageApiException ex) {
                LOG.errorf(ex, "Failed to fetch page %d of Sage items for batch %s", pageNumber, batch.getId());
                throw ex;
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to parse Sage response for page %d of batch %s", pageNumber, batch.getId());
                throw new RuntimeException("Failed to parse Sage API response: " + ex.getMessage(), ex);
            }
        }

        LOG.infof("Staged %d items from Sage (across %d pages) for batch %s", totalRows, pageNumber - 1, batch.getId());
        return new ImportStageResult(totalRows, validationErrors);
    }

    /**
     * Update batch progress for UI tracking after each page is staged.
     */
    private void updateBatchProgress(ProductImportBatchEntity batch, int totalRows) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // Re-fetch batch in this transaction since the original is detached
                ProductImportBatchEntity refreshedBatch = batchRepository.findById(batch.getId());
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
     * Stage a page of items from Sage in its own transaction.
     * This allows each page to be committed independently, preventing large transaction holdups.
     */
    private int stageItemsInTransaction(ProductImportBatchEntity batch, List<SageItem> sageItems) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                int rowCount = 0;
                for (SageItem sageItem : sageItems) {
                    ProductImportStagedEntity staged = new ProductImportStagedEntity();
                    staged.setBatch(batch);
                    staged.setProductSlug(sageItem.product_slug());
                    staged.setSku(sageItem.sku());
                    staged.setDescription(sageItem.description());
                    staged.setStock(sageItem.quantity_on_hand());

                    // Validate Sage item and detect changes
                    List<String> validationErrorsMsg = new ArrayList<>();
                    sageItemValidator.validateAndDiff(staged, validationErrorsMsg);
                    sageItemValidator.applyValidationResults(staged, validationErrorsMsg);

                    stagedRepository.persist(staged);
                    rowCount++;
                }
                return rowCount;
            });
        } catch (Exception ex) {
            throw new RuntimeException("Failed to stage Sage items page", ex);
        }
    }

    /**
     * Parse Sage API response into item objects.
     * Sage Item/Get API returns: { "TotalResults": N, "ReturnedResults": N, "Results": [...] }
     */
    private List<SageItem> parseSageResponse(String sageResponse) throws Exception {
        List<SageItem> items = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(sageResponse);
            JsonNode resultsNode = root.get("Results");

            if (resultsNode == null || !resultsNode.isArray()) {
                LOG.debugf("Sage response missing 'Results' array. Response: %s", sageResponse);
                return items;
            }

            for (JsonNode item : resultsNode) {
                String sku = item.has("Code") ? item.get("Code").asText() : null;
                if (sku == null || sku.isBlank()) {
                    LOG.debugf("Skipping Sage item without Code field");
                    continue;
                }

                String description = item.has("Description") ? item.get("Description").asText() : null;
                boolean active = item.has("Active") ? item.get("Active").asBoolean() : true;

                String product_slug = item.has("ID") ? item.get("ID").asText() : null;
                Integer quantity_on_hand = item.has("QuantityOnHand") ? item.get("QuantityOnHand").asInt() : null;
                String modified = item.has("Modified") ? item.get("Modified").asText() : null;

                items.add(new SageItem(sku, description, active, product_slug, quantity_on_hand, modified));
            }

            LOG.debugf("Parsed %d items from Sage response", items.size());
            return items;

        } catch (Exception ex) {
            LOG.errorf(ex, "Failed to parse Sage JSON response. Response: %s", sageResponse);
            throw ex;
        }
    }

    /**
     * Record representing an item from Sage API.
     */
    private record SageItem(
            String sku,
            String description,
            boolean active,
            String product_slug,
            Integer quantity_on_hand,
            String modified
    ) {
    }
}
