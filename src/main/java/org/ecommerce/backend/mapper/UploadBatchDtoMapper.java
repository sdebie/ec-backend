package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.entity.ProductPriceUploadBatchEntity;
import org.ecommerce.common.entity.ProductUploadBatchEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared mapper for upload-batch entities to GraphQL/REST DTOs.
 */
public final class UploadBatchDtoMapper
{
    private UploadBatchDtoMapper()
    {
    }

    public static ProductUploadBatchDto fromProductBatch(ProductUploadBatchEntity batch)
    {
        if (batch == null) {
            return null;
        }

        return map(
                batch.getId(),
                batch.getFilename(),
                batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().toString() : null,
                batch.getTotalRows(),
                batch.getProcessedRows(),
                batch.getSkippedRows(),
                batch.getValidationErrorCount(),
                batch.getCreatedAt(),
                null,
                batch.getUploadedBy() != null ? batch.getUploadedBy().getEmail() : null,
                null
        );
    }

    public static ProductUploadBatchDto fromProductPriceBatch(ProductPriceUploadBatchEntity batch)
    {
        if (batch == null) {
            return null;
        }

        return map(
                batch.getId(),
                batch.getFilename(),
                batch.getProductUploadStatusEn() != null ? batch.getProductUploadStatusEn().toString() : null,
                batch.getTotalRows(),
                batch.getProcessedRows(),
                batch.getSkippedRows(),
                batch.getValidationErrorCount(),
                batch.getCreatedAt(),
                batch.getCompletedAt(),
                batch.getUploadedBy() != null ? batch.getUploadedBy().getEmail() : null,
                batch.getApprovedBy() != null ? batch.getApprovedBy().getEmail() : null
        );
    }

    private static ProductUploadBatchDto map(UUID id, String filename, String status, Integer totalRows, Integer processedRows, Integer skippedRows, Integer validationErrorCount, LocalDateTime createdAt, LocalDateTime completedAt, String uploadedByUsername, String approvedByUsername)
    {
        ProductUploadBatchDto dto = new ProductUploadBatchDto();
        dto.setId(id);
        dto.setFilename(filename);
        dto.setStatus(status);
        dto.setTotalRows(totalRows);
        dto.setProcessedRows(processedRows);
        dto.setSkippedRows(skippedRows);
        dto.setValidationErrorCount(validationErrorCount);
        dto.setCreatedAt(createdAt);
        dto.setCompletedAt(completedAt);
        dto.setUploadedByUsername(uploadedByUsername);
        dto.setApprovedByUsername(approvedByUsername);
        return dto;
    }
}
