package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.entity.ProductPriceUploadStagedEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps staged price-import rows to the comparison DTO the admin review screen shows.
 * <p>
 * Same proposed-versus-current pairing as {@link ProductComparisonMapper}, narrowed to the
 * two price tiers a price import can change.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ProductPriceComparisonMapper
{
    @Mapping(target = "stagedId", source = "id")
    @Mapping(target = "proposedRetailPrice", source = "retailPrice")
    @Mapping(target = "proposedWholesalePrice", source = "wholesalePrice")
    ProductPriceComparisonDto toDto(ProductPriceUploadStagedEntity staged);

    List<ProductPriceComparisonDto> toDtos(List<ProductPriceUploadStagedEntity> staged);
}
