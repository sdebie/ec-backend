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
 * address is null or wholly blank (every field unset), since callers pass through unset form
 * sections rather than pre-filtering. A partial address that isn't enough to create a valid new
 * row (missing line1/city/province/postalCode — the entity's non-nullable columns) is likewise
 * skipped rather than persisted incomplete; the same partial data applied to an already-saved
 * address instead updates just the fields present, leaving the rest untouched. Shared by the two
 * independent address-write paths: storefront profile self-service ({@code CustomerResource})
 * and staff wholesale create/approve/edit ({@code WholesaleCustomerService}).
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
                .orElse(null);

        if (addr == null) {
            if (!hasRequiredFieldsForCreate(source)) {
                return;
            }
            addr = new CustomerAddressEntity();
            addr.setCustomer(ce);
            addr.setAddressType(type);
            ce.getAddresses().add(addr);
        }

        addressWriteMapper.updateAddress(source, addr);
    }

    private boolean isBlank(AddressDto source)
    {
        return source == null
                || (source.getLine1() == null && source.getLine2() == null
                        && source.getSuburb() == null && source.getCity() == null
                        && source.getProvince() == null && source.getPostalCode() == null);
    }

    private boolean hasRequiredFieldsForCreate(AddressDto source)
    {
        return source.getLine1() != null && source.getCity() != null
                && source.getProvince() != null && source.getPostalCode() != null;
    }
}
