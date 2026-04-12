package org.ecommerce.backend.service;

import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.entity.StaffUserEntity;

import java.util.List;
import java.util.UUID;

/**
 * Shared contract for product import-style batch services.
 */
public interface ImportBatchService<ROW_DTO, STATUS_DTO, BATCH_ENTITY>
{
    BATCH_ENTITY createImportPendingBatch(String filename, StaffUserEntity admin);

    void markImportBatchAsProcessing(UUID batchId);

    void markImportBatchAsProcessed(UUID batchId);

    void markImportBatchAsFailed(UUID batchId);

    STATUS_DTO getImportBatchProcessStatus(UUID batchId);

    List<ROW_DTO> getImportRows(UUID batchId);

    List<ProductUploadBatchDto> getUploadBatches();
}

