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
public class ProductComparisonMapper {

    public ProductComparisonDto toDto(ProductUploadStagedEntity staged) {
        ProductComparisonDto dto = new ProductComparisonDto();
        dto.stagedId = staged.id;
        dto.sku = staged.sku;
        dto.proposedName = staged.name;
        dto.proposedDescription = staged.description;
        dto.proposedShortDescription = staged.shortDescription;
        dto.categorySlug = staged.categorySlug;
        dto.brandSlug = staged.brandSlug;
        dto.proposedImages = staged.images;
        dto.proposedStock = staged.stock;
        dto.proposedAttributes = staged.attributes;
        dto.validationErrors = staged.validationErrors;
        dto.validationStatus = staged.validationStatus;
        dto.imageErrors = staged.imageErrors;
        dto.isValidCategory = staged.isValidCategory;
        dto.isValidBrand = staged.isValidBrand;
        dto.isNewProduct = staged.isNewProduct;
        dto.isNewVariant = staged.isNewVariant;
        dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);

        // Persisted current values captured at import time
        dto.currentName = staged.currentName;
        dto.currentDescription = staged.currentDescription;
        dto.currentShortDescription = staged.currentShortDescription;
        dto.currentStock = staged.currentStock;
        dto.currentImages = staged.currentImages;
        dto.currentAttributes = staged.currentAttributes;
        return dto;
    }

    public List<ProductComparisonDto> toDtos(List<ProductUploadStagedEntity> staged) {
        return staged.stream().map(this::toDto).collect(Collectors.toList());
    }
}
