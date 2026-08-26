package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps staged product-import rows to the comparison DTO the admin review screen shows.
 * <p>
 * The DTO pairs each staged value ({@code proposed*}) against the value captured from the
 * catalogue at import time ({@code current*}), so the reviewer sees both sides of every change.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ProductComparisonMapper
{
    @Mapping(target = "stagedId", source = "id")
    @Mapping(target = "proposedName", source = "name")
    @Mapping(target = "proposedDescription", source = "description")
    @Mapping(target = "proposedShortDescription", source = "shortDescription")
    @Mapping(target = "proposedImages", source = "images")
    @Mapping(target = "proposedStock", source = "stock")
    @Mapping(target = "proposedAttributes", source = "attributes")
    @Mapping(target = "validCategory", source = "isValidCategory")
    @Mapping(target = "validBrand", source = "isValidBrand")
    @Mapping(target = "newProduct", source = "isNewProduct")
    @Mapping(target = "newVariant", source = "isNewVariant")
    ProductComparisonDto toDto(ProductImportStagedEntity staged);

    List<ProductComparisonDto> toDtos(List<ProductImportStagedEntity> staged);
}
