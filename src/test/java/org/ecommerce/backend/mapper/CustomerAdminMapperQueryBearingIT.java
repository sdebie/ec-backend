package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 3: Mapper output preservation (customer admin)

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Output-preservation test for {@code CustomerAdminMapper.toListItemDto(customer, application)}.
 * <p>
 * ⚠️ The wholesale-application lookup lives in {@code CustomerAdminService.wholesaleApplicationFor},
 * since mappers do not open queries — that query has no dedicated test of its own; these
 * assertions only pin the mapping.
 * <p>
 * Asserts that the query-bearing method output equals the pinned inline-method baseline —
 * does NOT re-implement the query in the test (per Requirement 4.4).
 * <p>
 * Tests representative scenarios:
 * - Customer with an approved wholesale application
 * - Customer without a wholesale application
 * - Customer with null user (email/registeredAt should be null)
 * - Customer with null status and shopperType
 * <p>
 */
@QuarkusTest
class CustomerAdminMapperQueryBearingIT
{
    @Inject
    CustomerAdminMapper customerAdminMapper;


    // ══════════════════════════════════════════════════════════════════════════
    // Reference: old inline toListItemDto logic (the baseline to compare against)
    // ══════════════════════════════════════════════════════════════════════════

    private AdminCustomerListItemDto referenceToListItemDto(CustomerEntity c, WholesaleApplicationEntity app)
    {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.setId(c.getId().toString());
        dto.setFirstName(c.getFirstName());
        dto.setLastName(c.getLastName());
        dto.setEmail(c.getUser() != null ? c.getUser().getEmail() : null);
        dto.setStatus(c.getStatus() != null ? c.getStatus().name() : null);
        dto.setShopperType(c.getShopperType() != null ? c.getShopperType().name() : null);
        dto.setRegisteredAt(c.getUser() != null && c.getUser().getCreatedAt() != null ? c.getUser().getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null);
        dto.setWholesaleApplicationStatus(app != null && app.getStatus() != null ? app.getStatus().name() : null);
        return dto;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void toListItemDto_queryBearing_customerWithWholesaleApp_matchesBaseline()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(WholesaleApplicationStatusEn.APPROVED);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, app);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
    }

    @Test
    void toListItemDto_queryBearing_customerWithoutWholesaleApp_matchesBaseline()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, null);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
    }

    @Test
    void toListItemDto_queryBearing_nullUser_emailAndRegisteredAtNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.setUser(null);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, null);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.getEmail());
        assertNull(mapperResult.getRegisteredAt());
    }

    @Test
    void toListItemDto_queryBearing_nullStatusAndShopperType_mapsToNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.setStatus(null);
        customer.setShopperType(null);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, null);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.getStatus());
        assertNull(mapperResult.getShopperType());
    }

    @Test
    void toListItemDto_queryBearing_pendingWholesaleApp_statusMapped()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(WholesaleApplicationStatusEn.PENDING);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, app);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
        assertEquals("PENDING", mapperResult.getWholesaleApplicationStatus());
    }

    @Test
    void toListItemDto_queryBearing_appWithNullStatus_wholesaleStatusIsNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(null);


        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer, app);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.getWholesaleApplicationStatus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Assertions
    // ══════════════════════════════════════════════════════════════════════════

    private void assertListItemDtoEquals(AdminCustomerListItemDto expected, AdminCustomerListItemDto actual)
    {
        assertEquals(expected.getId(), actual.getId(), "id mismatch");
        assertEquals(expected.getFirstName(), actual.getFirstName(), "firstName mismatch");
        assertEquals(expected.getLastName(), actual.getLastName(), "lastName mismatch");
        assertEquals(expected.getEmail(), actual.getEmail(), "email mismatch");
        assertEquals(expected.getStatus(), actual.getStatus(), "status mismatch");
        assertEquals(expected.getShopperType(), actual.getShopperType(), "shopperType mismatch");
        assertEquals(expected.getRegisteredAt(), actual.getRegisteredAt(), "registeredAt mismatch");
        assertEquals(expected.getWholesaleApplicationStatus(), actual.getWholesaleApplicationStatus(), "wholesaleApplicationStatus mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Builders
    // ══════════════════════════════════════════════════════════════════════════

    private CustomerEntity buildFullCustomer(UUID customerId)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setFirstName("Johan");
        customer.setLastName("van der Merwe");
        customer.setPhone("0821234567");
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.setShopperType(CustomerTypeEn.WHOLESALER);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("johan@example.com");
        user.setCreatedAt(OffsetDateTime.parse("2026-01-15T09:00:00Z"));
        customer.setUser(user);

        return customer;
    }

    private CustomerEntity buildRetailCustomer(UUID customerId)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setFirstName("Sarah");
        customer.setLastName("Smith");
        customer.setPhone(null);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.setShopperType(CustomerTypeEn.RETAILER);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("sarah@example.com");
        user.setCreatedAt(OffsetDateTime.parse("2026-03-20T14:30:00Z"));
        customer.setUser(user);

        return customer;
    }

    private WholesaleApplicationEntity buildApp(WholesaleApplicationStatusEn status)
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setId(UUID.randomUUID());
        app.setStatus(status);
        app.setFirstName("Test");
        app.setCompanyName("Test Co");
        return app;
    }

}
