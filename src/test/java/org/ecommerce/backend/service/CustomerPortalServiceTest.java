package org.ecommerce.backend.service;

import org.ecommerce.backend.mapper.CustomerAddressMapperImpl;
import jakarta.ws.rs.WebApplicationException;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CustomerPortalService}.
 * Tests profile mapping with various address combinations, hasPassword logic,
 * shopperType mapping, and changePassword scenarios.
 * <p>
 * Requirements: 1.3, 1.4, 1.5, 1.6, 1.7, 5.4, 5.5, 5.6, 5.7
 */
class CustomerPortalServiceTest
{
    private CustomerPortalService service;
    private MockedStatic<CustomerEntity> customerEntityMock;

    @BeforeEach
    void setUp()
    {
        service = new CustomerPortalService();

        service.customerAddressMapper = new CustomerAddressMapperImpl();
        customerEntityMock = Mockito.mockStatic(CustomerEntity.class);
    }

    @AfterEach
    void tearDown()
    {
        customerEntityMock.close();
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private CustomerEntity buildCustomer(String email, String firstName, String lastName,
                                         String phone, CustomerTypeEn shopperType,
                                         String passwordHash,
                                         List<CustomerAddressEntity> addresses)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());
        customer.setFirstName(firstName);
        customer.setLastName(lastName);
        customer.setPhone(phone);
        customer.setShopperType(shopperType);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        customer.setUser(user);

