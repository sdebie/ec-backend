package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.mapstruct.Mapper;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps shipping methods to their storefront shape.
 * <p>
 * {@code active} is copied rather than assumed: the storefront only ever queries active
 * rows, but publishing {@code active=false} for a method that is active misleads any client
 * that reads the flag. The wire key is {@code active} — Lombok derives {@code isActive()}
 * from the private {@code isActive} field and Jackson strips the prefix.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ShippingMethodMapper
{
    ShippingMethodDto toDto(ShippingMethodEntity entity);

    List<ShippingMethodDto> toDtos(List<ShippingMethodEntity> entities);
}
