package org.ecommerce.backend.service;

import org.ecommerce.backend.mapper.CustomerAddressWriteMapperImpl;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CustomerAddressService}'s blank-address detection and its
 * create-vs-update split (a new address needs the DB's required fields; an update to an
 * existing one accepts any subset).
 */
class CustomerAddressServiceTest
{
    private CustomerAddressService service;

    @BeforeEach
    void setUp()
    {
        service = new CustomerAddressService();
        service.addressWriteMapper = new CustomerAddressWriteMapperImpl();
    }

    @Test
    void upsertAddress_nullSource_isSkipped()
    {
        CustomerEntity ce = new CustomerEntity();

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, null);

        assertTrue(ce.getAddresses().isEmpty());
    }

    @Test
    void upsertAddress_allSixFieldsNull_isSkipped()
    {
        CustomerEntity ce = new CustomerEntity();

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, new AddressDto());

        assertTrue(ce.getAddresses().isEmpty());
    }

    @Test
    void upsertAddress_line2OnlyNewAddress_isSkippedRatherThanPersistedIncomplete()
    {
        CustomerEntity ce = new CustomerEntity();
        AddressDto source = new AddressDto();
        source.setLine2("Unit 4B");

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, source);

        assertTrue(ce.getAddresses().isEmpty());
    }

    @Test
    void upsertAddress_suburbOnlyNewAddress_isSkippedRatherThanPersistedIncomplete()
    {
        CustomerEntity ce = new CustomerEntity();
        AddressDto source = new AddressDto();
        source.setSuburb("Sandton");

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, source);

        assertTrue(ce.getAddresses().isEmpty());
    }

    @Test
    void upsertAddress_allRequiredFieldsPresent_createsNewAddress()
    {
        CustomerEntity ce = new CustomerEntity();
        AddressDto source = new AddressDto();
        source.setLine1("123 Main St");
        source.setCity("Johannesburg");
        source.setProvince("Gauteng");
        source.setPostalCode("2000");

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, source);

        assertEquals(1, ce.getAddresses().size());
        CustomerAddressEntity saved = ce.getAddresses().get(0);
        assertEquals("123 Main St", saved.getAddressLine1());
        assertEquals("2000", saved.getPostalCode());
    }

    @Test
    void upsertAddress_line2Only_existingAddress_updatesItWithoutClearingOtherFields()
    {
        CustomerEntity ce = new CustomerEntity();
        ce.getAddresses().add(existingPhysicalAddress(ce));

        AddressDto source = new AddressDto();
        source.setLine2("Unit 4B");

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, source);

        assertEquals(1, ce.getAddresses().size());
        CustomerAddressEntity saved = ce.getAddresses().get(0);
        assertEquals("Unit 4B", saved.getAddressLine2());
        assertEquals("123 Main St", saved.getAddressLine1());
        assertEquals("Johannesburg", saved.getCity());
    }

    @Test
    void upsertAddress_suburbOnly_existingAddress_updatesItWithoutClearingOtherFields()
    {
        CustomerEntity ce = new CustomerEntity();
        ce.getAddresses().add(existingPhysicalAddress(ce));

        AddressDto source = new AddressDto();
        source.setSuburb("Sandton");

        service.upsertAddress(ce, AddressTypeEn.PHYSICAL, source);

        assertEquals(1, ce.getAddresses().size());
        CustomerAddressEntity saved = ce.getAddresses().get(0);
        assertEquals("Sandton", saved.getSuburb());
        assertEquals("123 Main St", saved.getAddressLine1());
        assertEquals("Johannesburg", saved.getCity());
    }

    private CustomerAddressEntity existingPhysicalAddress(CustomerEntity ce)
    {
        CustomerAddressEntity existing = new CustomerAddressEntity();
        existing.setCustomer(ce);
        existing.setAddressType(AddressTypeEn.PHYSICAL);
        existing.setAddressLine1("123 Main St");
        existing.setCity("Johannesburg");
        existing.setProvince("Gauteng");
        existing.setPostalCode("2000");
        return existing;
    }
}