        customer.setAddresses(addresses != null ? addresses : new ArrayList<>());
        return customer;
    }

    private CustomerAddressEntity buildAddress(AddressTypeEn type, String line1, String line2,
                                               String suburb, String city, String province,
                                               String postalCode)
    {
        CustomerAddressEntity address = new CustomerAddressEntity();
        address.setId(UUID.randomUUID());
        address.setAddressType(type);
        address.setAddressLine1(line1);
        address.setAddressLine2(line2);
        address.setSuburb(suburb);
        address.setCity(city);
        address.setProvince(province);
        address.setPostalCode(postalCode);
        return address;
    }

    // ── getPortalProfile: Address mapping tests ─────────────────────────────

    @Test
    void getPortalProfile_noAddresses_bothNull()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", "0821234567", CustomerTypeEn.RETAILER, "somehash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertNull(result.getPhysicalAddress());
        assertNull(result.getPostalAddress());
    }

    @Test
    void getPortalProfile_onlyPhysicalAddress_physicalPopulated_postalNull()
    {
        CustomerAddressEntity physical = buildAddress(AddressTypeEn.PHYSICAL, "123 Main St", "Apt 4", "Sandton", "Johannesburg", "Gauteng", "2196");

        CustomerEntity customer = buildCustomer("test@example.com", "Jane", "Smith", null, CustomerTypeEn.WHOLESALER, "hash123", List.of(physical));

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertNotNull(result.getPhysicalAddress());
        assertEquals("123 Main St", result.getPhysicalAddress().getLine1());
        assertEquals("Apt 4", result.getPhysicalAddress().getLine2());
        assertEquals("Sandton", result.getPhysicalAddress().getSuburb());
        assertEquals("Johannesburg", result.getPhysicalAddress().getCity());
        assertEquals("Gauteng", result.getPhysicalAddress().getProvince());
        assertEquals("2196", result.getPhysicalAddress().getPostalCode());
        assertNull(result.getPostalAddress());
    }

    @Test
    void getPortalProfile_onlyPostalAddress_physicalNull_postalPopulated()
    {
        CustomerAddressEntity postal = buildAddress(AddressTypeEn.POSTAL, "PO Box 500", null, null, "Cape Town", "Western Cape", "8001");

        CustomerEntity customer = buildCustomer("test@example.com", "Bob", "Brown", "0839876543", CustomerTypeEn.GUEST, "passwordhash", List.of(postal));

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertNull(result.getPhysicalAddress());
        assertNotNull(result.getPostalAddress());
        assertEquals("PO Box 500", result.getPostalAddress().getLine1());
        assertNull(result.getPostalAddress().getLine2());
        assertNull(result.getPostalAddress().getSuburb());
        assertEquals("Cape Town", result.getPostalAddress().getCity());
        assertEquals("Western Cape", result.getPostalAddress().getProvince());
        assertEquals("8001", result.getPostalAddress().getPostalCode());
    }

    @Test
    void getPortalProfile_bothAddresses_bothPopulatedWithCorrectFieldMapping()
    {
        CustomerAddressEntity physical = buildAddress(AddressTypeEn.PHYSICAL, "10 Oak Ave", "Unit 2B", "Rosebank", "Johannesburg", "Gauteng", "2196");
        CustomerAddressEntity postal = buildAddress(AddressTypeEn.POSTAL, "PO Box 100", null, null, "Pretoria", "Gauteng", "0001");

        CustomerEntity customer = buildCustomer("test@example.com", "Alice", "Green", "0115551234", CustomerTypeEn.RETAILER, "hash", List.of(physical, postal));

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        // Physical address field mapping: addressLine1→line1, addressLine2→line2
        assertNotNull(result.getPhysicalAddress());
        assertEquals("10 Oak Ave", result.getPhysicalAddress().getLine1());
        assertEquals("Unit 2B", result.getPhysicalAddress().getLine2());
        assertEquals("Rosebank", result.getPhysicalAddress().getSuburb());
        assertEquals("Johannesburg", result.getPhysicalAddress().getCity());
        assertEquals("Gauteng", result.getPhysicalAddress().getProvince());
        assertEquals("2196", result.getPhysicalAddress().getPostalCode());

        // Postal address field mapping
        assertNotNull(result.getPostalAddress());
        assertEquals("PO Box 100", result.getPostalAddress().getLine1());
        assertNull(result.getPostalAddress().getLine2());
        assertNull(result.getPostalAddress().getSuburb());
        assertEquals("Pretoria", result.getPostalAddress().getCity());
        assertEquals("Gauteng", result.getPostalAddress().getProvince());
        assertEquals("0001", result.getPostalAddress().getPostalCode());
    }

    // ── getPortalProfile: hasPassword tests ─────────────────────────────────

    @Test
    void getPortalProfile_passwordHashNull_hasPasswordFalse()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.RETAILER, null, new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertFalse(result.isHasPassword());
    }

    @Test
    void getPortalProfile_passwordHashEmpty_hasPasswordFalse()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.RETAILER, "", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertFalse(result.isHasPassword());
    }

    @Test
    void getPortalProfile_passwordHashNonEmpty_hasPasswordTrue()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.RETAILER, "abc123hash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertTrue(result.isHasPassword());
    }

    // ── getPortalProfile: shopperType mapping tests ─────────────────────────

    @Test
    void getPortalProfile_shopperTypeRetailer_mapsToRETAILER()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.RETAILER, "hash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertEquals("RETAILER", result.getShopperType());
    }

    @Test
    void getPortalProfile_shopperTypeWholesaler_mapsToWHOLESALER()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.WHOLESALER, "hash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertEquals("WHOLESALER", result.getShopperType());
    }

    @Test
    void getPortalProfile_shopperTypeGuest_mapsToGUEST()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, CustomerTypeEn.GUEST, "hash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertEquals("GUEST", result.getShopperType());
    }

    @Test
    void getPortalProfile_shopperTypeNull_defaultsToGUEST()
    {
        CustomerEntity customer = buildCustomer("test@example.com", "John", "Doe", null, null, "hash", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("test@example.com")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("test@example.com");

        assertEquals("GUEST", result.getShopperType());
    }

    // ── getPortalProfile: Customer not found ────────────────────────────────

    @Test
    void getPortalProfile_customerNotFound_throws404()
    {
        customerEntityMock.when(() -> CustomerEntity.findByEmail("unknown@example.com")).thenReturn(null);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> service.getPortalProfile("unknown@example.com"));

        assertEquals(404, ex.getResponse().getStatus());
    }

    // ── changePassword: Success scenario ────────────────────────────────────

    @Test
    void changePassword_success_correctCurrentPasswordAndValidNew()
    {
        String email = "test@example.com";
        String currentPassword = "oldPassword1";
        String newPassword = "newSecure8";
        String currentHash = org.ecommerce.backend.utils.PasswordHashUtil.hash(currentPassword);

        // Build customer with a spy on UserEntity so persist() is a no-op
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());
        customer.setFirstName("Test");
        customer.setLastName("User");
        customer.setAddresses(new ArrayList<>());

        UserEntity user = Mockito.spy(new UserEntity());
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(currentHash);
        Mockito.doNothing().when(user).persist();
        customer.setUser(user);

        customerEntityMock.when(() -> CustomerEntity.findByEmail(email)).thenReturn(customer);

        // Should not throw — validates current password matches and new password is long enough
        assertDoesNotThrow(() -> service.changePassword(email, currentPassword, newPassword));

        // Verify the password was actually updated to the new hash
        assertEquals(org.ecommerce.backend.utils.PasswordHashUtil.hash(newPassword), user.getPasswordHash());
    }

    // ── changePassword: Failure scenarios ───────────────────────────────────

    @Test
    void changePassword_incorrectCurrentPassword_throws401()
    {
        String email = "test@example.com";
        String currentPassword = "wrongPassword";
        String newPassword = "newSecure8";

        CustomerEntity customer = buildCustomer(email, "Test", "User",
                null, CustomerTypeEn.RETAILER,
                org.ecommerce.backend.utils.PasswordHashUtil.hash("correctPassword"),
                new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail(email)).thenReturn(customer);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> service.changePassword(email, currentPassword, newPassword));

        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void changePassword_newPasswordTooShort_throws400()
    {
        String email = "test@example.com";
        String currentPassword = "correctPass";
        String newPassword = "short";  // less than 8 characters

        CustomerEntity customer = buildCustomer(email, "Test", "User",
                null, CustomerTypeEn.RETAILER,
                org.ecommerce.backend.utils.PasswordHashUtil.hash(currentPassword),
                new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail(email)).thenReturn(customer);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> service.changePassword(email, currentPassword, newPassword));

        assertEquals(400, ex.getResponse().getStatus());
    }

    @Test
    void changePassword_noLocalPasswordSet_throws400()
    {
        String email = "test@example.com";
        String currentPassword = "anyPassword";
        String newPassword = "newSecure8";

        // passwordHash is null — Google-only account
        CustomerEntity customer = buildCustomer(email, "Test", "User", null, CustomerTypeEn.RETAILER, null, new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail(email)).thenReturn(customer);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> service.changePassword(email, currentPassword, newPassword));

        assertEquals(400, ex.getResponse().getStatus());
    }

    @Test
    void changePassword_customerNotFound_throws404()
    {
        customerEntityMock.when(() -> CustomerEntity.findByEmail("unknown@example.com"))
                .thenReturn(null);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> service.changePassword("unknown@example.com", "pass", "newPass123"));

        assertEquals(404, ex.getResponse().getStatus());
    }

    // ── getPortalProfile: General field mapping ─────────────────────────────

    @Test
    void getPortalProfile_mapsAllProfileFields()
    {
        CustomerEntity customer = buildCustomer("alice@shop.co.za", "Alice", "Wonder", "0821112233", CustomerTypeEn.WHOLESALER, "hashed", new ArrayList<>());

        customerEntityMock.when(() -> CustomerEntity.findByEmail("alice@shop.co.za")).thenReturn(customer);

        StorefrontCustomerPortalDto result = service.getPortalProfile("alice@shop.co.za");

        assertEquals("alice@shop.co.za", result.getEmail());
        assertEquals("Alice", result.getFirstName());
        assertEquals("Wonder", result.getLastName());
        assertEquals("0821112233", result.getPhone());
        assertEquals("WHOLESALER", result.getShopperType());
        assertTrue(result.isHasPassword());
    }
}
