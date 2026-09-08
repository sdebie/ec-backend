package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerProfileMapperTest
{
    private CustomerProfileMapper mapper;

    @BeforeEach
    void setUp()
    {
        mapper = new CustomerProfileMapperImpl(new CustomerAddressMapperImpl());
    }

    @Test
    void blankPasswordHashIsNotALocalPassword()
    {
        CustomerEntity customer = customerWithHash("   ");

        assertFalse(mapper.toProfileDto(customer).isHasPassword());
    }

    @Test
    void emptyPasswordHashIsNotALocalPassword()
    {
        CustomerEntity customer = customerWithHash("");

        assertFalse(mapper.toProfileDto(customer).isHasPassword());
    }

    @Test
    void presentPasswordHashIsALocalPassword()
    {
        CustomerEntity customer = customerWithHash("argon2-hash");

        assertTrue(mapper.toProfileDto(customer).isHasPassword());
    }

    @Test
    void addressesComeFromTheEntityTypedGetters()
    {
        CustomerEntity customer = customerWithHash("hash");
        customer.getAddresses().add(address(AddressTypeEn.POSTAL, "Postal Rd"));
        customer.getAddresses().add(address(AddressTypeEn.PHYSICAL, "Physical Rd"));

        CustomerProfileDto dto = mapper.toProfileDto(customer);

        assertEquals("Physical Rd", dto.getPhysicalAddress().getLine1());
        assertEquals("Postal Rd", dto.getPostalAddress().getLine1());
    }

    @Test
    void nullAddressListDoesNotFail()
    {
        CustomerEntity customer = customerWithHash("hash");
        customer.setAddresses(null);

        CustomerProfileDto dto = mapper.toProfileDto(customer);

        assertNull(dto.getPhysicalAddress());
        assertNull(dto.getPostalAddress());
    }

    @Test
    void missingShopperTypeDefaultsToGuest()
    {
        CustomerEntity customer = customerWithHash("hash");
        customer.setShopperType(null);

        assertEquals("GUEST", mapper.toProfileDto(customer).getShopperType());
    }

    @Test
    void mapsIdentityAndLeavesAdditionalInfoUnset()
    {
        CustomerEntity customer = customerWithHash("hash");
        customer.getUser().setEmail("shopper@example.com");
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");
        customer.setPhone("0123456789");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);

        CustomerProfileDto dto = mapper.toProfileDto(customer);

        assertEquals("shopper@example.com", dto.getEmail());
        assertEquals("Ada", dto.getFirstName());
        assertEquals("Lovelace", dto.getLastName());
        assertEquals("0123456789", dto.getPhone());
        assertEquals("RETAILER", dto.getShopperType());
        assertEquals("ACTIVE", dto.getStatus());
        assertNull(dto.getAdditionalInfo());
    }

    @Test
    void missingUserHasNoEmailOrLocalPassword()
    {
        CustomerEntity customer = customerWithHash("hash");
        customer.setUser(null);

        CustomerProfileDto dto = mapper.toProfileDto(customer);

        assertNull(dto.getEmail());
        assertFalse(dto.isHasPassword());
    }

    private static CustomerEntity customerWithHash(String passwordHash)
    {
        UserEntity user = new UserEntity();
        user.setEmail("shopper@example.com");
        user.setPasswordHash(passwordHash);

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        return customer;
    }

    private static CustomerAddressEntity address(AddressTypeEn type, String line1)
    {
        CustomerAddressEntity address = new CustomerAddressEntity();
        address.setAddressType(type);
        address.setAddressLine1(line1);
        address.setCity("Cape Town");
        address.setProvince("Western Cape");
        address.setPostalCode("8001");
        return address;
    }
}
