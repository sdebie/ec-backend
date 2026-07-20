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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for CustomerAdminService entity→DTO mappings.
 *
 * Pins the current output of:
 * - toListItemDto(CustomerEntity c) — queries WholesaleApplicationEntity for the customer
 * - toListItemDto(CustomerEntity c, WholesaleApplicationEntity app) — pure field copy
 * - toDetailDto(CustomerEntity c, WholesaleApplicationEntity app, List&lt;OrderEntity&gt; orders)
 * - toOrderRefDto(OrderEntity o)
 *
 * These baselines guard against regressions when extracting these methods into
 * CustomerAdminMapper (Task 4.3).
 *
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class CustomerAdminMappingCharacterizationTest {

    @Inject
    CustomerAdminService customerAdminService;

    @io.quarkus.test.InjectMock
    org.ecommerce.common.repository.CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(WholesaleApplicationEntity.class);
        PanacheMock.mock(OrderEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer with wholesale application (via allCustomers)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithWholesaleApp_pinsAllFields() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, WholesaleApplicationStatusEn.APPROVED);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.id, app);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(
                pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.id);
        assertEquals("Johan", listItem.firstName);
        assertEquals("van der Merwe", listItem.lastName);
        assertEquals("johan@example.com", listItem.email);
        assertEquals("ACTIVE", listItem.status);
        assertEquals("WHOLESALER", listItem.shopperType);
        assertEquals(OffsetDateTime.parse("2026-01-15T09:00:00Z").toString(), listItem.registeredAt);
        assertEquals("APPROVED", listItem.wholesaleApplicationStatus);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer without wholesale application
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithoutWholesaleApp_wholesaleApplicationStatusIsNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.id, null);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(
                pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.id);
        assertEquals("Sarah", listItem.firstName);
        assertEquals("Smith", listItem.lastName);
        assertEquals("sarah@example.com", listItem.email);
        assertEquals("ACTIVE", listItem.status);
        assertEquals("RETAILER", listItem.shopperType);
        assertNotNull(listItem.registeredAt);
        assertNull(listItem.wholesaleApplicationStatus);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — customer with null user (edge case)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_customerWithNullUser_emailAndRegisteredAtAreNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.user = null;

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.id, null);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(
                pageRequest(0, 10), null);

        assertEquals(1, result.size());
        AdminCustomerListItemDto listItem = result.get(0);

        assertEquals(customerId.toString(), listItem.id);
        assertEquals("Johan", listItem.firstName);
        assertEquals("van der Merwe", listItem.lastName);
        assertNull(listItem.email, "email should be null when user is null");
        assertEquals("ACTIVE", listItem.status);
        assertEquals("WHOLESALER", listItem.shopperType);
        assertNull(listItem.registeredAt, "registeredAt should be null when user is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toListItemDto — various wholesale application statuses
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_pendingWholesaleApp_statusMappedCorrectly() {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.PENDING, "PENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_rejectedWholesaleApp_statusMappedCorrectly() {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.REJECTED, "REJECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toListItemDto_convertedWholesaleApp_statusMappedCorrectly() {
        assertListItemWholesaleStatus(WholesaleApplicationStatusEn.CONVERTED, "CONVERTED");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — fully populated customer with application and orders
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_fullyPopulated_pinsAllFields() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, WholesaleApplicationStatusEn.APPROVED);

        OrderEntity order1 = buildOrder(customerId, OrderStatusEn.PAID, new BigDecimal("1500.00"),
                LocalDateTime.of(2026, 7, 10, 14, 30, 0));
        OrderEntity order2 = buildOrder(customerId, OrderStatusEn.DELIVERED, new BigDecimal("3200.50"),
                LocalDateTime.of(2026, 7, 5, 9, 15, 0));

        stubPanacheForAdminCustomer(customerId, customer, app, List.of(order1, order2));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        // Customer fields
        assertEquals(customerId.toString(), detail.id);
        assertEquals("Johan", detail.firstName);
        assertEquals("van der Merwe", detail.lastName);
        assertEquals("johan@example.com", detail.email);
        assertEquals("0821234567", detail.phone);
        assertEquals("ACTIVE", detail.status);
        assertEquals("WHOLESALER", detail.shopperType);
        assertEquals(OffsetDateTime.parse("2026-01-15T09:00:00Z").toString(), detail.registeredAt);

        // Wholesale application — delegated to WholesaleMapper
        assertNotNull(detail.wholesaleApplication, "wholesaleApplication should be populated");

        // Recent orders
        assertNotNull(detail.recentOrders);
        assertEquals(2, detail.recentOrders.size());

        // First order
        AdminOrderRefDto orderRef1 = detail.recentOrders.get(0);
        assertEquals(order1.id.toString(), orderRef1.id);
        assertEquals("ORD-" + order1.id.toString().substring(0, 8).toUpperCase(), orderRef1.reference);
        assertEquals(order1.createdAt.toString(), orderRef1.placedAt);
        assertEquals(1500.00, orderRef1.total, 0.001);
        assertEquals("PAID", orderRef1.status);

        // Second order
        AdminOrderRefDto orderRef2 = detail.recentOrders.get(1);
        assertEquals(order2.id.toString(), orderRef2.id);
        assertEquals("ORD-" + order2.id.toString().substring(0, 8).toUpperCase(), orderRef2.reference);
        assertEquals(order2.createdAt.toString(), orderRef2.placedAt);
        assertEquals(3200.50, orderRef2.total, 0.001);
        assertEquals("DELIVERED", orderRef2.status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer without wholesale application
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_noWholesaleApp_wholesaleApplicationIsNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(customerId.toString(), detail.id);
        assertEquals("Sarah", detail.firstName);
        assertEquals("Smith", detail.lastName);
        assertEquals("sarah@example.com", detail.email);
        assertNull(detail.phone);
        assertEquals("ACTIVE", detail.status);
        assertEquals("RETAILER", detail.shopperType);
        assertNull(detail.wholesaleApplication, "wholesaleApplication should be null");
        assertNotNull(detail.recentOrders);
        assertTrue(detail.recentOrders.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer with null user
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_nullUser_emailAndRegisteredAtAreNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.user = null;

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(customerId.toString(), detail.id);
        assertNull(detail.email, "email should be null when user is null");
        assertNull(detail.registeredAt, "registeredAt should be null when user is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — customer with null status and shopperType
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_nullStatusAndShopperType_mapsToNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        customer.status = null;
        customer.shopperType = null;

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNull(detail.status, "status should be null when entity status is null");
        assertNull(detail.shopperType, "shopperType should be null when entity shopperType is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toOrderRefDto — pins reference format and field mapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_pinsReferenceFormat() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        // Create an order with a known UUID to verify the reference format
        OrderEntity order = new OrderEntity();
        order.id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        order.totalAmount = new BigDecimal("999.99");
        order.status = OrderStatusEn.IN_TRANSIT;
        order.createdAt = LocalDateTime.of(2026, 6, 20, 16, 45, 0);

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertEquals(1, detail.recentOrders.size());
        AdminOrderRefDto ref = detail.recentOrders.get(0);

        assertEquals("abcdef12-3456-7890-abcd-ef1234567890", ref.id);
        assertEquals("ORD-ABCDEF12", ref.reference, "reference should be ORD- + first 8 chars of UUID uppercased");
        assertEquals("2026-06-20T16:45", ref.placedAt);
        assertEquals(999.99, ref.total, 0.001);
        assertEquals("IN_TRANSIT", ref.status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullCreatedAt_placedAtIsNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.totalAmount = new BigDecimal("250.00");
        order.status = OrderStatusEn.PENDING;
        order.createdAt = null;

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.recentOrders.get(0);
        assertNull(ref.placedAt, "placedAt should be null when order.createdAt is null");
        assertEquals(250.00, ref.total, 0.001);
        assertEquals("PENDING", ref.status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullTotalAmount_totalIsZero() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.totalAmount = null;
        order.status = OrderStatusEn.CREATED;
        order.createdAt = LocalDateTime.of(2026, 7, 1, 12, 0, 0);

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.recentOrders.get(0);
        assertEquals(0.0, ref.total, 0.001, "total should be 0.0 when totalAmount is null");
        assertEquals("CREATED", ref.status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOrderRefDto_nullStatus_statusIsNull() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildRetailCustomer(customerId);

        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.totalAmount = new BigDecimal("100.00");
        order.status = null;
        order.createdAt = LocalDateTime.of(2026, 7, 2, 8, 0, 0);

        stubPanacheForAdminCustomer(customerId, customer, null, List.of(order));

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        AdminOrderRefDto ref = detail.recentOrders.get(0);
        assertNull(ref.status, "status should be null when order.status is null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — empty orders list
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void toDetailDto_emptyOrders_recentOrdersIsEmptyList() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);

        stubPanacheForAdminCustomer(customerId, customer, null, Collections.emptyList());

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.recentOrders);
        assertTrue(detail.recentOrders.isEmpty(), "recentOrders should be empty when no orders exist");
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
        user.email = "sarah@example.com";
        user.createdAt = OffsetDateTime.parse("2026-03-20T14:30:00Z");
        customer.user = user;

        return customer;
    }

    private WholesaleApplicationEntity buildWholesaleApp(UUID customerId, WholesaleApplicationStatusEn status) {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.id = UUID.randomUUID();
        app.applicantEmail = "johan@wholesale.co.za";
        app.companyName = "Johan Trading (Pty) Ltd";
        app.firstName = "Johan";
        app.status = status;
        app.createdAt = OffsetDateTime.parse("2026-02-01T10:00:00Z");

        CustomerEntity appCustomer = new CustomerEntity();
        appCustomer.id = customerId;
        app.customer = appCustomer;

        return app;
    }

    private OrderEntity buildOrder(UUID customerId, OrderStatusEn status, BigDecimal total, LocalDateTime createdAt) {
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.totalAmount = total;
        order.status = status;
        order.createdAt = createdAt;

        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;
        order.customerEntity = customer;

        return order;
    }

    private PageRequest pageRequest(int pageIndex, int pageSize) {
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
                                             WholesaleApplicationEntity app, List<OrderEntity> orders) {
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
    private void stubAllCustomersReturning(List<CustomerEntity> customers) {
        // allCustomers now delegates the filter→query to CustomerRepository.
        when(customerRepository.findForAdmin(any(), any())).thenReturn(customers);
    }

    @SuppressWarnings("unchecked")
    private void stubWholesaleAppForCustomer(UUID customerId, WholesaleApplicationEntity app) {
        PanacheQuery<PanacheEntityBase> appQuery = mock(PanacheQuery.class);
        when(appQuery.firstResult()).thenReturn(app);
        when(WholesaleApplicationEntity.find("customer.id = ?1", customerId)).thenReturn(appQuery);
    }

    @SuppressWarnings("unchecked")
    private void assertListItemWholesaleStatus(WholesaleApplicationStatusEn appStatus, String expectedDtoStatus) {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = buildFullCustomer(customerId);
        WholesaleApplicationEntity app = buildWholesaleApp(customerId, appStatus);

        stubAllCustomersReturning(List.of(customer));
        stubWholesaleAppForCustomer(customer.id, app);

        List<AdminCustomerListItemDto> result = customerAdminService.allCustomers(
                pageRequest(0, 10), null);

        assertEquals(1, result.size());
        assertEquals(expectedDtoStatus, result.get(0).wholesaleApplicationStatus,
                "wholesaleApplicationStatus should be " + expectedDtoStatus);
    }
}
