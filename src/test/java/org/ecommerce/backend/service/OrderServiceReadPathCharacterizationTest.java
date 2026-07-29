package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for OrderService read-path DTO construction.
 * <p>
 * Pins the current output of:
 * - getOrderById(UUID)
 * - getOrderDetail(UUID)
 * - getAllOrders(PageRequest, FilterRequest)
 * - getMyOrders(UUID)
 * - getLatestOrderBySessionId(String)
 * <p>
 * These baselines guard against regressions when extracting DTO mapping
 * into OrderMapper (Task 10.2).
 * <p>
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class OrderServiceReadPathCharacterizationTest
{
    @Inject
    OrderService orderService;

    @InjectMock
    OrderRepository orderRepository;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
        PanacheMock.mock(OrderStatusHistoryEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared fixture builders
    // ══════════════════════════════════════════════════════════════════════════

    private OrderEntity buildFullyPopulatedOrder()
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        order.setSessionId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        order.setStatus(OrderStatusEn.PAID);
        order.setTotalAmount(new BigDecimal("1250.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 7, 15, 10, 30, 0));
        order.setStreetAddress("123 Main Street");
        order.setCity("Cape Town");
        order.setProvince("Western Cape");
        order.setPostalCode("8001");

        // Customer
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        UserEntity user = new UserEntity();
        user.setEmail("customer@example.com");
        customer.setUser(user);
        order.setCustomerEntity(customer);

        // Items with variant, product, images
        order.setItems(new ArrayList<>());

        ProductEntity product1 = new ProductEntity();
        product1.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        product1.setName("Premium Widget");

        ProductImageEntity image1 = new ProductImageEntity();
        image1.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        image1.setImageUrl("https://cdn.example.com/widget1.jpg");
        image1.setSortOrder(1);
        image1.setIsFeatured(true);

        ProductImageEntity image2 = new ProductImageEntity();
        image2.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        image2.setImageUrl("https://cdn.example.com/widget2.jpg");
        image2.setSortOrder(2);
        image2.setIsFeatured(false);

        ProductVariantEntity variant1 = new ProductVariantEntity();
        variant1.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        variant1.setStockQuantity(50);
        variant1.setAttributesJson("{\"color\":\"red\",\"size\":\"L\"}");
        variant1.setWeightKg(new BigDecimal("0.75"));
        variant1.setProduct(product1);
        variant1.setImages(List.of(image1, image2));

        OrderItemEntity item1 = new OrderItemEntity();
        item1.setId(UUID.fromString("88888888-8888-8888-8888-888888888888"));
        item1.setOrderEntity(order);
        item1.setVariant(variant1);
        item1.setQuantity(3);
        item1.setUnitPrice(new BigDecimal("250.00"));
        order.getItems().add(item1);

        // Second item — different product, no images
        ProductEntity product2 = new ProductEntity();
        product2.setId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
        product2.setName("Basic Gadget");

        ProductVariantEntity variant2 = new ProductVariantEntity();
        variant2.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        variant2.setStockQuantity(100);
        variant2.setAttributesJson(null);
        variant2.setWeightKg(new BigDecimal("1.20"));
        variant2.setProduct(product2);
        variant2.setImages(Collections.emptyList());

        OrderItemEntity item2 = new OrderItemEntity();
        item2.setId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        item2.setOrderEntity(order);
        item2.setVariant(variant2);
        item2.setQuantity(2);
        item2.setUnitPrice(new BigDecimal("125.00"));
        order.getItems().add(item2);

        return order;
    }

    private OrderEntity buildMinimalOrder()
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        order.setSessionId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setTotalAmount(new BigDecimal("0.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        order.setStreetAddress(null);
        order.setCity(null);
        order.setProvince(null);
        order.setPostalCode(null);
        order.setCustomerEntity(null);
        order.setItems(new ArrayList<>());
        return order;
    }

    private OrderEntity buildOrderWithNullVariant()
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"));
        order.setSessionId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        order.setStatus(OrderStatusEn.PENDING);
        order.setTotalAmount(new BigDecimal("50.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 3, 10, 14, 0, 0));
        order.setCustomerEntity(null);

        OrderItemEntity item = new OrderItemEntity();
        item.setId(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        item.setOrderEntity(order);
        item.setVariant(null);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("50.00"));

        order.setItems(new ArrayList<>());
        order.getItems().add(item);
        return order;
    }

    private List<OrderStatusHistoryEntity> buildStatusHistory(OrderEntity order)
    {
        OrderStatusHistoryEntity h1 = new OrderStatusHistoryEntity();
        h1.setId(UUID.fromString("aaa11111-1111-1111-1111-111111111111"));
        h1.setOrder(order);
        h1.setStatus(OrderStatusEn.PAID);
        h1.setComment("Payment confirmed");
        h1.setChangedBy("SYSTEM");
        h1.setCreatedAt(LocalDateTime.of(2026, 7, 15, 10, 35, 0));

        OrderStatusHistoryEntity h2 = new OrderStatusHistoryEntity();
        h2.setId(UUID.fromString("aaa22222-2222-2222-2222-222222222222"));
        h2.setOrder(order);
        h2.setStatus(OrderStatusEn.CREATED);
        h2.setComment("Order created");
        h2.setChangedBy(null);
        h2.setCreatedAt(LocalDateTime.of(2026, 7, 15, 10, 30, 0));

        return List.of(h1, h2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getOrderById — pins OrderResponseDto construction via OrderMapper
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getOrderById")
    class GetOrderByIdTests
    {

        @Test
        @DisplayName("fully populated order — pins all OrderResponseDto fields")
        void fullyPopulatedOrder_pinsAllFields()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.getId());

            assertNotNull(dto);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.getId());
            assertEquals("22222222-2222-2222-2222-222222222222", dto.getSessionId());
            assertEquals("PAID", dto.getStatus());
            assertEquals(order.getCreatedAt().toString(), dto.getCreateDate());
            assertEquals(new BigDecimal("1250.00"), dto.getTotalAmount());
            assertEquals(2, dto.getItemCount());

            // Customer
            assertNotNull(dto.getCustomer());
            assertEquals("customer@example.com", dto.getCustomer().getEmail());

            // Items
            assertNotNull(dto.getItems());
            assertEquals(2, dto.getItems().size());

            // First item
            var item1Dto = dto.getItems().get(0);
            assertEquals("88888888-8888-8888-8888-888888888888", item1Dto.getId());
            assertEquals(new BigDecimal("250.00"), item1Dto.getUnitPrice());
            assertEquals(3, item1Dto.getQuantity());
            assertNotNull(item1Dto.getVariant());
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), item1Dto.getVariant().getId());
            assertEquals(50, item1Dto.getVariant().getStockQuantity());
            assertEquals("{\"color\":\"red\",\"size\":\"L\"}", item1Dto.getVariant().getAttributesJson());
            assertEquals(new BigDecimal("0.75"), item1Dto.getVariant().getWeightKg());
            assertNotNull(item1Dto.getVariant().getProduct());
            assertEquals("Premium Widget", item1Dto.getVariant().getProduct().getName());

            // Images on first item's variant
            assertNotNull(item1Dto.getVariant().getImages());
            assertEquals(2, item1Dto.getVariant().getImages().size());
            assertEquals("55555555-5555-5555-5555-555555555555", item1Dto.getVariant().getImages().get(0).getId());
            assertEquals("https://cdn.example.com/widget1.jpg", item1Dto.getVariant().getImages().get(0).getImageUrl());
            assertEquals(1, item1Dto.getVariant().getImages().get(0).getSortOrder());
            assertTrue(item1Dto.getVariant().getImages().get(0).isFeatured());
            assertEquals("66666666-6666-6666-6666-666666666666", item1Dto.getVariant().getImages().get(1).getId());
            assertEquals("https://cdn.example.com/widget2.jpg", item1Dto.getVariant().getImages().get(1).getImageUrl());
            assertEquals(2, item1Dto.getVariant().getImages().get(1).getSortOrder());
            assertFalse(item1Dto.getVariant().getImages().get(1).isFeatured());

            // Second item — no images
            var item2Dto = dto.getItems().get(1);
            assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", item2Dto.getId());
            assertEquals(new BigDecimal("125.00"), item2Dto.getUnitPrice());
            assertEquals(2, item2Dto.getQuantity());
            assertNotNull(item2Dto.getVariant());
            assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), item2Dto.getVariant().getId());
            assertEquals(100, item2Dto.getVariant().getStockQuantity());
            assertNull(item2Dto.getVariant().getAttributesJson());
            assertEquals(new BigDecimal("1.20"), item2Dto.getVariant().getWeightKg());
            assertNotNull(item2Dto.getVariant().getProduct());
            assertEquals("Basic Gadget", item2Dto.getVariant().getProduct().getName());
            assertNotNull(item2Dto.getVariant().getImages());
            assertTrue(item2Dto.getVariant().getImages().isEmpty());
        }

        @Test
        @DisplayName("null orderId — returns null")
        void nullOrderId_returnsNull()
        {
            OrderResponseDto dto = orderService.getOrderById(null);
            assertNull(dto);
        }

        @Test
        @DisplayName("minimal order (no customer, no items) — pins null handling")
        void minimalOrder_pinsNullHandling()
        {
            OrderEntity order = buildMinimalOrder();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.getId());

            assertNotNull(dto);
            assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", dto.getId());
            assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", dto.getSessionId());
            assertEquals("CREATED", dto.getStatus());
            assertEquals(order.getCreatedAt().toString(), dto.getCreateDate());
            assertEquals(new BigDecimal("0.00"), dto.getTotalAmount());
            assertNull(dto.getCustomer());
            assertNotNull(dto.getItems());
            assertTrue(dto.getItems().isEmpty());
            assertEquals(0, dto.getItemCount());
        }

        @Test
        @DisplayName("order with null variant item — pins null variant handling")
        void orderWithNullVariant_pinsNullVariantHandling()
        {
            OrderEntity order = buildOrderWithNullVariant();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.getId());

            assertNotNull(dto);
            assertEquals(1, dto.getItems().size());
            var itemDto = dto.getItems().get(0);
            assertEquals("12345678-1234-1234-1234-123456789abc", itemDto.getId());
            assertEquals(new BigDecimal("50.00"), itemDto.getUnitPrice());
            assertEquals(1, itemDto.getQuantity());
            assertNull(itemDto.getVariant());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getOrderDetail — pins OrderDetailRespDto (inline mapping in service)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getOrderDetail")
    class GetOrderDetailTests
    {

        @Test
        @DisplayName("fully populated order — pins all detail fields")
        @SuppressWarnings("unchecked")
        void fullyPopulatedOrder_pinsAllDetailFields()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindById(order);

            List<OrderStatusHistoryEntity> history = buildStatusHistory(order);
            stubStatusHistoryFind(order.getId(), history);

            OrderDetailRespDto detail = orderService.getOrderDetail(order.getId());

            assertNotNull(detail);
            assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), detail.getId());
            assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), detail.getSessionId());
            assertEquals(OrderStatusEn.PAID, detail.getStatus());
            assertEquals(new BigDecimal("1250.00"), detail.getTotalAmount());
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30, 0), detail.getCreatedAt());

            // Shipping
            assertEquals("123 Main Street", detail.getShippingAddressLine1());
            assertNull(detail.getShippingAddressLine2()); // legacy field
            assertEquals("Cape Town", detail.getShippingCity());
            assertEquals("Western Cape", detail.getShippingProvince());
            assertEquals("8001", detail.getShippingPostalCode());
            assertNull(detail.getShippingPhone()); // legacy field

            // Customer detail
            assertNotNull(detail.getCustomerEntity());
            assertEquals("customer@example.com", detail.getCustomerEntity().getEmail());

            // Items
            assertNotNull(detail.getItems());
            assertEquals(2, detail.getItems().size());

            // First item detail
            OrderItemDetailDto itemDetail1 = detail.getItems().get(0);
            assertEquals("88888888-8888-8888-8888-888888888888", itemDetail1.getId());
            assertEquals(new BigDecimal("250.00"), itemDetail1.getUnitPrice());
            assertEquals(3, itemDetail1.getQuantity());
            assertNotNull(itemDetail1.getVariant());
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), itemDetail1.getVariant().getId());
            assertEquals(50, itemDetail1.getVariant().getStockQuantity());
            assertEquals("{\"color\":\"red\",\"size\":\"L\"}", itemDetail1.getVariant().getAttributesJson());
            assertEquals(new BigDecimal("0.75"), itemDetail1.getVariant().getWeightKg());
            assertNotNull(itemDetail1.getVariant().getProduct());
            assertEquals("Premium Widget", itemDetail1.getVariant().getProduct().getName());

            // Images on first item
            assertNotNull(itemDetail1.getVariant().getImages());
            assertEquals(2, itemDetail1.getVariant().getImages().size());
            assertEquals("55555555-5555-5555-5555-555555555555", itemDetail1.getVariant().getImages().get(0).getId());
            assertEquals("https://cdn.example.com/widget1.jpg", itemDetail1.getVariant().getImages().get(0).getImageUrl());
            assertEquals(1, itemDetail1.getVariant().getImages().get(0).getSortOrder());
            assertTrue(itemDetail1.getVariant().getImages().get(0).isFeatured());
            assertEquals("66666666-6666-6666-6666-666666666666", itemDetail1.getVariant().getImages().get(1).getId());
            assertEquals("https://cdn.example.com/widget2.jpg", itemDetail1.getVariant().getImages().get(1).getImageUrl());
            assertEquals(2, itemDetail1.getVariant().getImages().get(1).getSortOrder());
            assertFalse(itemDetail1.getVariant().getImages().get(1).isFeatured());

            // Second item — empty images
            OrderItemDetailDto itemDetail2 = detail.getItems().get(1);
            assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", itemDetail2.getId());
            assertEquals(new BigDecimal("125.00"), itemDetail2.getUnitPrice());
            assertEquals(2, itemDetail2.getQuantity());
            assertNotNull(itemDetail2.getVariant());
            assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), itemDetail2.getVariant().getId());
            assertNull(itemDetail2.getVariant().getAttributesJson());
            assertNotNull(itemDetail2.getVariant().getImages());
            assertTrue(itemDetail2.getVariant().getImages().isEmpty());

            // Status history
            assertNotNull(detail.getStatusHistory());
            assertEquals(2, detail.getStatusHistory().size());

            var hist1 = detail.getStatusHistory().get(0);
            assertEquals(UUID.fromString("aaa11111-1111-1111-1111-111111111111"), hist1.getId());
            assertEquals(OrderStatusEn.PAID, hist1.getStatus());
            assertEquals("Payment confirmed", hist1.getComment());
            assertEquals("SYSTEM", hist1.getChangedBy());
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 35, 0), hist1.getCreatedAt());

            var hist2 = detail.getStatusHistory().get(1);
            assertEquals(UUID.fromString("aaa22222-2222-2222-2222-222222222222"), hist2.getId());
            assertEquals(OrderStatusEn.CREATED, hist2.getStatus());
            assertEquals("Order created", hist2.getComment());
            assertNull(hist2.getChangedBy());
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30, 0), hist2.getCreatedAt());
        }

        @Test
        @DisplayName("null orderId — returns null")
        void nullOrderId_returnsNull()
        {
            OrderDetailRespDto detail = orderService.getOrderDetail(null);
            assertNull(detail);
        }

        @Test
        @DisplayName("minimal order (null customer, no items, no history) — pins null handling")
        @SuppressWarnings("unchecked")
        void minimalOrder_pinsNullHandling()
        {
            OrderEntity order = buildMinimalOrder();
            stubOrderRepositoryFindById(order);
            stubStatusHistoryFind(order.getId(), Collections.emptyList());

            OrderDetailRespDto detail = orderService.getOrderDetail(order.getId());

            assertNotNull(detail);
            assertEquals(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), detail.getId());
            assertEquals(OrderStatusEn.CREATED, detail.getStatus());
            assertEquals(new BigDecimal("0.00"), detail.getTotalAmount());
            assertNull(detail.getCustomerEntity());
            assertNull(detail.getShippingAddressLine1());
            assertNull(detail.getShippingCity());
            assertNull(detail.getShippingProvince());
            assertNull(detail.getShippingPostalCode());
            assertNotNull(detail.getItems());
            assertTrue(detail.getItems().isEmpty());
            assertNotNull(detail.getStatusHistory());
            assertTrue(detail.getStatusHistory().isEmpty());
        }

        @Test
        @DisplayName("order with null variant item — pins variant null handling in detail")
        @SuppressWarnings("unchecked")
        void orderWithNullVariant_pinsNullVariantInDetail()
        {
            OrderEntity order = buildOrderWithNullVariant();
            stubOrderRepositoryFindById(order);
            stubStatusHistoryFind(order.getId(), Collections.emptyList());

            OrderDetailRespDto detail = orderService.getOrderDetail(order.getId());

            assertNotNull(detail);
            assertEquals(1, detail.getItems().size());
            OrderItemDetailDto itemDetail = detail.getItems().get(0);
            assertEquals("12345678-1234-1234-1234-123456789abc", itemDetail.getId());
            assertEquals(new BigDecimal("50.00"), itemDetail.getUnitPrice());
            assertEquals(1, itemDetail.getQuantity());
            assertNull(itemDetail.getVariant());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getAllOrders — pins list DTO construction
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllOrders")
    class GetAllOrdersTests
    {

        @Test
        @DisplayName("multiple orders — pins each OrderResponseDto in list")
        void multipleOrders_pinsListMapping()
        {
            OrderEntity order1 = buildFullyPopulatedOrder();
            OrderEntity order2 = buildMinimalOrder();
            stubOrderRepositoryFindAll(List.of(order1, order2));

            PageRequest page = new PageRequest();
            page.setPageIndex(0);
            page.setPageSize(10);

            List<OrderResponseDto> result = orderService.getAllOrders(page, null);

            assertNotNull(result);
            assertEquals(2, result.size());

            // First order
            assertEquals("11111111-1111-1111-1111-111111111111", result.get(0).getId());
            assertEquals("PAID", result.get(0).getStatus());
            assertEquals(new BigDecimal("1250.00"), result.get(0).getTotalAmount());
            assertEquals(2, result.get(0).getItemCount());
            assertNotNull(result.get(0).getCustomer());
            assertEquals("customer@example.com", result.get(0).getCustomer().getEmail());

            // Second order — minimal
            assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", result.get(1).getId());
            assertEquals("CREATED", result.get(1).getStatus());
            assertEquals(new BigDecimal("0.00"), result.get(1).getTotalAmount());
            assertEquals(0, result.get(1).getItemCount());
            assertNull(result.get(1).getCustomer());
        }

        @Test
        @DisplayName("empty order list — returns empty")
        void emptyOrderList_returnsEmpty()
        {
            stubOrderRepositoryFindAll(Collections.emptyList());

            List<OrderResponseDto> result = orderService.getAllOrders(new PageRequest(), null);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMyOrders — pins OrderSummaryDto construction
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyOrders")
    class GetMyOrdersTests
    {

        @Test
        @DisplayName("fully populated order — pins summary DTO fields")
        @SuppressWarnings("unchecked")
        void fullyPopulatedOrder_pinsSummaryFields()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            UUID customerId = order.getCustomerEntity().getId();
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

            assertNotNull(result);
            assertEquals(1, result.size());
            OrderSummaryDto dto = result.get(0);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.getId());
            assertEquals("2026-07-15T10:30:00", dto.getOrderDate());
            assertEquals("PAID", dto.getStatus());
            assertEquals(5, dto.getItemCount()); // 3 + 2
            assertEquals(1250.00, dto.getTotalAmount(), 0.001);
        }

        @Test
        @DisplayName("order with null status — maps status as null")
        @SuppressWarnings("unchecked")
        void orderWithNullStatus_mapsStatusAsNull()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            order.setStatus(null);
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.getCustomerEntity().getId());

            assertEquals(1, result.size());
            assertNull(result.get(0).getStatus());
        }

        @Test
        @DisplayName("order with null totalAmount — maps to 0.0")
        @SuppressWarnings("unchecked")
        void orderWithNullTotalAmount_mapsToZero()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            order.setTotalAmount(null);
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.getCustomerEntity().getId());

            assertEquals(1, result.size());
            assertEquals(0.0, result.get(0).getTotalAmount(), 0.001);
        }

        @Test
        @DisplayName("order with null createdAt — maps orderDate as null")
        @SuppressWarnings("unchecked")
        void orderWithNullCreatedAt_mapsOrderDateAsNull()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            order.setCreatedAt(null);
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.getCustomerEntity().getId());

            assertEquals(1, result.size());
            assertNull(result.get(0).getOrderDate());
        }

        @Test
        @DisplayName("order with null items — maps itemCount as 0")
        @SuppressWarnings("unchecked")
        void orderWithNullItems_mapsItemCountAsZero()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            order.setItems(null);
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.getCustomerEntity().getId());

            assertEquals(1, result.size());
            assertEquals(0, result.get(0).getItemCount());
        }

        @Test
        @DisplayName("empty orders — returns empty list")
        @SuppressWarnings("unchecked")
        void emptyOrders_returnsEmptyList()
        {
            stubMyOrdersFind(Collections.emptyList());

            List<OrderSummaryDto> result = orderService.getMyOrders(UUID.randomUUID());

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getLatestOrderBySessionId — pins OrderResponseDto via session lookup
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getLatestOrderBySessionId")
    class GetLatestOrderBySessionIdTests
    {

        @Test
        @DisplayName("valid sessionId with order — pins DTO fields")
        void validSessionId_pinsDtoFields()
        {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindBySessionId(order.getSessionId(), order);

            OrderResponseDto dto = orderService.getLatestOrderBySessionId(
                    "22222222-2222-2222-2222-222222222222");

            assertNotNull(dto);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.getId());
            assertEquals("22222222-2222-2222-2222-222222222222", dto.getSessionId());
            assertEquals("PAID", dto.getStatus());
            assertEquals(new BigDecimal("1250.00"), dto.getTotalAmount());
            assertEquals(2, dto.getItemCount());
            assertNotNull(dto.getCustomer());
            assertEquals("customer@example.com", dto.getCustomer().getEmail());
        }

        @Test
        @DisplayName("invalid sessionId — returns null DTO (mapper handles null)")
        void invalidSessionId_returnsNull()
        {
            OrderResponseDto dto = orderService.getLatestOrderBySessionId("not-a-uuid");

            assertNull(dto);
        }

        @Test
        @DisplayName("no order for sessionId — returns null DTO")
        void noOrderForSession_returnsNull()
        {
            UUID sessionId = UUID.randomUUID();
            stubOrderRepositoryFindBySessionId(sessionId, null);

            OrderResponseDto dto = orderService.getLatestOrderBySessionId(sessionId.toString());

            assertNull(dto);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panache / Repository stubs
    // ══════════════════════════════════════════════════════════════════════════

    private void stubOrderRepositoryFindById(OrderEntity order)
    {
        when(orderRepository.findOrderInfoById(any(UUID.class))).thenReturn(order);
    }

    private void stubOrderRepositoryFindAll(List<OrderEntity> orders)
    {
        when(orderRepository.findAllOrderInfo(any(), any())).thenReturn(
                orders != null ? orders : Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void stubOrderRepositoryFindBySessionId(UUID sessionId, OrderEntity order)
    {
        when(orderRepository.findLatestOrderInfoBySessionId(any(UUID.class))).thenReturn(order);
    }

    @SuppressWarnings("unchecked")
    private void stubMyOrdersFind(List<OrderEntity> orders)
    {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(orders != null ? (List) orders : Collections.emptyList());
        when(OrderEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    @SuppressWarnings("unchecked")
    private void stubStatusHistoryFind(UUID orderId, List<OrderStatusHistoryEntity> histories)
    {
        PanacheQuery<PanacheEntityBase> histQuery = mock(PanacheQuery.class);
        when(histQuery.list()).thenReturn(histories != null ? (List) histories : Collections.emptyList());
        when(OrderStatusHistoryEntity.find(anyString(), any(Object[].class))).thenReturn(histQuery);
    }
}
