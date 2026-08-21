package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.CustomerAddressWriteMapper;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.enums.AddressTypeEn;

/**
 * Creates or updates a single typed address on a customer; skips silently if the incoming
 * address is null or wholly blank, since callers pass through unset form sections rather
 * than pre-filtering. Shared by the two independent address-write paths: storefront profile
 * self-service ({@code CustomerResource}) and staff wholesale create/approve/edit
 * ({@code WholesaleCustomerService}).
 */
@ApplicationScoped
public class CustomerAddressService
{
    @Inject
    CustomerAddressWriteMapper addressWriteMapper;

    public void upsertAddress(CustomerEntity ce, AddressTypeEn type, AddressDto source)
    {
        if (isBlank(source)) {
            return;
        }

        CustomerAddressEntity addr = ce.getAddresses().stream()
                .filter(a -> a.getAddressType() == type)
                .findFirst()
                .orElseGet(() -> {
                    CustomerAddressEntity a = new CustomerAddressEntity();
                    a.setCustomer(ce);
                    a.setAddressType(type);
                    ce.getAddresses().add(a);
                    return a;
                });

        addressWriteMapper.updateAddress(source, addr);
    }

    private boolean isBlank(AddressDto source)
    {
        return source == null
                || (source.getLine1() == null && source.getCity() == null
                        && source.getProvince() == null && source.getPostalCode() == null);
    }
}
