package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps a stored address row to the portal's address shape.
 * <p>
 * Selecting <em>which</em> of a customer's addresses to map is the caller's decision —
 * this only converts the row it is handed.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface CustomerAddressMapper
{
    @Mapping(target = "line1", source = "addressLine1")
    @Mapping(target = "line2", source = "addressLine2")
    AddressDto toAddressDto(CustomerAddressEntity entity);
}
