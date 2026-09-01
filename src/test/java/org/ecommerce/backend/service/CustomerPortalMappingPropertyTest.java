package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 1: Profile Mapping Correctness

import org.ecommerce.backend.mapper.CustomerAddressMapperImpl;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.repository.CustomerRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test verifying that the CustomerPortalService mapping logic
 * correctly transforms any combination of CustomerEntity state into the expected
 * StorefrontCustomerPortalDto shape.
 * <p>
 */
public class CustomerPortalMappingPropertyTest
{
    private CustomerRepository customerRepository;
    private CustomerPortalService service;

    @BeforeTry
    void setup()
    {
        service = new CustomerPortalService();

        service.customerAddressMapper = new CustomerAddressMapperImpl();
        customerRepository = mock(CustomerRepository.class);
        service.customerRepository = customerRepository;
    }

    @Property(tries = 100)
    void profileMappingCorrectness(
            @ForAll("customerEntities") CustomerEntity customer
    )
    {
        // Arrange: stub the repository finder to return our generated customer
        String email = customer.getUser().getEmail();
        when(customerRepository.findByEmail(email)).thenReturn(customer);

        // Act
        StorefrontCustomerPortalDto dto = service.getPortalProfile(email);

        // Assert physicalAddress mapping (Requirement 1.4)
        CustomerAddressEntity expectedPhysical = customer.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressTypeEn.PHYSICAL)
                .findFirst()
                .orElse(null);

        if (expectedPhysical == null) {
            assertNull(dto.getPhysicalAddress(), "physicalAddress should be null when no PHYSICAL address exists");
        } else {
            assertNotNull(dto.getPhysicalAddress(), "physicalAddress should not be null when PHYSICAL address exists");
            assertEquals(expectedPhysical.getAddressLine1(), dto.getPhysicalAddress().getLine1());
            assertEquals(expectedPhysical.getAddressLine2(), dto.getPhysicalAddress().getLine2());
            assertEquals(expectedPhysical.getSuburb(), dto.getPhysicalAddress().getSuburb());
            assertEquals(expectedPhysical.getCity(), dto.getPhysicalAddress().getCity());
            assertEquals(expectedPhysical.getProvince(), dto.getPhysicalAddress().getProvince());
            assertEquals(expectedPhysical.getPostalCode(), dto.getPhysicalAddress().getPostalCode());
        }

        // Assert postalAddress mapping (Requirement 1.5)
        CustomerAddressEntity expectedPostal = customer.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressTypeEn.POSTAL)
                .findFirst()
                .orElse(null);

        if (expectedPostal == null) {
            assertNull(dto.getPostalAddress(), "postalAddress should be null when no POSTAL address exists");
        } else {
            assertNotNull(dto.getPostalAddress(), "postalAddress should not be null when POSTAL address exists");
            assertEquals(expectedPostal.getAddressLine1(), dto.getPostalAddress().getLine1());
            assertEquals(expectedPostal.getAddressLine2(), dto.getPostalAddress().getLine2());
            assertEquals(expectedPostal.getSuburb(), dto.getPostalAddress().getSuburb());
            assertEquals(expectedPostal.getCity(), dto.getPostalAddress().getCity());
            assertEquals(expectedPostal.getProvince(), dto.getPostalAddress().getProvince());
            assertEquals(expectedPostal.getPostalCode(), dto.getPostalAddress().getPostalCode());
        }

        // Assert hasPassword (Requirement 1.6)
        boolean expectedHasPassword = customer.getUser().getPasswordHash() != null && !customer.getUser().getPasswordHash().isEmpty();
        assertEquals(expectedHasPassword, dto.isHasPassword(), "hasPassword should be true iff passwordHash is non-null and non-empty");

        // Assert shopperType (Requirement 1.7)
        String expectedShopperType = customer.getShopperType() != null ? customer.getShopperType().name() : "GUEST";
        assertEquals(expectedShopperType, dto.getShopperType(), "shopperType should equal enum name or 'GUEST' when null");
    }

    @Provide
    Arbitrary<CustomerEntity> customerEntities()
    {
        Arbitrary<String> emails = Arbitraries.strings()
                .alpha().ofMinLength(3).ofMaxLength(10)
                .map(s -> s.toLowerCase() + "@test.com");

        Arbitrary<String> passwordHashStates = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(64)
        );

        Arbitrary<CustomerTypeEn> shopperTypes = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(CustomerTypeEn.values())
        );

        Arbitrary<List<CustomerAddressEntity>> addressLists = addressListArbitrary();

        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(15);
        Arbitrary<String> phones = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().numeric().ofLength(10)
        );

        return Combinators.combine(emails, passwordHashStates, shopperTypes, addressLists, names, names, phones)
                .as((email, passwordHash, shopperType, addresses, firstName, lastName, phone) -> {
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

                    customer.setAddresses(addresses);
                    return customer;
                });
    }

    private Arbitrary<List<CustomerAddressEntity>> addressListArbitrary()
    {
        Arbitrary<CustomerAddressEntity> addressArbitrary = addressArbitrary();

        // Generate 0-4 addresses with various types, ensuring at most one of each type
        return Arbitraries.integers().between(0, 4).flatMap(count -> {
            if (count == 0) {
                return Arbitraries.just(new ArrayList<>());
            }
            return addressArbitrary.list().ofSize(count).map(list -> {
                List<AddressTypeEn> availableTypes = new ArrayList<>(List.of(AddressTypeEn.values()));
                List<CustomerAddressEntity> result = new ArrayList<>();
                for (int i = 0; i < list.size() && !availableTypes.isEmpty(); i++) {
                    CustomerAddressEntity addr = list.get(i);
                    int typeIndex = i % availableTypes.size();
                    addr.setAddressType(availableTypes.remove(typeIndex));
                    result.add(addr);
                }
                return result;
            });
        });
    }

    private Arbitrary<CustomerAddressEntity> addressArbitrary()
    {
        Arbitrary<String> lines = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30);
        Arbitrary<String> nullableLines = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30)
        );

        return Combinators.combine(lines, nullableLines, nullableLines, lines, lines, lines)
                .as((line1, line2, suburb, city, province, postalCode) -> {
                    CustomerAddressEntity addr = new CustomerAddressEntity();
                    addr.setId(UUID.randomUUID());
                    addr.setAddressLine1(line1);
                    addr.setAddressLine2(line2);
                    addr.setSuburb(suburb);
                    addr.setCity(city);
                    addr.setProvince(province);
                    addr.setPostalCode(postalCode);
                    return addr;
                });
    }
}
