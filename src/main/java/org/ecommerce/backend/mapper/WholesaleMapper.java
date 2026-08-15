package org.ecommerce.backend.mapper;

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


import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ERROR,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface WholesaleMapper
{
    @Mapping(source = "accountEmail", target = "email")
    @Mapping(source = "customer.id", target = "customerId")
    WholesaleApplicationDetailsDto toDetailsDto(WholesaleApplicationEntity application);

    @Mapping(source = "accountEmail", target = "email")
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
    @Mapping(target = "physicalAddressLine1", source = "physicalAddress.addressLine1")
    @Mapping(target = "physicalAddressLine2", source = "physicalAddress.addressLine2")
    @Mapping(target = "physicalSuburb", source = "physicalAddress.suburb")
    @Mapping(target = "physicalCity", source = "physicalAddress.city")
    @Mapping(target = "physicalProvince", source = "physicalAddress.province")
    @Mapping(target = "physicalPostalCode", source = "physicalAddress.postalCode")
    @Mapping(target = "postalAddressLine1", source = "postalAddress.addressLine1")
    @Mapping(target = "postalAddressLine2", source = "postalAddress.addressLine2")
    @Mapping(target = "postalSuburb", source = "postalAddress.suburb")
    @Mapping(target = "postalCity", source = "postalAddress.city")
    @Mapping(target = "postalProvince", source = "postalAddress.province")
    @Mapping(target = "postalPostalCode", source = "postalAddress.postalCode")
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
