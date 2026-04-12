package org.ecommerce.backend.service;

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
                batch.id,
                batch.filename,
                batch.productUploadStatusEn != null ? batch.productUploadStatusEn.toString() : null,
                batch.totalRows,
                batch.processedRows,
                batch.skippedRows,
                batch.validationErrorCount,
                batch.createdAt,
                batch.uploadedBy != null ? batch.uploadedBy.email : null
        );
    }

    public static ProductUploadBatchDto fromProductPriceBatch(ProductPriceUploadBatchEntity batch)
    {
        if (batch == null) {
            return null;
        }

        return map(
                batch.id,
                batch.filename,
                batch.productUploadStatusEn != null ? batch.productUploadStatusEn.toString() : null,
                batch.totalRows,
                batch.processedRows,
                batch.skippedRows,
                batch.validationErrorCount,
                batch.createdAt,
                batch.uploadedBy != null ? batch.uploadedBy.email : null
        );
    }

    private static ProductUploadBatchDto map(
            UUID id,
            String filename,
            String status,
            Integer totalRows,
            Integer processedRows,
            Integer skippedRows,
            Integer validationErrorCount,
            LocalDateTime createdAt,
            String uploadedByUsername
    )
    {
        ProductUploadBatchDto dto = new ProductUploadBatchDto();
        dto.id = id;
        dto.filename = filename;
        dto.status = status;
        dto.totalRows = totalRows;
        dto.processedRows = processedRows;
        dto.skippedRows = skippedRows;
        dto.validationErrorCount = validationErrorCount;
        dto.createdAt = createdAt;
        dto.uploadedByUsername = uploadedByUsername;
        return dto;
    }
}

