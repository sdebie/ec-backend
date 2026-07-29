package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.entity.ProductPriceUploadStagedEntity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps staged price-import rows to the comparison DTO the admin review screen shows.
 * Pure field copy — extracted from {@code ProductPriceImportService}.
 */
@ApplicationScoped
public class ProductPriceComparisonMapper
{

    public ProductPriceComparisonDto toDto(ProductPriceUploadStagedEntity productPriceUploadStagedEntity)
    {
        ProductPriceComparisonDto dto = new ProductPriceComparisonDto();
        dto.setStagedId(productPriceUploadStagedEntity.getId());
        dto.setSku(productPriceUploadStagedEntity.getSku());
        dto.setValidationErrors(productPriceUploadStagedEntity.getValidationErrors());
        dto.setValidationStatus(productPriceUploadStagedEntity.getValidationStatus());
        dto.setProposedRetailPrice(productPriceUploadStagedEntity.getRetailPrice());
        dto.setProposedWholesalePrice(productPriceUploadStagedEntity.getWholesalePrice());
        dto.setCurrentRetailPrice(productPriceUploadStagedEntity.getCurrentRetailPrice());
        dto.setCurrentWholesalePrice(productPriceUploadStagedEntity.getCurrentWholesalePrice());
        dto.setHasChanges(Boolean.TRUE.equals(productPriceUploadStagedEntity.getHasChanges()));
        return dto;
    }

    public List<ProductPriceComparisonDto> toDtos(List<ProductPriceUploadStagedEntity> productPriceUploadStagedEntities)
    {
        return productPriceUploadStagedEntities
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }
}
