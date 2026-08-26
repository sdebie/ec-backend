package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.entity.ProductPriceImportBatchEntity;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps both import-batch entities to the one batch DTO the admin import screens read.
 * <p>
 * The two entities carry an identical progress block but share no supertype, so each needs
 * its own method. A product batch has no completion timestamp and no approver, so those two
 * fields stay absent on that side rather than being invented.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ImportBatchDtoMapper
{
    @Mapping(target = "status", source = "productUploadStatusEn")
    @Mapping(target = "importSourceType", source = "importSourceTypeEn")
    @Mapping(target = "uploadedByUsername", source = "uploadedBy.email")
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "approvedByUsername", ignore = true)
    ProductImportBatchDto fromProductBatch(ProductImportBatchEntity batch);

    @Mapping(target = "status", source = "productUploadStatusEn")
    @Mapping(target = "importSourceType", source = "importSourceTypeEn")
    @Mapping(target = "uploadedByUsername", source = "uploadedBy.email")
    @Mapping(target = "approvedByUsername", source = "approvedBy.email")
    ProductImportBatchDto fromProductPriceBatch(ProductPriceImportBatchEntity batch);
}
