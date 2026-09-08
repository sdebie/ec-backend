package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;
import static org.mapstruct.ReportingPolicy.ERROR;

/**
 * The shopper's own view of themselves — GET portal and PATCH {@code /profile}
 * both read through this so {@code hasPassword} and address lookup cannot drift.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, uses = CustomerAddressMapper.class,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface CustomerProfileMapper
{
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "physicalAddress", source = "physicalAddress")
    @Mapping(target = "postalAddress", source = "postalAddress")
    @Mapping(target = "shopperType", source = "shopperType", defaultValue = "GUEST")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "hasPassword", expression = "java(hasLocalPassword(customer.getUser()))")
    @Mapping(target = "additionalInfo", ignore = true)
    CustomerProfileDto toProfileDto(CustomerEntity customer);

    default boolean hasLocalPassword(UserEntity user)
    {
        if (user == null) {
            return false;
        }
        String hash = user.getPasswordHash();
        return hash != null && !hash.isBlank();
    }
}
