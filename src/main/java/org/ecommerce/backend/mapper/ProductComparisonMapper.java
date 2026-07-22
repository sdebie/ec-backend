package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.entity.ProductUploadStagedEntity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps staged product-import rows to the comparison DTO the admin review screen shows.
 * Pure field copy — extracted from {@code ProductImportService} so the service stays
 * focused on orchestration.
 */
@ApplicationScoped
public class ProductComparisonMapper
{

    public ProductComparisonDto toDto(ProductUploadStagedEntity staged)
    {
        ProductComparisonDto dto = new ProductComparisonDto();
        dto.setStagedId(staged.getId());
        dto.setSku(staged.getSku());
        dto.setProposedName(staged.getName());
        dto.setProposedDescription(staged.getDescription());
        dto.setProposedShortDescription(staged.getShortDescription());
        dto.setCategorySlug(staged.getCategorySlug());
        dto.setBrandSlug(staged.getBrandSlug());
        dto.setProposedImages(staged.getImages());
        dto.setProposedStock(staged.getStock());
        dto.setProposedAttributes(staged.getAttributes());
        dto.setValidationErrors(staged.getValidationErrors());
        dto.setValidationStatus(staged.getValidationStatus());
        dto.setImageErrors(staged.getImageErrors());
        dto.setValidCategory(staged.getIsValidCategory());
        dto.setValidBrand(staged.getIsValidBrand());
        dto.setNewProduct(staged.getIsNewProduct());
        dto.setNewVariant(staged.getIsNewVariant());
        dto.setHasChanges(Boolean.TRUE.equals(staged.getHasChanges()));

        // Persisted current values captured at import time
        dto.setCurrentName(staged.getCurrentName());
        dto.setCurrentDescription(staged.getCurrentDescription());
        dto.setCurrentShortDescription(staged.getCurrentShortDescription());
        dto.setCurrentStock(staged.getCurrentStock());
        dto.setCurrentImages(staged.getCurrentImages());
        dto.setCurrentAttributes(staged.getCurrentAttributes());
        return dto;
    }

    public List<ProductComparisonDto> toDtos(List<ProductUploadStagedEntity> staged)
    {
        return staged.stream().map(this::toDto).collect(Collectors.toList());
    }
}
