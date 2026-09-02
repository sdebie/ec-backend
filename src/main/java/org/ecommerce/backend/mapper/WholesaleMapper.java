package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.enums.WholesaleCustomerStatusEn;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;


import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS,
        uses = CustomerAddressMapper.class)
public interface WholesaleMapper
{
    @Mapping(source = "accountEmail", target = "email")
    @Mapping(source = "customer.id", target = "customerId")
    @Mapping(target = "physicalAddress", source = ".", qualifiedByName = "wholesaleApplicationPhysicalAddress")
    @Mapping(target = "postalAddress", source = ".", qualifiedByName = "wholesaleApplicationPostalAddress")
    WholesaleApplicationDetailsDto toDetailsDto(WholesaleApplicationEntity application);

    @Mapping(source = "accountEmail", target = "email")
    @Mapping(target = "physicalAddress", source = ".", qualifiedByName = "wholesaleApplicationPhysicalAddress")
    @Mapping(target = "postalAddress", source = ".", qualifiedByName = "wholesaleApplicationPostalAddress")
    @Mapping(target = "applicantEmail", ignore = true)
    @Mapping(target = "tradingName", ignore = true)
    @Mapping(target = "companyPhone", ignore = true)
    @Mapping(target = "companyEmail", ignore = true)
    @Mapping(target = "financeContactName", ignore = true)
    @Mapping(target = "financeContactEmail", ignore = true)
    @Mapping(target = "financeContactPhone", ignore = true)
    @Mapping(target = "purchaseOrderRequired", ignore = true)
    WholesaleCustomerDto toDto(WholesaleApplicationEntity application);

    @Mapping(source = "user.email", target = "email")
    @Mapping(source = "wholesaleProfile.companyName", target = "companyName")
    @Mapping(source = "wholesaleProfile.vatNumber", target = "vatNumber")
    @Mapping(source = "wholesaleProfile.regNumber", target = "regNumber")
    @Mapping(target = "applicantEmail", ignore = true)
    @Mapping(target = "tradingName", ignore = true)
    @Mapping(target = "companyPhone", ignore = true)
    @Mapping(target = "companyEmail", ignore = true)
    @Mapping(target = "financeContactName", ignore = true)
    @Mapping(target = "financeContactEmail", ignore = true)
    @Mapping(target = "financeContactPhone", ignore = true)
    @Mapping(target = "purchaseOrderRequired", ignore = true)
    @Mapping(target = "notes", ignore = true) // staff notes live on the application, not the customer
    WholesaleCustomerDto toDto(CustomerEntity customer);

    @Mapping(source = "accountEmail", target = "email")
    WholesaleApplicationListItemDto toListItemDto(WholesaleApplicationEntity application);

    /**
     * WholesaleApplicationEntity stores both addresses as flat columns (no related
     * address entity, unlike CustomerEntity/CustomerAddressEntity), so bridging to the
     * DTOs' nested AddressDto needs an explicit whole-bean mapping rather than the
     * auto-matched same-named property MapStruct otherwise resolves on its own.
     */
    @Named("wholesaleApplicationPhysicalAddress")
    @Mapping(target = "line1", source = "physicalAddressLine1")
    @Mapping(target = "line2", source = "physicalAddressLine2")
    @Mapping(target = "suburb", source = "physicalSuburb")
    @Mapping(target = "city", source = "physicalCity")
    @Mapping(target = "province", source = "physicalProvince")
    @Mapping(target = "postalCode", source = "physicalPostalCode")
    AddressDto wholesaleApplicationPhysicalAddress(WholesaleApplicationEntity application);

    @Named("wholesaleApplicationPostalAddress")
    @Mapping(target = "line1", source = "postalAddressLine1")
    @Mapping(target = "line2", source = "postalAddressLine2")
    @Mapping(target = "suburb", source = "postalSuburb")
    @Mapping(target = "city", source = "postalCity")
    @Mapping(target = "province", source = "postalProvince")
    @Mapping(target = "postalCode", source = "postalPostalCode")
    AddressDto wholesaleApplicationPostalAddress(WholesaleApplicationEntity application);

    /**
     * The customer's own status enum is narrower than the wholesale one but shares its
     * names, so it converts by name rather than through a table that could drift.
     */
    default WholesaleCustomerStatusEn mapCustomerStatus(CustomerStatusEn status)
    {
        return status == null ? null : WholesaleCustomerStatusEn.valueOf(status.name());
    }


    default WholesaleCustomerStatusEn mapApplicationStatusToCustomerStatus(WholesaleApplicationStatusEn status)
    {
        if (status == null) {
            return null;
        }
        return WholesaleCustomerStatusEn.valueOf(status.name());
    }
}
