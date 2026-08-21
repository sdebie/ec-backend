package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValuePropertyMappingStrategy.IGNORE;
import static org.mapstruct.ReportingPolicy.ERROR;

/**
 * Applies a caller-supplied {@link AddressDto} onto an existing {@link CustomerAddressEntity},
 * leaving a target field unchanged when the corresponding source field is null — the
 * partial-update semantics both upsert call sites (profile self-service, wholesale
 * create/approve/edit) rely on.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR,
        nullValueCheckStrategy = ALWAYS, nullValuePropertyMappingStrategy = IGNORE)
public interface CustomerAddressWriteMapper
{
    @Mapping(target = "addressLine1", source = "line1")
    @Mapping(target = "addressLine2", source = "line2")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "addressType", ignore = true)
    @Mapping(target = "default", ignore = true) // CustomerAddressEntity.isDefault; Lombok's boolean-getter convention names the bean property "default", not "isDefault"
    void updateAddress(AddressDto source, @MappingTarget CustomerAddressEntity target);
}
