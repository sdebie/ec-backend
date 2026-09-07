package org.ecommerce.backend.service.import_engine;

import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.ImportSourceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductImportOrchestratorTest
{
    @Test
    void processNextChunkRecordsProcessedAndSkippedCounts() {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);

        ProductImportStagedEntity valid = stagedRow("SKU-VALID", ProductImportValidationStatusEn.VALID);
        ProductImportStagedEntity invalid = stagedRow("SKU-INVALID", ProductImportValidationStatusEn.INVALID);

        ProductImportBatchRepository batchRepository = mock(ProductImportBatchRepository.class);
        ProductImportStagedRepository stagedRepository = mock(ProductImportStagedRepository.class);
        ProductVariantRepository variantRepository = mock(ProductVariantRepository.class);
        when(stagedRepository.findNextUnprocessedByBatchId(batchId, 1000)).thenReturn(List.of(valid, invalid));
        when(batchRepository.findById(batchId)).thenReturn(batch);
        when(variantRepository.findBySku("SKU-VALID")).thenReturn(new ProductVariantEntity());

        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = batchRepository;
        orchestrator.stagedRepository = stagedRepository;
        orchestrator.variantRepository = variantRepository;

        int chunkSize = orchestrator.processNextChunk(batchId, 1000);

        assertEquals(2, chunkSize);
        assertEquals(1, batch.getProcessedRows());
        assertEquals(1, batch.getSkippedRows());
        assertTrue(valid.getProcessed());
        assertTrue(invalid.getProcessed());
    }

    @Test
    void processNextChunkAddsToExistingCounts() {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setProcessedRows(4);
        batch.setSkippedRows(2);

        ProductImportStagedEntity valid = stagedRow("SKU-VALID", ProductImportValidationStatusEn.VALID);

        ProductImportBatchRepository batchRepository = mock(ProductImportBatchRepository.class);
        ProductImportStagedRepository stagedRepository = mock(ProductImportStagedRepository.class);
        ProductVariantRepository variantRepository = mock(ProductVariantRepository.class);
        when(stagedRepository.findNextUnprocessedByBatchId(batchId, 1000)).thenReturn(List.of(valid));
        when(batchRepository.findById(batchId)).thenReturn(batch);
        when(variantRepository.findBySku("SKU-VALID")).thenReturn(new ProductVariantEntity());

        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = batchRepository;
        orchestrator.stagedRepository = stagedRepository;
        orchestrator.variantRepository = variantRepository;

        orchestrator.processNextChunk(batchId, 1000);

        assertEquals(5, batch.getProcessedRows());
        assertEquals(2, batch.getSkippedRows());
    }

    @Test
    void overlayMissingProgressFillsCountsFromStagedRowsForAProcessedBatch() {
        UUID batchId = UUID.randomUUID();
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setId(batchId);
        batch.setProductUploadStatusEn(org.ecommerce.common.enums.ProductUploadStatusEn.PROCESSED);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);

        ProductImportBatchDto dto = new ProductImportBatchDto();
        dto.setProcessedRows(0);
        dto.setSkippedRows(0);

        ProductImportStagedRepository stagedRepository = mock(ProductImportStagedRepository.class);
        when(stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.VALID)).thenReturn(12L);
        when(stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.INVALID)).thenReturn(3L);

        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.stagedRepository = stagedRepository;

        orchestrator.overlayMissingProgress(batch, dto);

        assertEquals(12, dto.getProcessedRows());
        assertEquals(3, dto.getSkippedRows());
    }

    @Test
    void overlayMissingProgressLeavesCountsAloneWhenAlreadyRecorded() {
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setProductUploadStatusEn(org.ecommerce.common.enums.ProductUploadStatusEn.PROCESSED);

        ProductImportBatchDto dto = new ProductImportBatchDto();
        dto.setProcessedRows(8);
        dto.setSkippedRows(1);

        ProductImportStagedRepository stagedRepository = mock(ProductImportStagedRepository.class);
        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.stagedRepository = stagedRepository;

        orchestrator.overlayMissingProgress(batch, dto);

        assertEquals(8, dto.getProcessedRows());
        assertEquals(1, dto.getSkippedRows());
    }

    private static ProductImportStagedEntity stagedRow(String sku, ProductImportValidationStatusEn status) {
        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setSku(sku);
        staged.setValidationStatus(status);
        staged.setProcessed(false);
        return staged;
    }

    @Test
    void sageBatchStoresSageSourceTypeNotFile() {
        ProductImportBatchRepository repository = mock(ProductImportBatchRepository.class);
        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = repository;

        ProductImportBatchEntity batch = orchestrator.createPendingBatch("Sage Item Import", new StaffUserEntity(), ImportSourceTypeEn.SAGE);

        assertEquals(ImportSourceTypeEn.SAGE, batch.getImportSourceTypeEn());
        verify(repository).persist(batch);
    }

    @Test
    void csvBatchStoresFileSourceType() {
        ProductImportBatchRepository repository = mock(ProductImportBatchRepository.class);
        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = repository;

        ProductImportBatchEntity batch = orchestrator.createPendingBatch("products.csv", new StaffUserEntity(), ImportSourceTypeEn.FILE);

        assertEquals(ImportSourceTypeEn.FILE, batch.getImportSourceTypeEn());
    }
}
