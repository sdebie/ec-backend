package org.ecommerce.backend.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.entity.ProductPriceImportStagedEntity;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-path integration tests for both import types (product and price).
 * <p>
 * Drives each service's public path:
 * CSV upload → staged → processed, asserting batch counter/state transitions.
 * Tests exercise the SERVICES, NOT the ChunkedImportStateMachine class alone.
 * <p>
 * Note: these tests use {@code QuarkusTransaction.requiringNew()} internally via
 * the services, so {@code @TestTransaction} would NOT roll back the inner
 * transactions. Instead, tests create unique batches and clean up after themselves.
 * <p>
 */
@QuarkusTest
class ProductImportRealPathIT
{
    @Inject
    ProductImportService productImportService;

    @Inject
    ProductPriceImportService productPriceImportService;

    @Inject
    ProductImportBatchRepository productImportBatchRepository;

    @Inject
    ProductImportStagedRepository productImportStagedRepository;

    @Inject
    ProductPriceImportBatchRepository productPriceImportBatchRepository;

    @Inject
    ProductPriceImportStagedRepository productPriceImportStagedRepository;

    // ═══════════════════════════════════════════════════════════════════════════
    // Product Import Service — real path
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProductImportService — CSV upload → stage → process")
    class ProductImportServiceTests
    {

        @Test
        @DisplayName("Happy path: staging a CSV transitions batch IMPORTING → PENDING with correct counters")
        void handleCsvUploadForBatch_stagesCsvAndTransitionsToPending() throws Exception
        {
            // GIVEN: a batch in IMPORTING state
            UUID batchId = createProductBatch();

            // CSV with 3 rows:
            //   Row 1: all required fields present (will still fail validation — no matching
            //           category/brand in DB — but it IS staged and counted)
            //   Row 2: missing name (validation error)
            //   Row 3: valid structure, invalid stock (parse error)
            String csv = """
                    sku,name,product_slug,category_slug,brand_slug,stock,images,attributes
                    TEST-SKU-001,Blue Tee,blue-tee,apparel,nike,10,,
                    TEST-SKU-002,,missing-name,apparel,nike,5,,
                    TEST-SKU-003,Red Tee,red-tee,apparel,nike,INVALID_STOCK,,
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // WHEN: staging the CSV
                productImportService.handleCsvUploadForBatch(is, batchId);

                // THEN: batch transitions to PENDING
                ProductImportBatchEntity batch = loadProductBatch(batchId);
                assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn(), "Batch should be PENDING after staging completes");

                // THEN: totalRows reflects the number of CSV rows staged
                assertEquals(3, batch.getTotalRows(), "totalRows should equal the number of CSV rows");

                // THEN: validationErrorCount > 0 (rows with validation failures)
                assertTrue(batch.getValidationErrorCount() > 0, "validationErrorCount should be > 0 since rows have missing fields/bad data");

                // THEN: staged rows exist in the database
                List<ProductImportStagedEntity> stagedRows = productImportStagedRepository.findByBatchId(batchId);
                assertEquals(3, stagedRows.size(), "All 3 rows should be staged");

            } finally {
                cleanupProductBatch(batchId);
            }
        }

        @Test
        @DisplayName("Processing staged rows transitions batch through counter updates")
        void processProductStagedRowsForBatch_updatesCounters() throws Exception
        {
            // GIVEN: a batch with staged rows (all will be INVALID since no real
            // categories/brands/products exist — but the processing still runs)
            UUID batchId = createProductBatch();

            String csv = """
                    sku,name,product_slug,category_slug,brand_slug,stock,images,attributes
                    TEST-PROC-001,Blue Tee,blue-tee,apparel,nike,10,,
                    TEST-PROC-002,,missing-name,apparel,nike,5,,
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // Stage the CSV first
                productImportService.handleCsvUploadForBatch(is, batchId);

                // Mark as PROCESSING (simulates the async service workflow)
                productImportService.markProductImportBatchAsProcessing(batchId);

                ProductImportBatchEntity batchBeforeProcessing = loadProductBatch(batchId);
                assertEquals(ProductUploadStatusEn.PROCESSING, batchBeforeProcessing.getProductUploadStatusEn(), "Batch should be PROCESSING after markAsProcessing");

                // WHEN: processing the staged rows
                productImportService.processProductStagedRowsForBatch(batchId);

                // THEN: counters are updated
                ProductImportBatchEntity batchAfter = loadProductBatch(batchId);
                assertNotNull(batchAfter.getTotalRows(), "totalRows should be set");
                assertTrue(batchAfter.getTotalRows() > 0, "totalRows should be > 0");

                // processedRows + skippedRows should equal totalRows after processing
                int processed = batchAfter.getProcessedRows() != null ? batchAfter.getProcessedRows() : 0;
                int skipped = batchAfter.getSkippedRows() != null ? batchAfter.getSkippedRows() : 0;
                assertEquals(batchAfter.getTotalRows().intValue(), processed + skipped, "processedRows + skippedRows should equal totalRows after full processing");

                // All staged rows should be marked as processed
                List<ProductImportStagedEntity> stagedRows = productImportStagedRepository.findByBatchId(batchId);
                for (ProductImportStagedEntity row : stagedRows) {
                    assertTrue(row.getProcessed(), "Every staged row should be marked processed");
                }

            } finally {
                cleanupProductBatch(batchId);
            }
        }

