package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderDetailRespDto.OrderStatusHistoryDetailRespDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.ProductDetailDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductVariantDetailDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.lang.reflect.Field;
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
 * Characterization tests pinning the current response shapes produced by {@link OrderMapper}.
 *
 * Key shapes captured (post order-item DTO consolidation):
 * <ul>
 *   <li>{@link OrderItemDetailDto}: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}
 *       — the single canonical order-item output DTO used by BOTH OrderResponseDto and OrderDetailRespDto</li>
 *   <li>{@link OrderStatusHistoryDetailRespDto}: {id: UUID, status, comment, changedBy, createdAt}
 *       — pins that the leaked {@code order: OrderEntity} field has been removed</li>
 * </ul>
 *
 * Both toResponseDto and toDetailDto now produce OrderItemDetailDto with ProductVariantDetailDto
 * (reduced variant: no sku/status/prices; product: ProductDetailDto name-only; images: ProductImageDto).
 *
 * <p>Validates: Requirements 2.4, 4.2</p>
 */
@QuarkusTest
@DisplayName("OrderMapper DTO shape characterization")
class OrderDtoCharacterizationTest {

    @Inject
    OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(OrderStatusHistoryEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fixture builders
    // ══════════════════════════════════════════════════════════════════════════

    private OrderEntity buildOrder() {
        OrderEntity order = new OrderEntity();
        order.id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        order.sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        order.status = OrderStatusEn.PAID;
        order.totalAmount = new BigDecimal("500.00");
        order.createdAt = LocalDateTime.of(2026, 7, 20, 12, 0, 0);
        order.streetAddress = "10 Test Road";
        order.city = "Johannesburg";
        order.province = "Gauteng";
        order.postalCode = "2000";

        CustomerEntity customer = new CustomerEntity();
        customer.id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UserEntity user = new UserEntity();
        user.email = "test@example.com";
        customer.user = user;
        order.customerEntity = customer;

        // Build item with variant, product, images
        ProductEntity product = new ProductEntity();
        product.id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        product.name = "Test Product";

        ProductImageEntity img1 = new ProductImageEntity();
        img1.id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        img1.imageUrl = "/images/test1.jpg";
        img1.sortOrder = 1;
        img1.isFeatured = true;

        ProductImageEntity img2 = new ProductImageEntity();
        img2.id = UUID.fromString("66666666-6666-6666-6666-666666666666");
        img2.imageUrl = "/images/test2.jpg";
        img2.sortOrder = 2;
        img2.isFeatured = false;

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        variant.sku = "SKU-001";
        variant.stockQuantity = 25;
        variant.attributesJson = "{\"color\":\"blue\"}";
        variant.weightKg = new BigDecimal("1.50");
        variant.product = product;
        variant.images = List.of(img1, img2);

        OrderItemEntity item = new OrderItemEntity();
        item.id = UUID.fromString("88888888-8888-8888-8888-888888888888");
        item.orderEntity = order;
        item.variant = variant;
        item.quantity = 2;
        item.unitPrice = new BigDecimal("250.00");

        order.items = new ArrayList<>();
        order.items.add(item);

        return order;
    }

    private List<OrderStatusHistoryEntity> buildHistory(OrderEntity order) {
        OrderStatusHistoryEntity h1 = new OrderStatusHistoryEntity();
        h1.id = UUID.fromString("aaa11111-1111-1111-1111-111111111111");
        h1.order = order;
        h1.status = OrderStatusEn.PAID;
        h1.comment = "Payment received";
        h1.changedBy = "SYSTEM";
        h1.createdAt = LocalDateTime.of(2026, 7, 20, 12, 5, 0);

        OrderStatusHistoryEntity h2 = new OrderStatusHistoryEntity();
        h2.id = UUID.fromString("aaa22222-2222-2222-2222-222222222222");
        h2.order = order;
        h2.status = OrderStatusEn.PENDING;
        h2.comment = null;
        h2.changedBy = "admin-user-1";
        h2.createdAt = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

        return List.of(h1, h2);
    }

    @SuppressWarnings("unchecked")
    private void stubHistoryQuery(UUID orderId, List<OrderStatusHistoryEntity> histories) {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(histories != null ? (List) histories : Collections.emptyList());
        when(OrderStatusHistoryEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toResponseDto — pins OrderResponseDto with canonical OrderItemDetailDto
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toResponseDto — OrderResponseDto shape")
    class ToResponseDtoTests {

        @Test
        @DisplayName("pins OrderResponseDto field set: id(String), sessionId(String), status(String), createDate(String), totalAmount, itemCount, customer(CustomerDto), items(List<OrderItemDetailDto>)")
        void pinsOrderResponseDtoShape() {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            // Top-level OrderResponseDto shape
            assertNotNull(dto);
            assertInstanceOf(String.class, dto.id);
            assertInstanceOf(String.class, dto.sessionId);
            assertInstanceOf(String.class, dto.status);
            assertInstanceOf(String.class, dto.createDate);
            assertInstanceOf(BigDecimal.class, dto.totalAmount);
            assertInstanceOf(Integer.class, dto.itemCount);
            assertInstanceOf(CustomerDto.class, dto.customer);
            assertInstanceOf(List.class, dto.items);

            // Verify actual values
            assertEquals("11111111-1111-1111-1111-111111111111", dto.id);
            assertEquals("22222222-2222-2222-2222-222222222222", dto.sessionId);
            assertEquals("PAID", dto.status);
            assertEquals("2026-07-20T12:00", dto.createDate);
            assertEquals(0, new BigDecimal("500.00").compareTo(dto.totalAmount));
            assertEquals(1, dto.itemCount);
            assertEquals("test@example.com", dto.customer.getEmail());
        }

        @Test
        @DisplayName("pins canonical OrderItemDetailDto shape in response path: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}")
        void pinsOrderItemDetailDtoShapeInResponse() {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertEquals(1, dto.items.size());
            OrderItemDetailDto itemDto = dto.items.get(0);

            // Shape assertion: canonical DTO uses String id
            assertInstanceOf(String.class, itemDto.id);
            assertInstanceOf(BigDecimal.class, itemDto.unitPrice);
            assertInstanceOf(Integer.class, itemDto.quantity);
            assertInstanceOf(ProductVariantDetailDto.class, itemDto.variant);

            // Values
            assertEquals("88888888-8888-8888-8888-888888888888", itemDto.id);
            assertEquals(0, new BigDecimal("250.00").compareTo(itemDto.unitPrice));
            assertEquals(2, itemDto.quantity);
        }

        @Test
        @DisplayName("pins ProductVariantDetailDto nested in response path: {id: UUID, stockQuantity, attributesJson, weightKg, product: ProductDetailDto, images: List<ProductImageDto>}")
        void pinsProductVariantDetailDtoShapeInResponse() {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto variantDto = dto.items.get(0).variant;

            // Shape: ProductVariantDetailDto has id as UUID
            assertInstanceOf(UUID.class, variantDto.id);
            assertInstanceOf(Integer.class, variantDto.stockQuantity);
            assertInstanceOf(String.class, variantDto.attributesJson);
            assertInstanceOf(BigDecimal.class, variantDto.weightKg);
            assertInstanceOf(ProductDetailDto.class, variantDto.product);
            assertInstanceOf(List.class, variantDto.images);

            // Values
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), variantDto.id);
            assertEquals(25, variantDto.stockQuantity);
            assertEquals("{\"color\":\"blue\"}", variantDto.attributesJson);
            assertEquals(0, new BigDecimal("1.50").compareTo(variantDto.weightKg));

            // ProductDetailDto nested in variant (only has 'name')
            assertNotNull(variantDto.product);
            assertEquals("Test Product", variantDto.product.name);

            // ProductImageDto images
            assertNotNull(variantDto.images);
            assertEquals(2, variantDto.images.size());
            ProductImageDto img1 = variantDto.images.get(0);
            assertInstanceOf(String.class, img1.id);
            assertEquals("55555555-5555-5555-5555-555555555555", img1.id);
            assertEquals("/images/test1.jpg", img1.imageUrl);
            assertEquals(1, img1.sortOrder);
            assertTrue(img1.isFeatured);

            ProductImageDto img2 = variantDto.images.get(1);
            assertEquals("66666666-6666-6666-6666-666666666666", img2.id);
            assertEquals("/images/test2.jpg", img2.imageUrl);
            assertEquals(2, img2.sortOrder);
            assertFalse(img2.isFeatured);
        }

        @Test
        @DisplayName("response path variant does NOT carry sku/status/prices (reduced variant)")
        void responseVariantIsReduced() {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto variantDto = dto.items.get(0).variant;

            // ProductVariantDetailDto has no sku, status, or prices fields —
            // these are product-management concerns, not order-display concerns
            Field[] fields = ProductVariantDetailDto.class.getDeclaredFields();
            for (Field field : fields) {
                assertNotEquals("sku", field.getName());
                assertNotEquals("status", field.getName());
                assertNotEquals("prices", field.getName());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toDetailDto — pins OrderDetailRespDto with canonical OrderItemDetailDto
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toDetailDto — OrderDetailRespDto shape")
    class ToDetailDtoTests {

        @Test
        @DisplayName("pins OrderDetailRespDto field set: id(UUID), customerEntity(CustomerDto — canonical, post customer-merge), totalAmount, sessionId(UUID), status(OrderStatusEn), shipping fields, items(List<OrderItemDetailDto>), createdAt, statusHistory")
        @SuppressWarnings("unchecked")
        void pinsOrderDetailRespDtoShape() {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.id, buildHistory(order));

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto);
            assertInstanceOf(UUID.class, dto.id);
            assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), dto.id);

            assertInstanceOf(UUID.class, dto.sessionId);
            assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), dto.sessionId);

