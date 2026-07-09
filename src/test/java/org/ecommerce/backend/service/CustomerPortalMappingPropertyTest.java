package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 1: Profile Mapping Correctness
// Validates: Requirements 1.3, 1.4, 1.5, 1.6, 1.7

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.ecommerce.common.dto.StorefrontCustomerPortalDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that the CustomerPortalService mapping logic
 * correctly transforms any combination of CustomerEntity state into the expected
 * StorefrontCustomerPortalDto shape.
 *
 * Validates: Requirements 1.3, 1.4, 1.5, 1.6, 1.7
 */
public class CustomerPortalMappingPropertyTest {

    private MockedStatic<CustomerEntity> mockedCustomerEntity;
    private CustomerPortalService service;

    @BeforeTry
    void setup() {
        service = new CustomerPortalService();
        mockedCustomerEntity = Mockito.mockStatic(CustomerEntity.class);
    }

    @AfterTry
    void teardown() {
        if (mockedCustomerEntity != null) {
            mockedCustomerEntity.close();
        }
    }

    @Property(tries = 100)
    void profileMappingCorrectness(
            @ForAll("customerEntities") CustomerEntity customer
    ) {
        // Arrange: stub the static finder to return our generated customer
        String email = customer.user.email;
        mockedCustomerEntity.when(() -> CustomerEntity.findByEmail(email)).thenReturn(customer);

        // Act
        StorefrontCustomerPortalDto dto = service.getPortalProfile(email);

        // Assert physicalAddress mapping (Requirement 1.4)
        CustomerAddressEntity expectedPhysical = customer.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.PHYSICAL)
                .findFirst()
                .orElse(null);

        if (expectedPhysical == null) {
            assertNull(dto.physicalAddress, "physicalAddress should be null when no PHYSICAL address exists");
        } else {
            assertNotNull(dto.physicalAddress, "physicalAddress should not be null when PHYSICAL address exists");
            assertEquals(expectedPhysical.addressLine1, dto.physicalAddress.line1);
            assertEquals(expectedPhysical.addressLine2, dto.physicalAddress.line2);
            assertEquals(expectedPhysical.suburb, dto.physicalAddress.suburb);
            assertEquals(expectedPhysical.city, dto.physicalAddress.city);
            assertEquals(expectedPhysical.province, dto.physicalAddress.province);
            assertEquals(expectedPhysical.postalCode, dto.physicalAddress.postalCode);
        }

        // Assert postalAddress mapping (Requirement 1.5)
        CustomerAddressEntity expectedPostal = customer.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.POSTAL)
                .findFirst()
                .orElse(null);

        if (expectedPostal == null) {
            assertNull(dto.postalAddress, "postalAddress should be null when no POSTAL address exists");
        } else {
            assertNotNull(dto.postalAddress, "postalAddress should not be null when POSTAL address exists");
            assertEquals(expectedPostal.addressLine1, dto.postalAddress.line1);
            assertEquals(expectedPostal.addressLine2, dto.postalAddress.line2);
            assertEquals(expectedPostal.suburb, dto.postalAddress.suburb);
            assertEquals(expectedPostal.city, dto.postalAddress.city);
            assertEquals(expectedPostal.province, dto.postalAddress.province);
            assertEquals(expectedPostal.postalCode, dto.postalAddress.postalCode);
        }

        // Assert hasPassword (Requirement 1.6)
        boolean expectedHasPassword = customer.user.passwordHash != null
                && !customer.user.passwordHash.isEmpty();
        assertEquals(expectedHasPassword, dto.hasPassword,
                "hasPassword should be true iff passwordHash is non-null and non-empty");

        // Assert shopperType (Requirement 1.7)
        String expectedShopperType = customer.shopperType != null
                ? customer.shopperType.name()
                : "GUEST";
        assertEquals(expectedShopperType, dto.shopperType,
                "shopperType should equal enum name or 'GUEST' when null");
    }

    @Provide
    Arbitrary<CustomerEntity> customerEntities() {
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
                    customer.id = UUID.randomUUID();
                    customer.firstName = firstName;
                    customer.lastName = lastName;
                    customer.phone = phone;
                    customer.shopperType = shopperType;

                    UserEntity user = new UserEntity();
                    user.id = UUID.randomUUID();
                    user.email = email;
                    user.passwordHash = passwordHash;
                    customer.user = user;

                    customer.addresses = addresses;
                    return customer;
                });
    }

    private Arbitrary<List<CustomerAddressEntity>> addressListArbitrary() {
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
                    addr.addressType = availableTypes.remove(typeIndex);
                    result.add(addr);
                }
                return result;
            });
        });
    }

    private Arbitrary<CustomerAddressEntity> addressArbitrary() {
        Arbitrary<String> lines = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30);
        Arbitrary<String> nullableLines = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30)
        );

        return Combinators.combine(lines, nullableLines, nullableLines, lines, lines, lines)
                .as((line1, line2, suburb, city, province, postalCode) -> {
                    CustomerAddressEntity addr = new CustomerAddressEntity();
                    addr.id = UUID.randomUUID();
                    addr.addressLine1 = line1;
                    addr.addressLine2 = line2;
                    addr.suburb = suburb;
                    addr.city = city;
                    addr.province = province;
                    addr.postalCode = postalCode;
                    return addr;
                });
    }
}