        @Test
        @DisplayName("Full lifecycle: IMPORTING → PENDING → PROCESSING → counters correct")
        void fullLifecycle_stateTransitionsAndCounters() throws Exception
        {
            UUID batchId = createProductBatch();

            // Mix of rows: one with all fields (still invalid — no DB match), one missing required fields
            String csv = """
                    sku,name,product_slug,category_slug,brand_slug,stock,images,attributes
                    TEST-FULL-001,Test Product,test-prod,electronics,sony,50,,
                    TEST-FULL-002,,no-name,,,,BAD_STOCK,,
                    TEST-FULL-003,Another,another-prod,food,generic,10,,
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // Phase 1: Upload → PENDING
                productImportService.handleCsvUploadForBatch(is, batchId);
                ProductImportBatchEntity afterStaging = loadProductBatch(batchId);
                assertEquals(ProductUploadStatusEn.PENDING, afterStaging.getProductUploadStatusEn());
                assertEquals(3, afterStaging.getTotalRows());

                // Phase 2: Mark PROCESSING
                productImportService.markProductImportBatchAsProcessing(batchId);
                ProductImportBatchEntity afterMarking = loadProductBatch(batchId);
                assertEquals(ProductUploadStatusEn.PROCESSING, afterMarking.getProductUploadStatusEn());
                assertEquals(0, afterMarking.getProcessedRows());
                assertEquals(0, afterMarking.getSkippedRows());

                // Phase 3: Process
                productImportService.processProductStagedRowsForBatch(batchId);
                ProductImportBatchEntity afterProcessing = loadProductBatch(batchId);

                // After synchronizeBatchProgress, counters must be consistent
                int total = afterProcessing.getTotalRows();
                int proc = afterProcessing.getProcessedRows() != null ? afterProcessing.getProcessedRows() : 0;
                int skip = afterProcessing.getSkippedRows() != null ? afterProcessing.getSkippedRows() : 0;
                assertEquals(total, proc + skip, "After processing, processedRows + skippedRows must equal totalRows");
                assertTrue(total > 0);

            } finally {
                cleanupProductBatch(batchId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Product Price Import Service — real path
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProductPriceImportService — CSV upload → stage → process")
    class ProductPriceImportServiceTests
    {

        @Test
        @DisplayName("Happy path: staging a price CSV transitions batch IMPORTING → PENDING with correct counters")
        void handleProductPriceCsvUploadForBatch_stagesCsvAndTransitionsToPending() throws Exception
        {
            // GIVEN: a price batch in IMPORTING state
            UUID batchId = createPriceBatch();

            // CSV with 3 rows:
            //   Row 1: valid SKU format but no matching variant in DB → validation error
            //   Row 2: blank SKU → "sku is required" error
            //   Row 3: another valid SKU format, no match → validation error
            String csv = """
                    sku,retail_price,wholesale_price
                    TEST-PRICE-SKU-001,299.00,150.00
                    ,199.00,99.00
                    TEST-PRICE-SKU-003,399.00,200.00
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // WHEN: staging the CSV
                productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

                // THEN: batch transitions to PENDING
                ProductPriceImportBatchEntity batch = loadPriceBatch(batchId);
                assertEquals(ProductUploadStatusEn.PENDING, batch.getProductUploadStatusEn(), "Batch should be PENDING after staging completes");

                // THEN: totalRows reflects the number of CSV rows staged
                assertEquals(3, batch.getTotalRows(), "totalRows should equal the number of CSV rows");

                // THEN: validationErrorCount > 0 (rows with validation failures)
                assertTrue(batch.getValidationErrorCount() > 0, "validationErrorCount should be > 0 since SKUs don't exist in DB");

                // THEN: staged rows exist in the database
                List<ProductPriceImportStagedEntity> stagedRows = productPriceImportStagedRepository.findByBatchId(batchId);
                assertEquals(3, stagedRows.size(), "All 3 rows should be staged");

            } finally {
                cleanupPriceBatch(batchId);
            }
        }

        @Test
        @DisplayName("Processing staged price rows transitions batch through counter updates")
        void processProductPriceStagedRowsForBatch_updatesCounters() throws Exception
        {
            // GIVEN: a price batch with staged rows (all INVALID — no matching variants)
            UUID batchId = createPriceBatch();

            String csv = """
                    sku,retail_price,wholesale_price
                    TEST-PPROC-001,100.00,50.00
                    TEST-PPROC-002,200.00,100.00
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // Stage first
                productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);

                // Mark PROCESSING
                productPriceImportService.markProductPriceImportBatchAsProcessing(batchId, null);

                ProductPriceImportBatchEntity batchBeforeProcessing = loadPriceBatch(batchId);
                assertEquals(ProductUploadStatusEn.PROCESSING, batchBeforeProcessing.getProductUploadStatusEn());

                // WHEN: processing
                productPriceImportService.processProductPriceStagedRowsForBatch(batchId);

                // THEN: counters are updated
                ProductPriceImportBatchEntity batchAfter = loadPriceBatch(batchId);
                assertNotNull(batchAfter.getTotalRows(), "totalRows should be set");
                assertTrue(batchAfter.getTotalRows() > 0, "totalRows should be > 0");

                // processedRows + skippedRows should equal totalRows
                int processed = batchAfter.getProcessedRows() != null ? batchAfter.getProcessedRows() : 0;
                int skipped = batchAfter.getSkippedRows() != null ? batchAfter.getSkippedRows() : 0;
                assertEquals(batchAfter.getTotalRows().intValue(), processed + skipped,
                        "processedRows + skippedRows should equal totalRows after full processing");

                // All staged rows should be marked as processed
                List<ProductPriceImportStagedEntity> stagedRows = productPriceImportStagedRepository.findByBatchId(batchId);
                for (ProductPriceImportStagedEntity row : stagedRows) {
                    assertTrue(row.getProcessed(), "Every staged row should be marked processed");
                }

            } finally {
                cleanupPriceBatch(batchId);
            }
        }

        @Test
        @DisplayName("Full lifecycle: IMPORTING → PENDING → PROCESSING → counters correct")
        void fullLifecycle_stateTransitionsAndCounters() throws Exception
        {
            UUID batchId = createPriceBatch();

            String csv = """
                    sku,retail_price,wholesale_price
                    TEST-PFULL-001,500.00,250.00
                    ,300.00,150.00
                    TEST-PFULL-003,400.00,200.00
                    """;

            InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

            try {
                // Phase 1: Upload → PENDING
                productPriceImportService.handleProductPriceCsvUploadForBatch(is, batchId);
                ProductPriceImportBatchEntity afterStaging = loadPriceBatch(batchId);
                assertEquals(ProductUploadStatusEn.PENDING, afterStaging.getProductUploadStatusEn());
                assertEquals(3, afterStaging.getTotalRows());

                // Phase 2: Mark PROCESSING
                productPriceImportService.markProductPriceImportBatchAsProcessing(batchId, null);
                ProductPriceImportBatchEntity afterMarking = loadPriceBatch(batchId);
                assertEquals(ProductUploadStatusEn.PROCESSING, afterMarking.getProductUploadStatusEn());
                assertEquals(0, afterMarking.getProcessedRows());
                assertEquals(0, afterMarking.getSkippedRows());

                // Phase 3: Process
                productPriceImportService.processProductPriceStagedRowsForBatch(batchId);
                ProductPriceImportBatchEntity afterProcessing = loadPriceBatch(batchId);

                // After synchronizeBatchProgress, counters must be consistent
                int total = afterProcessing.getTotalRows();
                int proc = afterProcessing.getProcessedRows() != null ? afterProcessing.getProcessedRows() : 0;
                int skip = afterProcessing.getSkippedRows() != null ? afterProcessing.getSkippedRows() : 0;
                assertEquals(total, proc + skip, "After processing, processedRows + skippedRows must equal totalRows");
                assertTrue(total > 0);

            } finally {
                cleanupPriceBatch(batchId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers — batch creation and cleanup
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    UUID createProductBatch()
    {
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setFilename("test-import-" + UUID.randomUUID() + ".csv");
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productImportBatchRepository.persist(batch);
        return batch.getId();
    }

    @Transactional
    UUID createPriceBatch()
    {
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setFilename("test-price-import-" + UUID.randomUUID() + ".csv");
        batch.setProductUploadStatusEn(ProductUploadStatusEn.IMPORTING);
        batch.setTotalRows(0);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);
        batch.setValidationErrorCount(0);
        productPriceImportBatchRepository.persist(batch);
        return batch.getId();
    }

    @Transactional
    ProductImportBatchEntity loadProductBatch(UUID batchId)
    {
        return productImportBatchRepository.findById(batchId);
    }

    @Transactional
    ProductPriceImportBatchEntity loadPriceBatch(UUID batchId)
    {
        return productPriceImportBatchRepository.findById(batchId);
    }

    @Transactional
    void cleanupProductBatch(UUID batchId)
    {
        productImportStagedRepository.delete("batch.id", batchId);
        productImportBatchRepository.delete("id", batchId);
    }

    @Transactional
    void cleanupPriceBatch(UUID batchId)
    {
        productPriceImportStagedRepository.delete("batch.id", batchId);
        productPriceImportBatchRepository.delete("id", batchId);
    }
}
