package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderLineItemDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.PageResponse;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed tests for the staff order surface.
 * <p>
 * These run against the shared local database, so no assertion may depend on
 * the total number of orders in it. Every fixture order is stamped into a
 * far-past date window that no real or operator-created row occupies, and the
 * list assertions filter on that window — which also exercises the date
 * filtering that {@code FilterRequest} cannot express and that this query
 * exists to provide.
 */
@QuarkusTest
class OrderAdminServiceIT
{
    /** A window no real order can fall in, so list assertions can be absolute. */
    private static final String WINDOW_FROM = "2001-03-01";
    private static final String WINDOW_TO = "2001-03-31";
    private static final LocalDateTime WINDOW_DAY = LocalDateTime.of(2001, 3, 15, 9, 0);

    @Inject
    OrderAdminService orderAdminService;

    @Inject
    EntityManager em;

    // ── Fixture builders ────────────────────────────────────────────────────

    private ProductVariantEntity newVariant(String marker, String sku)
    {
        ProductEntity product = new ProductEntity();
        product.setName(marker + " Product");
        product.setSlug((marker + "-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(50);
        variant.persist();
        return variant;
    }

    private CustomerEntity newCustomer(String firstName, String lastName)
    {
        UserEntity user = new UserEntity();
        user.setEmail(firstName.toLowerCase() + "-" + UUID.randomUUID() + "@test.example");
        user.setPasswordHash("irrelevant-test-hash");
        user.persist();

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName(firstName);
        customer.setLastName(lastName);
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.persist();
        return customer;
    }

    private OrderEntity newOrder(OrderStatusEn status, BigDecimal total, LocalDateTime placedAt)
    {
        OrderEntity order = new OrderEntity();
        order.setSessionId(UUID.randomUUID());
        order.setTotalAmount(total);
        order.setStatus(status);
        order.persist();
        stampCreatedAt(order, placedAt);
        return order;
    }

    /**
     * createdAt is a {@code @CreationTimestamp} the entity cannot set, so the
     * fixture's placement in time has to be written with a bulk update.
     */
    private void stampCreatedAt(OrderEntity order, LocalDateTime placedAt)
    {
        em.createQuery("update OrderEntity o set o.createdAt = :placedAt where o.id = :id")
                .setParameter("placedAt", placedAt)
                .setParameter("id", order.getId())
                .executeUpdate();
    }

    private void addLine(OrderEntity order, ProductVariantEntity variant, int quantity, String unitPrice)
    {
        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(variant);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.persist();
        order.getItems().add(item);
    }

    /** Bulk updates bypass the persistence context, so re-read from a clean one. */
    private void syncAndClear()
    {
        em.flush();
        em.clear();
    }

    private PageResponse<AdminOrderListItemDto> listWindow(int pageIndex, int pageSize, String status)
    {
        return orderAdminService.adminOrderList(pageIndex, pageSize, status, WINDOW_FROM, WINDOW_TO);
    }

    // ── List ────────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("lists orders in the date window newest first, with the row fields the admin table renders")
    void adminOrderList_withinDateWindow_returnsMappedRowsNewestFirst()
    {
        CustomerEntity customer = newCustomer("Thandi", "Nkosi");
        ProductVariantEntity variant = newVariant("Window", "WIN-SKU-" + UUID.randomUUID());

        OrderEntity older = newOrder(OrderStatusEn.PAID, new BigDecimal("250.00"), WINDOW_DAY);
        older.setCustomerEntity(customer);
        addLine(older, variant, 2, "100.00");

        OrderEntity newer = newOrder(OrderStatusEn.CREATED, new BigDecimal("60.00"), WINDOW_DAY.plusDays(1));
        newer.setContactFirstName("Guest");
        newer.setContactLastName("Shopper");
        addLine(newer, variant, 1, "50.00");

        syncAndClear();

        PageResponse<AdminOrderListItemDto> page = listWindow(0, 10, null);

        assertEquals(2, page.getTotalElements(), "the window must contain only this test's orders");
        assertEquals(2, page.getContent().size());

        AdminOrderListItemDto first = page.getContent().get(0);
        assertEquals(newer.getId().toString(), first.getId(), "newest order must come first");
        assertEquals("Guest Shopper", first.getCustomerName(),
                "a guest order has no customer record, so the checkout contact name is the only name there is");
        assertEquals(1, first.getItemCount());

        AdminOrderListItemDto second = page.getContent().get(1);
        assertEquals(older.getId().toString(), second.getId());
        assertEquals("Thandi Nkosi", second.getCustomerName());
        assertEquals(2, second.getItemCount(), "item count is the sum of quantities, not the number of lines");
        assertEquals("PAID", second.getStatus());
        assertEquals(0, new BigDecimal("250.00").compareTo(second.getTotal()));
        assertEquals("ORD-" + older.getId().toString().substring(0, 8).toUpperCase(), second.getReference());
    }

    @Test
    @TestTransaction
    @DisplayName("narrows the window by status, and counts only what the filter matches")
    void adminOrderList_withStatusFilter_returnsOnlyThatStatus()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.CANCELLED, new BigDecimal("30.00"), WINDOW_DAY);
        syncAndClear();

        assertEquals(3, listWindow(0, 10, null).getTotalElements());
        assertEquals(2, listWindow(0, 10, "PAID").getTotalElements());
        assertEquals(1, listWindow(0, 10, "CANCELLED").getTotalElements());
    }

