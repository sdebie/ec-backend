package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.enums.WholesaleCustomerStatusEn;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi",
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

    default WholesaleCustomerStatusEn mapApplicationStatusToCustomerStatus(WholesaleApplicationStatusEn status)
    {
        if (status == null) {
            return null;
        }
        return WholesaleCustomerStatusEn.valueOf(status.name());
    }
}
