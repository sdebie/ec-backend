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
public class ProductPriceComparisonMapper {

    public ProductPriceComparisonDto toDto(ProductPriceUploadStagedEntity staged) {
        ProductPriceComparisonDto dto = new ProductPriceComparisonDto();
        dto.stagedId = staged.id;
        dto.sku = staged.sku;
        dto.validationErrors = staged.validationErrors;
        dto.validationStatus = staged.validationStatus;
        dto.proposedRetailPrice = staged.retailPrice;
        dto.proposedWholesalePrice = staged.wholesalePrice;
        dto.currentRetailPrice = staged.currentRetailPrice;
        dto.currentWholesalePrice = staged.currentWholesalePrice;
        dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);
        return dto;
    }

    public List<ProductPriceComparisonDto> toDtos(List<ProductPriceUploadStagedEntity> staged) {
        return staged.stream().map(this::toDto).collect(Collectors.toList());
    }
}
