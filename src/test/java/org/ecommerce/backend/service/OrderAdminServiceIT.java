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
import org.ecommerce.common.entity.PaymentLogEntity;
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

    private PageResponse<AdminOrderListItemDto> listWindow(int pageIndex, int pageSize, String paymentState)
    {
        return listWindow(pageIndex, pageSize, paymentState, null, null);
    }

    private PageResponse<AdminOrderListItemDto> listWindow(String paymentState, String fulfilmentState)
    {
        return orderAdminService.adminOrderList(0, 10, paymentState, fulfilmentState, WINDOW_FROM, WINDOW_TO, null, null);
    }

    private PageResponse<AdminOrderListItemDto> listWindow(int pageIndex, int pageSize, String paymentState, String sortBy, String sortDir)
    {
        return orderAdminService.adminOrderList(pageIndex, pageSize, paymentState, null, WINDOW_FROM, WINDOW_TO, sortBy, sortDir);
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
    @DisplayName("narrows the window by payment state, and counts only what the filter matches")
    void adminOrderList_withPaymentFilter_returnsOnlyThatState()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.PROCESSING, new BigDecimal("20.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.ADMIN_CANCELED, new BigDecimal("30.00"), WINDOW_DAY);
        syncAndClear();

        assertEquals(3, listWindow(0, 10, null).getTotalElements());
        // PAID and PROCESSING are different statuses but the same answer to "has the money
        // arrived?", which is the point of filtering on the facet rather than the status.
        assertEquals(2, listWindow(0, 10, "PAID").getTotalElements());
        assertEquals(1, listWindow(0, 10, "CANCELLED").getTotalElements());
    }

    @Test
    @TestTransaction
    @DisplayName("intersects the two facets, so paid-but-unpicked is findable on its own")
    void adminOrderList_bothFacets_intersects()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.PROCESSING, new BigDecimal("20.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.DELIVERED, new BigDecimal("30.00"), WINDOW_DAY);
        syncAndClear();

        assertEquals(3, listWindow("PAID", null).getTotalElements(), "all three are paid");
        assertEquals(1, listWindow("PAID", "NOT_STARTED").getTotalElements(), "only the unpicked one");
        assertEquals(1, listWindow("PAID", "PROCESSING").getTotalElements());
        assertEquals(1, listWindow("PAID", "COMPLETED").getTotalElements());
    }

    /**
     * A pair with no overlap is a valid question with no answers, not a query — and
     * `status in ()` is not valid SQL, so it must never reach the database.
     */
    @Test
    @TestTransaction
    @DisplayName("returns an empty page when the two facets cannot overlap")
    void adminOrderList_contradictoryFacets_returnsEmpty()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        syncAndClear();

        PageResponse<AdminOrderListItemDto> page = listWindow("AWAITING", "COMPLETED");
        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    @Test
    @TestTransaction
    @DisplayName("treats the UI's ALL sentinel as no filter at all")
    void adminOrderList_allSentinel_doesNotFilter()
    {
        newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.ADMIN_CANCELED, new BigDecimal("20.00"), WINDOW_DAY);
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

    // ── Sort ────────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("with no sort given, orders newest first — the same default as before sorting existed")
    void adminOrderList_noSort_defaultsToNewestFirst()
    {
        OrderEntity older = newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        OrderEntity newer = newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), WINDOW_DAY.plusDays(1));
        syncAndClear();

        List<String> ids = listWindow(0, 10, null, null, null).getContent().stream()
                .map(AdminOrderListItemDto::getId)
                .toList();

        assertEquals(List.of(newer.getId().toString(), older.getId().toString()), ids);
    }

    @Test
    @TestTransaction
    @DisplayName("sorts by total in both directions")
    void adminOrderList_sortByTotal_ordersBothDirections()
    {
        OrderEntity small = newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        OrderEntity large = newOrder(OrderStatusEn.PAID, new BigDecimal("99.00"), WINDOW_DAY.plusDays(1));
        syncAndClear();

        List<String> ascending = listWindow(0, 10, null, "totalAmount", "ASC").getContent().stream()
                .map(AdminOrderListItemDto::getId)
                .toList();
        assertEquals(List.of(small.getId().toString(), large.getId().toString()), ascending);

        List<String> descending = listWindow(0, 10, null, "totalAmount", "DESC").getContent().stream()
                .map(AdminOrderListItemDto::getId)
                .toList();
        assertEquals(List.of(large.getId().toString(), small.getId().toString()), descending);
    }

    @Test
    @TestTransaction
    @DisplayName("sorts by status")
    void adminOrderList_sortByStatus_orders()
    {
        // CREATED < PAID alphabetically, and placed in the OPPOSITE order to their creation
        // timestamps — a fixture where status order and createdAt order agreed would pass
        // just as well against the createdAt-desc fallback this is supposed to distinguish
        // itself from, proving nothing about status actually being read.
        newOrder(OrderStatusEn.CREATED, new BigDecimal("10.00"), WINDOW_DAY);
        newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), WINDOW_DAY.plusDays(1));
        syncAndClear();

        List<String> ascending = listWindow(0, 10, null, "status", "ASC").getContent().stream()
                .map(AdminOrderListItemDto::getStatus)
                .toList();
        assertEquals(List.of("CREATED", "PAID"), ascending);
    }

    /**
     * The repository's own defence, exercised through the real service and a real query —
     * proof this rejects the request the same way an unknown column would if it reached raw
     * JPQL, rather than merely documenting that intent.
     */
    @Test
    @TestTransaction
    @DisplayName("an unsortable or unknown column falls back to the default sort rather than erroring")
    void adminOrderList_unsortableColumn_fallsBackToDefault()
    {
        OrderEntity older = newOrder(OrderStatusEn.PAID, new BigDecimal("10.00"), WINDOW_DAY);
        OrderEntity newer = newOrder(OrderStatusEn.PAID, new BigDecimal("20.00"), WINDOW_DAY.plusDays(1));
        syncAndClear();

        // itemCount is computed, not a column — exactly the trap the frontend column
        // definitions were written to avoid triggering.
        List<String> ids = listWindow(0, 10, null, "itemCount", "ASC").getContent().stream()
                .map(AdminOrderListItemDto::getId)
                .toList();

        assertEquals(List.of(newer.getId().toString(), older.getId().toString()), ids,
                "must fall back to newest-first, not attempt to sort by a non-existent column");
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
                () -> orderAdminService.adminOrderList(0, 10, null, null, "15-03-2001", null, null, null));
        assertTrue(ex.getMessage().contains("invalid fromDate"), ex.getMessage());
    }

    @Test
    @DisplayName("rejects a facet value that is not one of its enum's constants")
    void adminOrderList_unknownFacet_throws()
    {
        assertThrows(IllegalArgumentException.class,
                () -> orderAdminService.adminOrderList(0, 10, "SHIPPED", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> orderAdminService.adminOrderList(0, 10, null, "SHIPPED", null, null, null, null));
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
        // newOrder() sets totalAmount directly and leaves vatAmount/shippingCost unset,
        // the shape of every order placed before those columns existed — exercising the
        // live-estimate fallback in OrderService.totalsForAdminDetail. A pinned-order
        // exact breakdown is covered by OrderAdminServiceHistoricalTotalsIT.
        assertNotNull(detail.getVatAmount());
        assertNotNull(detail.getShippingCost());

        List<String> timeline = detail.getStatusHistory().stream()
                .map(entry -> entry.getStatus() + "/" + entry.getStaffName())
                .toList();
        assertEquals(List.of("PAID/SYSTEM", "CREATED/SYSTEM"), timeline, "timeline must be newest first");

        assertNull(detail.getLatestPayment(), "no payment log was ever written for this order");
    }

    @Test
    @TestTransaction
    @DisplayName("surfaces the linked payment log as latestPayment (BACKLOG.md payment-logs-never-linked-to-their-order)")
    void adminOrder_withPaymentLog_includesLatestPayment()
    {
        OrderEntity order = newOrder(OrderStatusEn.PAID, new BigDecimal("150.00"), WINDOW_DAY);
        syncAndClear();

        PaymentLogEntity.record(order, "PAYFAST", order.getId().toString(), "pf-77001",
                new BigDecimal("150.00"), "COMPLETE", "{}");

        AdminOrderDetailDto detail = orderAdminService.adminOrder(order.getId());

        assertNotNull(detail.getLatestPayment(), "a linked payment log must surface on the admin detail");
        assertEquals("PAYFAST", detail.getLatestPayment().getGateway());
        assertEquals("pf-77001", detail.getLatestPayment().getExternalReference());
        assertEquals(0, new BigDecimal("150.00").compareTo(detail.getLatestPayment().getAmountGross()));
        assertEquals("COMPLETE", detail.getLatestPayment().getStatus());
        assertNotNull(detail.getLatestPayment().getReceivedAt());
    }

    @Test
    @TestTransaction
    @DisplayName("when more than one payment log exists, latestPayment is the newest one, not the first")
    void adminOrder_withMultiplePaymentLogs_returnsTheNewestOne()
    {
        OrderEntity order = newOrder(OrderStatusEn.PAID, new BigDecimal("150.00"), WINDOW_DAY);
        syncAndClear();

        PaymentLogEntity older = PaymentLogEntity.record(order, "PAYFAST", order.getId().toString(), "pf-older",
                new BigDecimal("150.00"), "FAILED", "{}");
        PaymentLogEntity newer = PaymentLogEntity.record(order, "PAYFAST", order.getId().toString(), "pf-newer",
                new BigDecimal("150.00"), "COMPLETE", "{}");

        em.createQuery("update PaymentLogEntity l set l.createdAt = :t where l.id = :id")
                .setParameter("t", WINDOW_DAY.minusMinutes(10))
                .setParameter("id", older.getId())
                .executeUpdate();
        em.createQuery("update PaymentLogEntity l set l.createdAt = :t where l.id = :id")
                .setParameter("t", WINDOW_DAY)
                .setParameter("id", newer.getId())
                .executeUpdate();
        syncAndClear();

        AdminOrderDetailDto detail = orderAdminService.adminOrder(order.getId());

        assertEquals("pf-newer", detail.getLatestPayment().getExternalReference());
        assertEquals("COMPLETE", detail.getLatestPayment().getStatus());
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
