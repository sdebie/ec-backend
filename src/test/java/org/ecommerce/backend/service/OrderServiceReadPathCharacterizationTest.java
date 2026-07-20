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
 *
 * Pins the current output of:
 * - getOrderById(UUID)
 * - getOrderDetail(UUID)
 * - getAllOrders(PageRequest, FilterRequest)
 * - getMyOrders(UUID)
 * - getLatestOrderBySessionId(String)
 *
 * These baselines guard against regressions when extracting DTO mapping
 * into OrderMapper (Task 10.2).
 *
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class OrderServiceReadPathCharacterizationTest {

    @Inject
    OrderService orderService;

    @InjectMock
    OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(OrderEntity.class);
        PanacheMock.mock(OrderStatusHistoryEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared fixture builders
    // ══════════════════════════════════════════════════════════════════════════

    private OrderEntity buildFullyPopulatedOrder() {
        OrderEntity order = new OrderEntity();
        order.id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        order.sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        order.status = OrderStatusEn.PAID;
        order.totalAmount = new BigDecimal("1250.00");
        order.createdAt = LocalDateTime.of(2026, 7, 15, 10, 30, 0);
        order.streetAddress = "123 Main Street";
        order.city = "Cape Town";
        order.province = "Western Cape";
        order.postalCode = "8001";

        // Customer
        CustomerEntity customer = new CustomerEntity();
        customer.id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UserEntity user = new UserEntity();
        user.email = "customer@example.com";
        customer.user = user;
        order.customerEntity = customer;

        // Items with variant, product, images
        order.items = new ArrayList<>();

        ProductEntity product1 = new ProductEntity();
        product1.id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        product1.name = "Premium Widget";

        ProductImageEntity image1 = new ProductImageEntity();
        image1.id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        image1.imageUrl = "https://cdn.example.com/widget1.jpg";
        image1.sortOrder = 1;
        image1.isFeatured = true;

        ProductImageEntity image2 = new ProductImageEntity();
        image2.id = UUID.fromString("66666666-6666-6666-6666-666666666666");
        image2.imageUrl = "https://cdn.example.com/widget2.jpg";
        image2.sortOrder = 2;
        image2.isFeatured = false;

        ProductVariantEntity variant1 = new ProductVariantEntity();
        variant1.id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        variant1.stockQuantity = 50;
        variant1.attributesJson = "{\"color\":\"red\",\"size\":\"L\"}";
        variant1.weightKg = new BigDecimal("0.75");
        variant1.product = product1;
        variant1.images = List.of(image1, image2);

        OrderItemEntity item1 = new OrderItemEntity();
        item1.id = UUID.fromString("88888888-8888-8888-8888-888888888888");
        item1.orderEntity = order;
        item1.variant = variant1;
        item1.quantity = 3;
        item1.unitPrice = new BigDecimal("250.00");
        order.items.add(item1);

        // Second item — different product, no images
        ProductEntity product2 = new ProductEntity();
        product2.id = UUID.fromString("99999999-9999-9999-9999-999999999999");
        product2.name = "Basic Gadget";

        ProductVariantEntity variant2 = new ProductVariantEntity();
        variant2.id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        variant2.stockQuantity = 100;
        variant2.attributesJson = null;
        variant2.weightKg = new BigDecimal("1.20");
        variant2.product = product2;
        variant2.images = Collections.emptyList();

        OrderItemEntity item2 = new OrderItemEntity();
        item2.id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        item2.orderEntity = order;
        item2.variant = variant2;
        item2.quantity = 2;
        item2.unitPrice = new BigDecimal("125.00");
        order.items.add(item2);

        return order;
    }

    private OrderEntity buildMinimalOrder() {
        OrderEntity order = new OrderEntity();
        order.id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        order.sessionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        order.status = OrderStatusEn.CREATED;
        order.totalAmount = new BigDecimal("0.00");
        order.createdAt = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        order.streetAddress = null;
        order.city = null;
        order.province = null;
        order.postalCode = null;
        order.customerEntity = null;
        order.items = new ArrayList<>();
        return order;
    }

    private OrderEntity buildOrderWithNullVariant() {
        OrderEntity order = new OrderEntity();
        order.id = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        order.sessionId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        order.status = OrderStatusEn.PENDING;
        order.totalAmount = new BigDecimal("50.00");
        order.createdAt = LocalDateTime.of(2026, 3, 10, 14, 0, 0);
        order.customerEntity = null;

        OrderItemEntity item = new OrderItemEntity();
        item.id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        item.orderEntity = order;
        item.variant = null;
        item.quantity = 1;
        item.unitPrice = new BigDecimal("50.00");

        order.items = new ArrayList<>();
        order.items.add(item);
        return order;
    }

    private List<OrderStatusHistoryEntity> buildStatusHistory(OrderEntity order) {
        OrderStatusHistoryEntity h1 = new OrderStatusHistoryEntity();
        h1.id = UUID.fromString("aaa11111-1111-1111-1111-111111111111");
        h1.order = order;
        h1.status = OrderStatusEn.PAID;
        h1.comment = "Payment confirmed";
        h1.changedBy = "SYSTEM";
        h1.createdAt = LocalDateTime.of(2026, 7, 15, 10, 35, 0);

        OrderStatusHistoryEntity h2 = new OrderStatusHistoryEntity();
        h2.id = UUID.fromString("aaa22222-2222-2222-2222-222222222222");
        h2.order = order;
        h2.status = OrderStatusEn.CREATED;
        h2.comment = "Order created";
        h2.changedBy = null;
        h2.createdAt = LocalDateTime.of(2026, 7, 15, 10, 30, 0);

        return List.of(h1, h2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getOrderById — pins OrderResponseDto construction via OrderMapper
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getOrderById")
    class GetOrderByIdTests {

        @Test
        @DisplayName("fully populated order — pins all OrderResponseDto fields")
        void fullyPopulatedOrder_pinsAllFields() {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.id);

            assertNotNull(dto);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.id);
            assertEquals("22222222-2222-2222-2222-222222222222", dto.sessionId);
            assertEquals("PAID", dto.status);
            assertEquals(order.createdAt.toString(), dto.createDate);
            assertEquals(new BigDecimal("1250.00"), dto.totalAmount);
            assertEquals(2, dto.itemCount);

            // Customer
            assertNotNull(dto.customer);
            assertEquals("customer@example.com", dto.customer.getEmail());

            // Items
            assertNotNull(dto.items);
            assertEquals(2, dto.items.size());

            // First item
            var item1Dto = dto.items.get(0);
            assertEquals("88888888-8888-8888-8888-888888888888", item1Dto.id);
            assertEquals(new BigDecimal("250.00"), item1Dto.unitPrice);
            assertEquals(3, item1Dto.quantity);
            assertNotNull(item1Dto.variant);
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), item1Dto.variant.id);
            assertEquals(50, item1Dto.variant.stockQuantity);
            assertEquals("{\"color\":\"red\",\"size\":\"L\"}", item1Dto.variant.attributesJson);
            assertEquals(new BigDecimal("0.75"), item1Dto.variant.weightKg);
            assertNotNull(item1Dto.variant.product);
            assertEquals("Premium Widget", item1Dto.variant.product.name);

            // Images on first item's variant
            assertNotNull(item1Dto.variant.images);
            assertEquals(2, item1Dto.variant.images.size());
            assertEquals("55555555-5555-5555-5555-555555555555", item1Dto.variant.images.get(0).id);
            assertEquals("https://cdn.example.com/widget1.jpg", item1Dto.variant.images.get(0).imageUrl);
            assertEquals(1, item1Dto.variant.images.get(0).sortOrder);
            assertTrue(item1Dto.variant.images.get(0).isFeatured);
            assertEquals("66666666-6666-6666-6666-666666666666", item1Dto.variant.images.get(1).id);
            assertEquals("https://cdn.example.com/widget2.jpg", item1Dto.variant.images.get(1).imageUrl);
            assertEquals(2, item1Dto.variant.images.get(1).sortOrder);
            assertFalse(item1Dto.variant.images.get(1).isFeatured);

            // Second item — no images
            var item2Dto = dto.items.get(1);
            assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", item2Dto.id);
            assertEquals(new BigDecimal("125.00"), item2Dto.unitPrice);
            assertEquals(2, item2Dto.quantity);
            assertNotNull(item2Dto.variant);
            assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), item2Dto.variant.id);
            assertEquals(100, item2Dto.variant.stockQuantity);
            assertNull(item2Dto.variant.attributesJson);
            assertEquals(new BigDecimal("1.20"), item2Dto.variant.weightKg);
            assertNotNull(item2Dto.variant.product);
            assertEquals("Basic Gadget", item2Dto.variant.product.name);
            assertNotNull(item2Dto.variant.images);
            assertTrue(item2Dto.variant.images.isEmpty());
        }

        @Test
        @DisplayName("null orderId — returns null")
        void nullOrderId_returnsNull() {
            OrderResponseDto dto = orderService.getOrderById(null);
            assertNull(dto);
        }

        @Test
        @DisplayName("minimal order (no customer, no items) — pins null handling")
        void minimalOrder_pinsNullHandling() {
            OrderEntity order = buildMinimalOrder();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.id);

            assertNotNull(dto);
            assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", dto.id);
            assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", dto.sessionId);
            assertEquals("CREATED", dto.status);
            assertEquals(order.createdAt.toString(), dto.createDate);
            assertEquals(new BigDecimal("0.00"), dto.totalAmount);
            assertNull(dto.customer);
            assertNotNull(dto.items);
            assertTrue(dto.items.isEmpty());
            assertEquals(0, dto.itemCount);
        }

        @Test
        @DisplayName("order with null variant item — pins null variant handling")
        void orderWithNullVariant_pinsNullVariantHandling() {
            OrderEntity order = buildOrderWithNullVariant();
            stubOrderRepositoryFindById(order);

            OrderResponseDto dto = orderService.getOrderById(order.id);

            assertNotNull(dto);
            assertEquals(1, dto.items.size());
            var itemDto = dto.items.get(0);
            assertEquals("12345678-1234-1234-1234-123456789abc", itemDto.id);
            assertEquals(new BigDecimal("50.00"), itemDto.unitPrice);
            assertEquals(1, itemDto.quantity);
            assertNull(itemDto.variant);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getOrderDetail — pins OrderDetailRespDto (inline mapping in service)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getOrderDetail")
    class GetOrderDetailTests {

        @Test
        @DisplayName("fully populated order — pins all detail fields")
        @SuppressWarnings("unchecked")
        void fullyPopulatedOrder_pinsAllDetailFields() {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindById(order);

            List<OrderStatusHistoryEntity> history = buildStatusHistory(order);
            stubStatusHistoryFind(order.id, history);

            OrderDetailRespDto detail = orderService.getOrderDetail(order.id);

            assertNotNull(detail);
            assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), detail.id);
            assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), detail.sessionId);
            assertEquals(OrderStatusEn.PAID, detail.status);
            assertEquals(new BigDecimal("1250.00"), detail.totalAmount);
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30, 0), detail.createdAt);

            // Shipping
            assertEquals("123 Main Street", detail.shippingAddressLine1);
            assertNull(detail.shippingAddressLine2); // legacy field
            assertEquals("Cape Town", detail.shippingCity);
            assertEquals("Western Cape", detail.shippingProvince);
            assertEquals("8001", detail.shippingPostalCode);
            assertNull(detail.shippingPhone); // legacy field

            // Customer detail
            assertNotNull(detail.customerEntity);
            assertEquals("customer@example.com", detail.customerEntity.getEmail());

            // Items
            assertNotNull(detail.items);
            assertEquals(2, detail.items.size());

            // First item detail
            OrderItemDetailDto itemDetail1 = detail.items.get(0);
            assertEquals("88888888-8888-8888-8888-888888888888", itemDetail1.id);
            assertEquals(new BigDecimal("250.00"), itemDetail1.unitPrice);
            assertEquals(3, itemDetail1.quantity);
            assertNotNull(itemDetail1.variant);
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), itemDetail1.variant.id);
            assertEquals(50, itemDetail1.variant.stockQuantity);
            assertEquals("{\"color\":\"red\",\"size\":\"L\"}", itemDetail1.variant.attributesJson);
            assertEquals(new BigDecimal("0.75"), itemDetail1.variant.weightKg);
            assertNotNull(itemDetail1.variant.product);
            assertEquals("Premium Widget", itemDetail1.variant.product.name);

            // Images on first item
            assertNotNull(itemDetail1.variant.images);
            assertEquals(2, itemDetail1.variant.images.size());
            assertEquals("55555555-5555-5555-5555-555555555555", itemDetail1.variant.images.get(0).id);
            assertEquals("https://cdn.example.com/widget1.jpg", itemDetail1.variant.images.get(0).imageUrl);
            assertEquals(1, itemDetail1.variant.images.get(0).sortOrder);
            assertTrue(itemDetail1.variant.images.get(0).isFeatured);
            assertEquals("66666666-6666-6666-6666-666666666666", itemDetail1.variant.images.get(1).id);
            assertEquals("https://cdn.example.com/widget2.jpg", itemDetail1.variant.images.get(1).imageUrl);
            assertEquals(2, itemDetail1.variant.images.get(1).sortOrder);
            assertFalse(itemDetail1.variant.images.get(1).isFeatured);

            // Second item — empty images
            OrderItemDetailDto itemDetail2 = detail.items.get(1);
            assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", itemDetail2.id);
            assertEquals(new BigDecimal("125.00"), itemDetail2.unitPrice);
            assertEquals(2, itemDetail2.quantity);
            assertNotNull(itemDetail2.variant);
            assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), itemDetail2.variant.id);
            assertNull(itemDetail2.variant.attributesJson);
            assertNotNull(itemDetail2.variant.images);
            assertTrue(itemDetail2.variant.images.isEmpty());

            // Status history
            assertNotNull(detail.statusHistory);
            assertEquals(2, detail.statusHistory.size());

            var hist1 = detail.statusHistory.get(0);
            assertEquals(UUID.fromString("aaa11111-1111-1111-1111-111111111111"), hist1.id);
            assertEquals(OrderStatusEn.PAID, hist1.status);
            assertEquals("Payment confirmed", hist1.comment);
            assertEquals("SYSTEM", hist1.changedBy);
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 35, 0), hist1.createdAt);

            var hist2 = detail.statusHistory.get(1);
            assertEquals(UUID.fromString("aaa22222-2222-2222-2222-222222222222"), hist2.id);
            assertEquals(OrderStatusEn.CREATED, hist2.status);
            assertEquals("Order created", hist2.comment);
            assertNull(hist2.changedBy);
            assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30, 0), hist2.createdAt);
        }

        @Test
        @DisplayName("null orderId — returns null")
        void nullOrderId_returnsNull() {
            OrderDetailRespDto detail = orderService.getOrderDetail(null);
            assertNull(detail);
        }

        @Test
        @DisplayName("minimal order (null customer, no items, no history) — pins null handling")
        @SuppressWarnings("unchecked")
        void minimalOrder_pinsNullHandling() {
            OrderEntity order = buildMinimalOrder();
            stubOrderRepositoryFindById(order);
            stubStatusHistoryFind(order.id, Collections.emptyList());

            OrderDetailRespDto detail = orderService.getOrderDetail(order.id);

            assertNotNull(detail);
            assertEquals(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), detail.id);
            assertEquals(OrderStatusEn.CREATED, detail.status);
            assertEquals(new BigDecimal("0.00"), detail.totalAmount);
            assertNull(detail.customerEntity);
            assertNull(detail.shippingAddressLine1);
            assertNull(detail.shippingCity);
            assertNull(detail.shippingProvince);
            assertNull(detail.shippingPostalCode);
            assertNotNull(detail.items);
            assertTrue(detail.items.isEmpty());
            assertNotNull(detail.statusHistory);
            assertTrue(detail.statusHistory.isEmpty());
        }

        @Test
        @DisplayName("order with null variant item — pins variant null handling in detail")
        @SuppressWarnings("unchecked")
        void orderWithNullVariant_pinsNullVariantInDetail() {
            OrderEntity order = buildOrderWithNullVariant();
            stubOrderRepositoryFindById(order);
            stubStatusHistoryFind(order.id, Collections.emptyList());

            OrderDetailRespDto detail = orderService.getOrderDetail(order.id);

            assertNotNull(detail);
            assertEquals(1, detail.items.size());
            OrderItemDetailDto itemDetail = detail.items.get(0);
            assertEquals("12345678-1234-1234-1234-123456789abc", itemDetail.id);
            assertEquals(new BigDecimal("50.00"), itemDetail.unitPrice);
            assertEquals(1, itemDetail.quantity);
            assertNull(itemDetail.variant);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getAllOrders — pins list DTO construction
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllOrders")
    class GetAllOrdersTests {

        @Test
        @DisplayName("multiple orders — pins each OrderResponseDto in list")
        void multipleOrders_pinsListMapping() {
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
            assertEquals("11111111-1111-1111-1111-111111111111", result.get(0).id);
            assertEquals("PAID", result.get(0).status);
            assertEquals(new BigDecimal("1250.00"), result.get(0).totalAmount);
            assertEquals(2, result.get(0).itemCount);
            assertNotNull(result.get(0).customer);
            assertEquals("customer@example.com", result.get(0).customer.getEmail());

            // Second order — minimal
            assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", result.get(1).id);
            assertEquals("CREATED", result.get(1).status);
            assertEquals(new BigDecimal("0.00"), result.get(1).totalAmount);
            assertEquals(0, result.get(1).itemCount);
            assertNull(result.get(1).customer);
        }

        @Test
        @DisplayName("empty order list — returns empty")
        void emptyOrderList_returnsEmpty() {
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
    class GetMyOrdersTests {

        @Test
        @DisplayName("fully populated order — pins summary DTO fields")
        @SuppressWarnings("unchecked")
        void fullyPopulatedOrder_pinsSummaryFields() {
            OrderEntity order = buildFullyPopulatedOrder();
            UUID customerId = order.customerEntity.id;
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(customerId);

            assertNotNull(result);
            assertEquals(1, result.size());
            OrderSummaryDto dto = result.get(0);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.id);
            assertEquals("2026-07-15T10:30:00", dto.orderDate);
            assertEquals("PAID", dto.status);
            assertEquals(5, dto.itemCount); // 3 + 2
            assertEquals(1250.00, dto.totalAmount, 0.001);
        }

        @Test
        @DisplayName("order with null status — maps status as null")
        @SuppressWarnings("unchecked")
        void orderWithNullStatus_mapsStatusAsNull() {
            OrderEntity order = buildFullyPopulatedOrder();
            order.status = null;
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.customerEntity.id);

            assertEquals(1, result.size());
            assertNull(result.get(0).status);
        }

        @Test
        @DisplayName("order with null totalAmount — maps to 0.0")
        @SuppressWarnings("unchecked")
        void orderWithNullTotalAmount_mapsToZero() {
            OrderEntity order = buildFullyPopulatedOrder();
            order.totalAmount = null;
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.customerEntity.id);

            assertEquals(1, result.size());
            assertEquals(0.0, result.get(0).totalAmount, 0.001);
        }

        @Test
        @DisplayName("order with null createdAt — maps orderDate as null")
        @SuppressWarnings("unchecked")
        void orderWithNullCreatedAt_mapsOrderDateAsNull() {
            OrderEntity order = buildFullyPopulatedOrder();
            order.createdAt = null;
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.customerEntity.id);

            assertEquals(1, result.size());
            assertNull(result.get(0).orderDate);
        }

        @Test
        @DisplayName("order with null items — maps itemCount as 0")
        @SuppressWarnings("unchecked")
        void orderWithNullItems_mapsItemCountAsZero() {
            OrderEntity order = buildFullyPopulatedOrder();
            order.items = null;
            stubMyOrdersFind(List.of(order));

            List<OrderSummaryDto> result = orderService.getMyOrders(order.customerEntity.id);

            assertEquals(1, result.size());
            assertEquals(0, result.get(0).itemCount);
        }

        @Test
        @DisplayName("empty orders — returns empty list")
        @SuppressWarnings("unchecked")
        void emptyOrders_returnsEmptyList() {
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
    class GetLatestOrderBySessionIdTests {

        @Test
        @DisplayName("valid sessionId with order — pins DTO fields")
        void validSessionId_pinsDtoFields() {
            OrderEntity order = buildFullyPopulatedOrder();
            stubOrderRepositoryFindBySessionId(order.sessionId, order);

            OrderResponseDto dto = orderService.getLatestOrderBySessionId(
                    "22222222-2222-2222-2222-222222222222");

            assertNotNull(dto);
            assertEquals("11111111-1111-1111-1111-111111111111", dto.id);
            assertEquals("22222222-2222-2222-2222-222222222222", dto.sessionId);
            assertEquals("PAID", dto.status);
            assertEquals(new BigDecimal("1250.00"), dto.totalAmount);
            assertEquals(2, dto.itemCount);
            assertNotNull(dto.customer);
            assertEquals("customer@example.com", dto.customer.getEmail());
        }

        @Test
        @DisplayName("invalid sessionId — returns null DTO (mapper handles null)")
        void invalidSessionId_returnsNull() {
            OrderResponseDto dto = orderService.getLatestOrderBySessionId("not-a-uuid");

            assertNull(dto);
        }

        @Test
        @DisplayName("no order for sessionId — returns null DTO")
        void noOrderForSession_returnsNull() {
            UUID sessionId = UUID.randomUUID();
            stubOrderRepositoryFindBySessionId(sessionId, null);

            OrderResponseDto dto = orderService.getLatestOrderBySessionId(sessionId.toString());

            assertNull(dto);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panache / Repository stubs
    // ══════════════════════════════════════════════════════════════════════════

    private void stubOrderRepositoryFindById(OrderEntity order) {
        when(orderRepository.findOrderInfoById(any(UUID.class))).thenReturn(order);
    }

    private void stubOrderRepositoryFindAll(List<OrderEntity> orders) {
        when(orderRepository.findAllOrderInfo(any(), any())).thenReturn(
                orders != null ? orders : Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void stubOrderRepositoryFindBySessionId(UUID sessionId, OrderEntity order) {
        when(orderRepository.findLatestOrderInfoBySessionId(any(UUID.class))).thenReturn(order);
    }

    @SuppressWarnings("unchecked")
    private void stubMyOrdersFind(List<OrderEntity> orders) {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(orders != null ? (List) orders : Collections.emptyList());
        when(OrderEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    @SuppressWarnings("unchecked")
    private void stubStatusHistoryFind(UUID orderId, List<OrderStatusHistoryEntity> histories) {
        PanacheQuery<PanacheEntityBase> histQuery = mock(PanacheQuery.class);
        when(histQuery.list()).thenReturn(histories != null ? (List) histories : Collections.emptyList());
        when(OrderStatusHistoryEntity.find(anyString(), any(Object[].class))).thenReturn(histQuery);
    }
}