            assertInstanceOf(OrderStatusEn.class, dto.status);
            assertEquals(OrderStatusEn.PAID, dto.status);

            assertEquals(0, new BigDecimal("500.00").compareTo(dto.totalAmount));
            assertEquals(LocalDateTime.of(2026, 7, 20, 12, 0, 0), dto.createdAt);

            // Shipping
            assertEquals("10 Test Road", dto.shippingAddressLine1);
            assertNull(dto.shippingAddressLine2);
            assertEquals("Johannesburg", dto.shippingCity);
            assertEquals("Gauteng", dto.shippingProvince);
            assertEquals("2000", dto.shippingPostalCode);
            assertNull(dto.shippingPhone);

            // Customer — canonical CustomerDto (CustomerDetailDto merged away)
            assertInstanceOf(CustomerDto.class, dto.customerEntity);
            assertEquals("test@example.com", dto.customerEntity.getEmail());
        }

        @Test
        @DisplayName("pins OrderItemDetailDto shape in detail path: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}")
        @SuppressWarnings("unchecked")
        void pinsOrderItemDetailDtoShapeInDetail() {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.id, Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertEquals(1, dto.items.size());
            OrderItemDetailDto itemDto = dto.items.get(0);

            // Shape: canonical DTO uses String id
            assertInstanceOf(String.class, itemDto.id);
            assertInstanceOf(BigDecimal.class, itemDto.unitPrice);
            assertInstanceOf(Integer.class, itemDto.quantity);
            assertInstanceOf(ProductVariantDetailDto.class, itemDto.variant);

            // Values
            assertEquals("88888888-8888-8888-8888-888888888888", itemDto.id);
            assertEquals(0, new BigDecimal("250.00").compareTo(itemDto.unitPrice));
            assertEquals(2, itemDto.quantity);
        }

        @Test
        @DisplayName("pins ProductVariantDetailDto nested in detail path: {id: UUID, stockQuantity, attributesJson, weightKg, product: ProductDetailDto, images: List<ProductImageDto>}")
        @SuppressWarnings("unchecked")
        void pinsProductVariantDetailDtoShapeInDetail() {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.id, Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);
            ProductVariantDetailDto variantDto = dto.items.get(0).variant;

            // Shape: ProductVariantDetailDto has id as UUID
            assertInstanceOf(UUID.class, variantDto.id);
            assertInstanceOf(Integer.class, variantDto.stockQuantity);
            assertInstanceOf(String.class, variantDto.attributesJson);
            assertInstanceOf(BigDecimal.class, variantDto.weightKg);
            assertInstanceOf(ProductDetailDto.class, variantDto.product);
            assertInstanceOf(List.class, variantDto.images);

            // Values
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), variantDto.id);
            assertEquals(25, variantDto.stockQuantity);
            assertEquals("{\"color\":\"blue\"}", variantDto.attributesJson);
            assertEquals(0, new BigDecimal("1.50").compareTo(variantDto.weightKg));

            // ProductDetailDto nested in variant (only has 'name')
            assertNotNull(variantDto.product);
            assertEquals("Test Product", variantDto.product.name);

            // ProductImageDto images
            assertNotNull(variantDto.images);
            assertEquals(2, variantDto.images.size());
            ProductImageDto img1 = variantDto.images.get(0);
            assertInstanceOf(String.class, img1.id);
            assertEquals("55555555-5555-5555-5555-555555555555", img1.id);
            assertEquals("/images/test1.jpg", img1.imageUrl);
            assertEquals(1, img1.sortOrder);
            assertTrue(img1.isFeatured);

            ProductImageDto img2 = variantDto.images.get(1);
            assertEquals("66666666-6666-6666-6666-666666666666", img2.id);
            assertEquals("/images/test2.jpg", img2.imageUrl);
            assertEquals(2, img2.sortOrder);
            assertFalse(img2.isFeatured);
        }

        @Test
        @DisplayName("pins unification: both response and detail paths produce the same canonical OrderItemDetailDto type with ProductVariantDetailDto")
        @SuppressWarnings("unchecked")
        void pinsBothPathsProduceSameCanonicalDto() {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.id, Collections.emptyList());

            // Response path
            OrderResponseDto responseDto = orderMapper.toResponseDto(order);
            OrderItemDetailDto responseItem = responseDto.items.get(0);

            // Detail path
            OrderDetailRespDto detailDto = orderMapper.toDetailDto(order);
            OrderItemDetailDto detailItem = detailDto.items.get(0);

            // Both use the same DTO type
            assertInstanceOf(OrderItemDetailDto.class, responseItem);
            assertInstanceOf(OrderItemDetailDto.class, detailItem);

            // Both carry ProductVariantDetailDto
            assertInstanceOf(ProductVariantDetailDto.class, responseItem.variant);
            assertInstanceOf(ProductVariantDetailDto.class, detailItem.variant);

            // Both id fields are String (canonical reconciliation)
            assertInstanceOf(String.class, responseItem.id);
            assertInstanceOf(String.class, detailItem.id);

            // Same values
            assertEquals(responseItem.id, detailItem.id);
            assertEquals(responseItem.variant.id, detailItem.variant.id);
        }

        @Test
        @DisplayName("pins image DTO unification: both paths use ProductImageDto (String id, has isFeatured)")
        @SuppressWarnings("unchecked")
        void pinsImageDtoUnifiedBetweenResponseAndDetail() {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.id, Collections.emptyList());

            // Response path
            OrderResponseDto responseDto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto responseVariant = responseDto.items.get(0).variant;
            assertFalse(responseVariant.images.isEmpty());
            ProductImageDto responseImg = responseVariant.images.get(0);
            assertInstanceOf(String.class, responseImg.id);
            assertTrue(responseImg.isFeatured);

            // Detail path
            OrderDetailRespDto detailDto = orderMapper.toDetailDto(order);
            ProductVariantDetailDto detailVariant = detailDto.items.get(0).variant;
            assertFalse(detailVariant.images.isEmpty());
            ProductImageDto detailImg = detailVariant.images.get(0);
            assertInstanceOf(String.class, detailImg.id);
            assertTrue(detailImg.isFeatured);

            // Both produce the same image id value
            assertEquals(responseImg.id, detailImg.id);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OrderStatusHistoryDetailRespDto — pins current shape (order field already removed)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OrderStatusHistoryDetailRespDto shape")
    class StatusHistoryShapeTests {

        @Test
        @DisplayName("pins current shape: {id: UUID, status: OrderStatusEn, comment: String, changedBy: String, createdAt: LocalDateTime}")
        @SuppressWarnings("unchecked")
        void pinsStatusHistoryDtoShape() {
            OrderEntity order = buildOrder();
            List<OrderStatusHistoryEntity> history = buildHistory(order);
            stubHistoryQuery(order.id, history);

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto.statusHistory);
            assertEquals(2, dto.statusHistory.size());

            OrderStatusHistoryDetailRespDto h1 = dto.statusHistory.get(0);
            assertInstanceOf(UUID.class, h1.id);
            assertEquals(UUID.fromString("aaa11111-1111-1111-1111-111111111111"), h1.id);

            assertInstanceOf(OrderStatusEn.class, h1.status);
            assertEquals(OrderStatusEn.PAID, h1.status);

            assertEquals("Payment received", h1.comment);
            assertEquals("SYSTEM", h1.changedBy);
            assertEquals(LocalDateTime.of(2026, 7, 20, 12, 5, 0), h1.createdAt);
        }

        @Test
        @DisplayName("pins that the 'order' field does NOT exist on OrderStatusHistoryDetailRespDto (leaked entity already removed)")
        void pinsOrderFieldAlreadyRemoved() {
            Field[] fields = OrderStatusHistoryDetailRespDto.class.getDeclaredFields();
            for (Field field : fields) {
                assertNotEquals("order", field.getName(),
                        "OrderStatusHistoryDetailRespDto should not have a leaked 'order' field");
            }
        }

        @Test
        @DisplayName("pins that comment and changedBy fields are preserved (not null when populated)")
        @SuppressWarnings("unchecked")
        void pinsCommentAndChangedByPreserved() {
            OrderEntity order = buildOrder();
            List<OrderStatusHistoryEntity> history = buildHistory(order);
            stubHistoryQuery(order.id, history);

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            OrderStatusHistoryDetailRespDto h1 = dto.statusHistory.get(0);
            assertNotNull(h1.comment);
            assertNotNull(h1.changedBy);

            OrderStatusHistoryDetailRespDto h2 = dto.statusHistory.get(1);
            assertNull(h2.comment);
            assertEquals("admin-user-1", h2.changedBy);
        }

        @Test
        @DisplayName("pins the complete field set of OrderStatusHistoryDetailRespDto: exactly 5 fields")
        void pinsFieldSetSize() {
            Field[] fields = OrderStatusHistoryDetailRespDto.class.getDeclaredFields();
            assertEquals(5, fields.length,
                    "OrderStatusHistoryDetailRespDto should have exactly 5 fields: id, status, comment, changedBy, createdAt");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Null / edge case handling
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null handling")
    class NullHandlingTests {

        @Test
        @DisplayName("toResponseDto(null) returns null")
        void toResponseDtoNullReturnsNull() {
            assertNull(orderMapper.toResponseDto(null));
        }

        @Test
        @DisplayName("toDetailDto(null) returns null")
        void toDetailDtoNullReturnsNull() {
            assertNull(orderMapper.toDetailDto(null));
        }

        @Test
        @DisplayName("order with null items list produces empty items")
        void orderWithNullItemsProducesEmptyResponse() {
            OrderEntity order = buildOrder();
            order.items = null;

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertNotNull(dto);
            assertEquals(0, dto.itemCount);
        }

        @Test
        @DisplayName("item with null variant maps to null variant in response")
        void itemWithNullVariantMapsToNullInResponse() {
            OrderEntity order = buildOrder();
            order.items.get(0).variant = null;

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertNotNull(dto.items.get(0));
            assertNull(dto.items.get(0).variant);
        }

        @Test
        @DisplayName("item with null variant maps to null variant in detail")
        @SuppressWarnings("unchecked")
        void itemWithNullVariantMapsToNullInDetail() {
            OrderEntity order = buildOrder();
            order.items.get(0).variant = null;
            stubHistoryQuery(order.id, Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto.items.get(0));
            assertNull(dto.items.get(0).variant);
        }
    }
}
