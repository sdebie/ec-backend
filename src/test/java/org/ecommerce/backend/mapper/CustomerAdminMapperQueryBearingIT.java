package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 3: Mapper output preservation (customer admin)
// Validates: Requirements 1.3, 2.4, 4.2, 4.4

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-backed (PanacheMock) test for the query-bearing {@code CustomerAdminMapper.toListItemDto(c)}
 * which internally queries {@code WholesaleApplicationEntity.find("customer.id = ?1", c.id)}.
 *
 * Asserts that the query-bearing method output equals the pinned inline-method baseline —
 * does NOT re-implement the query in the test (per Requirement 4.4).
 *
 * Tests representative scenarios:
 * - Customer with an approved wholesale application
 * - Customer without a wholesale application
 * - Customer with null user (email/registeredAt should be null)
 * - Customer with null status and shopperType
 *
 * Validates: Requirements 1.3, 2.4, 4.2, 4.4
 */
@QuarkusTest
class CustomerAdminMapperQueryBearingIT {

    @Inject
    CustomerAdminMapper customerAdminMapper;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(WholesaleApplicationEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reference: old inline toListItemDto logic (the baseline to compare against)
    // ══════════════════════════════════════════════════════════════════════════

    private AdminCustomerListItemDto referenceToListItemDto(CustomerEntity c, WholesaleApplicationEntity app) {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.id = c.id.toString();
        dto.firstName = c.firstName;
        dto.lastName = c.lastName;
        dto.email = c.user != null ? c.user.email : null;
        dto.status = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;
        dto.wholesaleApplicationStatus = app != null && app.status != null
                ? app.status.name()
                : null;
        return dto;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_customerWithWholesaleApp_matchesBaseline() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(WholesaleApplicationStatusEn.APPROVED);

        stubWholesaleAppQuery(customerId, app);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_customerWithoutWholesaleApp_matchesBaseline() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        stubWholesaleAppQuery(customerId, null);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_nullUser_emailAndRegisteredAtNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.user = null;

        stubWholesaleAppQuery(customerId, null);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.email);
        assertNull(mapperResult.registeredAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_nullStatusAndShopperType_mapsToNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.status = null;
        customer.shopperType = null;

        stubWholesaleAppQuery(customerId, null);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, null);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.status);
        assertNull(mapperResult.shopperType);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_pendingWholesaleApp_statusMapped() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(WholesaleApplicationStatusEn.PENDING);

        stubWholesaleAppQuery(customerId, app);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
        assertEquals("PENDING", mapperResult.wholesaleApplicationStatus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_queryBearing_appWithNullStatus_wholesaleStatusIsNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildApp(null);

        stubWholesaleAppQuery(customerId, app);

        AdminCustomerListItemDto mapperResult = customerAdminMapper.toListItemDto(customer);
        AdminCustomerListItemDto baseline = referenceToListItemDto(customer, app);

        assertListItemDtoEquals(baseline, mapperResult);
        assertNull(mapperResult.wholesaleApplicationStatus);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Assertions
    // ══════════════════════════════════════════════════════════════════════════

    private void assertListItemDtoEquals(AdminCustomerListItemDto expected, AdminCustomerListItemDto actual) {
        assertEquals(expected.id, actual.id, "id mismatch");
        assertEquals(expected.firstName, actual.firstName, "firstName mismatch");
        assertEquals(expected.lastName, actual.lastName, "lastName mismatch");
        assertEquals(expected.email, actual.email, "email mismatch");
        assertEquals(expected.status, actual.status, "status mismatch");
        assertEquals(expected.shopperType, actual.shopperType, "shopperType mismatch");
        assertEquals(expected.registeredAt, actual.registeredAt, "registeredAt mismatch");
        assertEquals(expected.wholesaleApplicationStatus, actual.wholesaleApplicationStatus,
                "wholesaleApplicationStatus mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Builders
    // ══════════════════════════════════════════════════════════════════════════

    private CustomerEntity buildFullCustomer(UUID customerId) {
        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;
        customer.firstName = "Johan";
        customer.lastName = "van der Merwe";
        customer.phone = "0821234567";
        customer.status = CustomerStatusEn.ACTIVE;
        customer.shopperType = CustomerTypeEn.WHOLESALER;

        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID();
        user.email = "johan@example.com";
        user.createdAt = OffsetDateTime.parse("2026-01-15T09:00:00Z");
        customer.user = user;

        return customer;
    }

    private CustomerEntity buildRetailCustomer(UUID customerId) {
        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;
        customer.firstName = "Sarah";
        customer.lastName = "Smith";
        customer.phone = null;
        customer.status = CustomerStatusEn.ACTIVE;
        customer.shopperType = CustomerTypeEn.RETAILER;

        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID();
        user.email = "sarah@example.com";
        user.createdAt = OffsetDateTime.parse("2026-03-20T14:30:00Z");
        customer.user = user;

        return customer;
    }

    private WholesaleApplicationEntity buildApp(WholesaleApplicationStatusEn status) {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.id = UUID.randomUUID();
        app.status = status;
        app.firstName = "Test";
        app.companyName = "Test Co";
        return app;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panache stubs
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void stubWholesaleAppQuery(UUID customerId, WholesaleApplicationEntity app) {
        PanacheQuery<PanacheEntityBase> appQuery = mock(PanacheQuery.class);
        when(appQuery.firstResult()).thenReturn(app);
        when(WholesaleApplicationEntity.find("customer.id = ?1", customerId)).thenReturn(appQuery);
    }
}
