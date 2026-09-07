package org.ecommerce.backend.service.import_engine;

import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPriceImportOrchestratorTest
{
    @Test
    void overlayMissingProgressFillsCountsFromStagedRowsForAProcessedBatch() {
        UUID batchId = UUID.randomUUID();
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setId(batchId);
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);
        batch.setProcessedRows(0);
        batch.setSkippedRows(0);

        ProductImportBatchDto dto = new ProductImportBatchDto();
        dto.setProcessedRows(0);
        dto.setSkippedRows(0);

        ProductPriceImportStagedRepository stagedRepository = mock(ProductPriceImportStagedRepository.class);
        when(stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.VALID)).thenReturn(12L);
        when(stagedRepository.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.INVALID)).thenReturn(3L);

        ProductPriceImportOrchestrator orchestrator = new ProductPriceImportOrchestrator();
        orchestrator.stagedRepository = stagedRepository;

        orchestrator.overlayMissingProgress(batch, dto);

        assertEquals(12, dto.getProcessedRows());
        assertEquals(3, dto.getSkippedRows());
    }

    @Test
    void overlayMissingProgressLeavesCountsAloneWhenAlreadyRecorded() {
        ProductPriceImportBatchEntity batch = new ProductPriceImportBatchEntity();
        batch.setProductUploadStatusEn(ProductUploadStatusEn.PROCESSED);

        ProductImportBatchDto dto = new ProductImportBatchDto();
        dto.setProcessedRows(8);
        dto.setSkippedRows(1);

        ProductPriceImportStagedRepository stagedRepository = mock(ProductPriceImportStagedRepository.class);
        ProductPriceImportOrchestrator orchestrator = new ProductPriceImportOrchestrator();
        orchestrator.stagedRepository = stagedRepository;

        orchestrator.overlayMissingProgress(batch, dto);

        assertEquals(8, dto.getProcessedRows());
        assertEquals(1, dto.getSkippedRows());
    }
}
