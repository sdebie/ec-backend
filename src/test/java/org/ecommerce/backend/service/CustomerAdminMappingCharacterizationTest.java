package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.dto.AdminOrderRefDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.query.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for CustomerAdminService entity→DTO mappings.
 * <p>
 * Pins the current output of:
 * - toListItemDto(CustomerEntity c) — queries WholesaleApplicationEntity for the customer
 * - toListItemDto(CustomerEntity c, WholesaleApplicationEntity app) — pure field copy
 * - toDetailDto(CustomerEntity c, WholesaleApplicationEntity app, List&lt;OrderEntity&gt; orders)
 * - toOrderRefDto(OrderEntity o)
 * <p>
 * These baselines guard against regressions when extracting these methods into
 * CustomerAdminMapper (Task 4.3).
 * <p>
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class CustomerAdminMappingCharacterizationTest
{
    @Inject
    CustomerAdminService customerAdminService;

    @io.quarkus.test.InjectMock
    org.ecommerce.common.repository.CustomerRepository customerRepository;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(WholesaleApplicationEntity.class);
        PanacheMock.mock(OrderEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer with wholesale application (via allCustomers)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithWholesaleApp_pinsAllFields()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, WholesaleApplicationStatusEn.APPROVED);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.getId(), app);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.getId());
        assertEquals("Johan", listItem.getFirstName());
        assertEquals("van der Merwe", listItem.getLastName());
        assertEquals("johan@example.com", listItem.getEmail());
        assertEquals("ACTIVE", listItem.getStatus());
        assertEquals("WHOLESALER", listItem.getShopperType());
        assertEquals(OffsetDateTime.parse("2026-01-15T09:00:00Z").toString(), listItem.getRegisteredAt());
        assertEquals("APPROVED", listItem.getWholesaleApplicationStatus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer without wholesale application
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithoutWholesaleApp_wholesaleApplicationStatusIsNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.getId(), null);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(
                pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.getId());
        assertEquals("Sarah", listItem.getFirstName());
        assertEquals("Smith", listItem.getLastName());
        assertEquals("sarah@example.com", listItem.getEmail());
        assertEquals("ACTIVE", listItem.getStatus());
        assertEquals("RETAILER", listItem.getShopperType());
        assertNotNull(listItem.getRegisteredAt());
        assertNull(listItem.getWholesaleApplicationStatus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer with null user (edge case)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithNullUser_emailAndRegisteredAtAreNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.setUser(null);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.getId(), null);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.getId());
        assertEquals("Johan", listItem.getFirstName());
        assertEquals("van der Merwe", listItem.getLastName());
        assertNull(listItem.getEmail(), "email should be null when user is null");
        assertEquals("ACTIVE", listItem.getStatus());
        assertEquals("WHOLESALER", listItem.getShopperType());
        assertNull(listItem.getRegisteredAt(), "registeredAt should be null when user is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — various wholesale application statuses
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_pendingWholesaleApp_statusMappedCorrectly()
    {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.PENDING, "PENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_rejectedWholesaleApp_statusMappedCorrectly()
    {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.REJECTED, "REJECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_convertedWholesaleApp_statusMappedCorrectly()
    {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.CONVERTED, "CONVERTED");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — fully populated customer with application and orders
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_fullyPopulated_pinsAllFields()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, WholesaleApplicationStatusEn.APPROVED);

        OrderEntity order1 = buildOrder(customerId, OrderStatusEn.PAID, new BigDecimal("1500.00"), LocalDateTime.of(2026, 7, 10, 14, 30, 0));
        OrderEntity order2 = buildOrder(customerId, OrderStatusEn.DELIVERED, new BigDecimal("3200.50"), LocalDateTime.of(2026, 7, 5, 9, 15, 0));

        stubPanacheForAdminCustomer(customerId, customer, app, List.of(order1, order2));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        // Customer fields
        assertEquals(customerId.toString(), detail.getId());
        assertEquals("Johan", detail.getFirstName());
        assertEquals("van der Merwe", detail.getLastName());
        assertEquals("johan@example.com", detail.getEmail());
        assertEquals("0821234567", detail.getPhone());
        assertEquals("ACTIVE", detail.getStatus());
        assertEquals("WHOLESALER", detail.getShopperType());
        assertEquals(OffsetDateTime.parse("2026-01-15T09:00:00Z").toString(), detail.getRegisteredAt());

        // Wholesale application — delegated to WholesaleMapper
        assertNotNull(detail.getWholesaleApplication(), "wholesaleApplication should be populated");

        // Recent orders
        assertNotNull(detail.getRecentOrders());
        assertEquals(2, detail.getRecentOrders().size());

        // First order
        AdminOrderRefDto orderRef1 = detail.getRecentOrders().get(0);
        assertEquals(order1.getId().toString(), orderRef1.getId());
        assertEquals("ORD-" + order1.getId().toString().substring(0, 8).toUpperCase(), orderRef1.getReference());
        assertEquals(order1.getCreatedAt().toString(), orderRef1.getPlacedAt());
        assertEquals(1500.00, orderRef1.getTotal(), 0.001);
        assertEquals("PAID", orderRef1.getStatus());

        // Second order
        AdminOrderRefDto orderRef2 = detail.getRecentOrders().get(1);
        assertEquals(order2.getId().toString(), orderRef2.getId());
        assertEquals("ORD-" + order2.getId().toString().substring(0, 8).toUpperCase(), orderRef2.getReference());
        assertEquals(order2.getCreatedAt().toString(), orderRef2.getPlacedAt());
        assertEquals(3200.50, orderRef2.getTotal(), 0.001);
        assertEquals("DELIVERED", orderRef2.getStatus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer without wholesale application
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_noWholesaleApp_wholesaleApplicationIsNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(customerId.toString(), detail.getId());
        assertEquals("Sarah", detail.getFirstName());
        assertEquals("Smith", detail.getLastName());
        assertEquals("sarah@example.com", detail.getEmail());
        assertNull(detail.getPhone());
        assertEquals("ACTIVE", detail.getStatus());
        assertEquals("RETAILER", detail.getShopperType());
        assertNull(detail.getWholesaleApplication(), "wholesaleApplication should be null");
        assertNotNull(detail.getRecentOrders());
        assertTrue(detail.getRecentOrders().isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer with null user
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_nullUser_emailAndRegisteredAtAreNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.setUser(null);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(customerId.toString(), detail.getId());
        assertNull(detail.getEmail(), "email should be null when user is null");
        assertNull(detail.getRegisteredAt(), "registeredAt should be null when user is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer with null status and shopperType
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_nullStatusAndShopperType_mapsToNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.setStatus(null);
        customer.setShopperType(null);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNull(detail.getStatus(), "status should be null when entity status is null");
        assertNull(detail.getShopperType(), "shopperType should be null when entity shopperType is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toOrderRefDto — pins reference format and field mapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_pinsReferenceFormat()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        // Create an order with a known UUID to verify the reference format
        OrderEntity order = new OrderEntity();
        order.setId(UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890"));
        order.setTotalAmount(new BigDecimal("999.99"));
        order.setStatus(OrderStatusEn.IN_TRANSIT);
        order.setCreatedAt(LocalDateTime.of(2026, 6, 20, 16, 45, 0));

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(1, detail.getRecentOrders().size());
        AdminOrderRefDto ref = detail.getRecentOrders().get(0);

        assertEquals("abcdef12-3456-7890-abcd-ef1234567890", ref.getId());
        assertEquals("ORD-ABCDEF12", ref.getReference(), "reference should be ORD- + first 8 chars of UUID uppercased");
        assertEquals("2026-06-20T16:45", ref.getPlacedAt());
        assertEquals(999.99, ref.getTotal(), 0.001);
        assertEquals("IN_TRANSIT", ref.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullCreatedAt_placedAtIsNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setStatus(OrderStatusEn.PENDING);
        order.setCreatedAt(null);

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.getRecentOrders().get(0);
        assertNull(ref.getPlacedAt(), "placedAt should be null when order.createdAt is null");
        assertEquals(250.00, ref.getTotal(), 0.001);
        assertEquals("PENDING", ref.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullTotalAmount_totalIsZero()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(null);
        order.setStatus(OrderStatusEn.CREATED);
        order.setCreatedAt(LocalDateTime.of(2026, 7, 1, 12, 0, 0));

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.getRecentOrders().get(0);
        assertEquals(0.0, ref.getTotal(), 0.001, "total should be 0.0 when totalAmount is null");
        assertEquals("CREATED", ref.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullStatus_statusIsNull()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(null);
        order.setCreatedAt(LocalDateTime.of(2026, 7, 2, 8, 0, 0));

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.getRecentOrders().get(0);
        assertNull(ref.getStatus(), "status should be null when order.status is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — empty orders list
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_emptyOrders_recentOrdersIsEmptyList()
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.getRecentOrders());
        assertTrue(detail.getRecentOrders().isEmpty(), "recentOrders should be empty when no orders exist");
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
        user.setEmail("sarah@example.com");
        user.setCreatedAt(OffsetDateTime.parse("2026-03-20T14:30:00Z"));
        customer.setUser(user);

        return customer;
    }

    private WholesaleApplicationEntity buildWholesaleApp(UUID customerId, WholesaleApplicationStatusEn status)
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setId(UUID.randomUUID());
        app.setApplicantEmail("johan@wholesale.co.za");
        app.setCompanyName("Johan Trading (Pty) Ltd");
        app.setFirstName("Johan");
        app.setStatus(status);
        app.setCreatedAt(OffsetDateTime.parse("2026-02-01T10:00:00Z"));

        CustomerEntity appCustomer = new CustomerEntity();
        appCustomer.setId(customerId);
        app.setCustomer(appCustomer);

        return app;
    }

    private OrderEntity buildOrder(UUID customerId, OrderStatusEn status, BigDecimal total, LocalDateTime createdAt)
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(total);
        order.setStatus(status);
        order.setCreatedAt(createdAt);

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        order.setCustomerEntity(customer);

        return order;
    }

    private PageRequest pageRequest(int pageIndex, int pageSize)
    {
        PageRequest pr = new PageRequest();
        pr.setPageIndex(pageIndex);
        pr.setPageSize(pageSize);
        return pr;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panache stubs
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void stubPanacheForAdminCustomer(UUID customerId, CustomerEntity customer,
                                             WholesaleApplicationEntity app, List<OrderEntity> orders)
    {
        // CustomerEntity.findById(customerId) → customer
        when(CustomerEntity.findById(customerId)).thenReturn(customer);

        // WholesaleApplicationEntity.find("customer.id = ?1", customerId) → app
        PanacheQuery<PanacheEntityBase> appQuery = mock(PanacheQuery.class);
        when(appQuery.firstResult()).thenReturn(app);
        when(WholesaleApplicationEntity.find("customer.id = ?1", customerId)).thenReturn(appQuery);

        // OrderEntity.find("customerEntity.id = ?1 order by createdAt desc", customerId) → orders
        PanacheQuery<PanacheEntityBase> orderQuery = mock(PanacheQuery.class);
        when(orderQuery.page(0, 10)).thenReturn(orderQuery);
        when(orderQuery.list()).thenReturn(orders != null ? (List) orders : Collections.emptyList());
        when(OrderEntity.find("customerEntity.id = ?1 order by createdAt desc", customerId)).thenReturn(orderQuery);
    }

    @SuppressWarnings("unchecked")
    private void stubAllCustomersReturning(List<CustomerEntity> customers)
    {
        // allCustomers now delegates the filter→query to CustomerRepository.
        when(customerRepository.findForAdmin(any(), any())).thenReturn(customers);
    }

    @SuppressWarnings("unchecked")
    private void stubWholesaleAppForCustomer(UUID customerId, WholesaleApplicationEntity app)
    {
        PanacheQuery<PanacheEntityBase> appQuery = mock(PanacheQuery.class);
        when(appQuery.firstResult()).thenReturn(app);
        when(WholesaleApplicationEntity.find("customer.id = ?1", customerId)).thenReturn(appQuery);
    }

    @SuppressWarnings("unchecked")
    private void assertListItemWholesaleStatus(WholesaleApplicationStatusEn appStatus, String expectedDtoStatus)
    {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, appStatus);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.getId(), app);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(pageRequest(0, 10), null);

        assertEquals(1, result.size());
        assertEquals(expectedDtoStatus, result.get(0).getWholesaleApplicationStatus(), "wholesaleApplicationStatus should be " + expectedDtoStatus);
    }
}