    @Test
    @TestTransaction
    @DisplayName("treats the UI's ALL sentinel as no status filter at all")
    void adminOrderList_allSentinel_doesNotFilter()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.CANCELLED, new BigDecimal("20.00"), WINDOW_DAY);
        syncAndClear();

        assertEquals(2, listWindow(0, 10, "ALL").getTotalElements());
    }

    @Test
    @TestTransaction
    @DisplayName("pages the rows while the total counts every match, so the table can size its pager")
    void adminOrderList_pageSizeSmallerThanMatches_pagesRowsButCountsAll()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), WINDOW_DAY.plusDays(1));
        newOrder(OrderStatusEn.PAID, new BigDecimal("30.00"), WINDOW_DAY.plusDays(2));
        syncAndClear();

        PageResponse<AdminOrderListItemDto> firstPage = listWindow(0, 2, null);
        assertEquals(2, firstPage.getContent().size());
        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());

        PageResponse<AdminOrderListItemDto> secondPage = listWindow(1, 2, null);
        assertEquals(1, secondPage.getContent().size());
        assertEquals(3, secondPage.getTotalElements());
    }

    @Test
    @TestTransaction
    @DisplayName("excludes orders placed outside the requested day range, inclusive of the end day")
    void adminOrderList_datesOutsideWindow_areExcluded()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        // 23:30 on the final day: an inclusive end date must still include it.
        newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), LocalDateTime.of(2001, 3, 31, 23, 30));
        newOrder(OrderStatusEn.PAID, new BigDecimal("30.00"), LocalDateTime.of(2001, 4, 1, 0, 30));
        syncAndClear();

        assertEquals(2, listWindow(0, 10, null).getTotalElements());
    }

    @Test
    @DisplayName("rejects a malformed date rather than silently ignoring the filter")
    void adminOrderList_malformedDate_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderAdminService.adminOrderList(0, 10, null, "15-03-2001", null));
        assertTrue(ex.getMessage().contains("invalid fromDate"), ex.getMessage());
    }

    @Test
    @DisplayName("rejects a status that is not an OrderStatusEn value")
    void adminOrderList_unknownStatus_throws()
    {
        assertThrows(IllegalArgumentException.class,
                () -> orderAdminService.adminOrderList(0, 10, "SHIPPED", null, null));
    }

    // ── Detail ──────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("returns the lines, address, money breakdown and newest-first timeline for one order")
    void adminOrder_existingOrder_returnsFullDetail()
    {
        CustomerEntity customer = newCustomer("Lerato", "Dlamini");
        ProductVariantEntity variant = newVariant("Detail", "DET-SKU-" + UUID.randomUUID());

        OrderEntity order = newOrder(OrderStatusEn.PAID, new BigDecimal("345.00"), WINDOW_DAY);
        order.setCustomerEntity(customer);
        order.setStreetAddress("12 Loop Street");
        order.setCity("Cape Town");
        order.setProvince("Western Cape");
        order.setPostalCode("8001");
        addLine(order, variant, 3, "100.00");

        OrderStatusHistoryEntity.record(order, OrderStatusEn.CREATED, "Order placed", OrderService.SYSTEM_ACTOR);
        OrderStatusHistoryEntity.record(order, OrderStatusEn.PAID, "Payment confirmed by PayFast", OrderService.SYSTEM_ACTOR);
        syncAndClear();

        AdminOrderDetailDto detail = orderAdminService.adminOrder(order.getId());

        assertNotNull(detail);
        assertEquals("Lerato Dlamini", detail.getCustomerName());
        assertEquals(customer.getUser().getEmail(), detail.getCustomerEmail());
        assertEquals("ORD-" + order.getId().toString().substring(0, 8).toUpperCase(), detail.getReference());
        assertEquals(3, detail.getItemCount());

        assertEquals("12 Loop Street", detail.getShippingAddress().getStreet());
        assertEquals("Cape Town", detail.getShippingAddress().getCity());
        assertEquals("Western Cape", detail.getShippingAddress().getProvince());
        assertEquals("8001", detail.getShippingAddress().getPostalCode());

        assertEquals(1, detail.getLineItems().size());
        AdminOrderLineItemDto line = detail.getLineItems().get(0);
        assertEquals("Detail Product", line.getProductName());
        assertEquals(variant.getSku(), line.getVariantSku());
        assertEquals(3, line.getQuantity());
        assertEquals(0, new BigDecimal("300.00").compareTo(line.getLineTotal()),
                "line total is unit price times quantity, not a stored column");

        assertEquals(0, new BigDecimal("300.00").compareTo(detail.getSubtotal()));
        assertEquals(0, order.getTotalAmount().compareTo(detail.getGrandTotal()),
                "the grand total shown must be the amount the order was charged");
        assertNotNull(detail.getVatAmount());
        assertNotNull(detail.getShippingCost());

        List<String> timeline = detail.getStatusHistory().stream()
                .map(entry -> entry.getStatus() + "/" + entry.getStaffName())
                .toList();
        assertEquals(List.of("PAID/SYSTEM", "CREATED/SYSTEM"), timeline, "timeline must be newest first");
    }

    @Test
    @TestTransaction
    @DisplayName("falls back to the checkout contact details for a guest order with no customer record")
    void adminOrder_guestOrder_usesContactDetails()
    {
        OrderEntity order = newOrder(OrderStatusEn.CREATED, new BigDecimal("99.00"), WINDOW_DAY);
        order.setContactFirstName("Sipho");
        order.setContactLastName("Mahlangu");
        order.setContactEmail("sipho@guest.example");
        syncAndClear();

        AdminOrderDetailDto detail = orderAdminService.adminOrder(order.getId());

        assertEquals("Sipho Mahlangu", detail.getCustomerName());
        assertEquals("sipho@guest.example", detail.getCustomerEmail());
    }

    @Test
    @TestTransaction
    @DisplayName("returns null for an id no order has, so the resolver can answer not-found")
    void adminOrder_unknownId_returnsNull()
    {
        assertNull(orderAdminService.adminOrder(UUID.randomUUID()));
    }
}
