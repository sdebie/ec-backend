package org.ecommerce.backend.mapper;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.dto.OrderDetailRespDto.OrderStatusHistoryDetailRespDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * <p>
 * Key shapes captured (post order-item DTO consolidation):
 * <ul>
 *   <li>{@link OrderItemDetailDto}: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}
 *       — the single canonical order-item output DTO used by BOTH OrderResponseDto and OrderDetailRespDto</li>
 *   <li>{@link OrderStatusHistoryDetailRespDto}: {id: UUID, status, comment, changedBy, createdAt}
 *       — pins that the leaked {@code order: OrderEntity} field has been removed</li>
 * </ul>
 * <p>
 * Both toResponseDto and toDetailDto now produce OrderItemDetailDto with ProductVariantDetailDto
 * (reduced variant: no sku/status/prices; product: ProductDetailDto name-only; images: ProductImageDto).
 *
 */
@QuarkusTest
@DisplayName("OrderMapper DTO shape characterization")
class OrderDtoCharacterizationTest
{
    @Inject
    OrderMapper orderMapper;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderStatusHistoryEntity.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fixture builders
    // ══════════════════════════════════════════════════════════════════════════

    private OrderEntity buildOrder()
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        order.setSessionId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        order.setStatus(OrderStatusEn.PAID);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 7, 20, 12, 0, 0));
        order.setStreetAddress("10 Test Road");
        order.setCity("Johannesburg");
        order.setProvince("Gauteng");
        order.setPostalCode("2000");

        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        UserEntity user = new UserEntity();
        user.setEmail("test@example.com");
        customer.setUser(user);
        order.setCustomerEntity(customer);

        // Build item with variant, product, images
        ProductEntity product = new ProductEntity();
        product.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        product.setName("Test Product");

        ProductImageEntity img1 = new ProductImageEntity();
        img1.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        img1.setImageUrl("/images/test1.jpg");
        img1.setSortOrder(1);
        img1.setIsFeatured(true);

        ProductImageEntity img2 = new ProductImageEntity();
        img2.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        img2.setImageUrl("/images/test2.jpg");
        img2.setSortOrder(2);
        img2.setIsFeatured(false);

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        variant.setSku("SKU-001");
        variant.setStockQuantity(25);
        variant.setAttributesJson("{\"color\":\"blue\"}");
        variant.setWeightKg(new BigDecimal("1.50"));
        variant.setProduct(product);
        variant.setImages(List.of(img1, img2));

        OrderItemEntity item = new OrderItemEntity();
        item.setId(UUID.fromString("88888888-8888-8888-8888-888888888888"));
        item.setOrderEntity(order);
        item.setVariant(variant);
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("250.00"));

        order.setItems(new ArrayList<>());
        order.getItems().add(item);

        return order;
    }

    private List<OrderStatusHistoryEntity> buildHistory(OrderEntity order)
    {
        OrderStatusHistoryEntity h1 = new OrderStatusHistoryEntity();
        h1.setId(UUID.fromString("aaa11111-1111-1111-1111-111111111111"));
        h1.setOrder(order);
        h1.setStatus(OrderStatusEn.PAID);
        h1.setComment("Payment received");
        h1.setChangedBy("SYSTEM");
        h1.setCreatedAt(LocalDateTime.of(2026, 7, 20, 12, 5, 0));

        OrderStatusHistoryEntity h2 = new OrderStatusHistoryEntity();
        h2.setId(UUID.fromString("aaa22222-2222-2222-2222-222222222222"));
        h2.setOrder(order);
        h2.setStatus(OrderStatusEn.PENDING);
        h2.setComment(null);
        h2.setChangedBy("admin-user-1");
        h2.setCreatedAt(LocalDateTime.of(2026, 7, 20, 12, 0, 0));

        return List.of(h1, h2);
    }

    @SuppressWarnings("unchecked")
    private void stubHistoryQuery(UUID orderId, List<OrderStatusHistoryEntity> histories)
    {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(histories != null ? (List) histories : Collections.emptyList());
        when(OrderStatusHistoryEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toResponseDto — pins OrderResponseDto with canonical OrderItemDetailDto
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toResponseDto — OrderResponseDto shape")
    class ToResponseDtoTests
    {

        @Test
        @DisplayName("pins OrderResponseDto field set: id(String), sessionId(String), status(String), createDate(String), totalAmount, itemCount, customer(CustomerDto), items(List<OrderItemDetailDto>)")
        void pinsOrderResponseDtoShape()
        {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            // Top-level OrderResponseDto shape
            assertNotNull(dto);
            assertInstanceOf(String.class, dto.getId());
            assertInstanceOf(String.class, dto.getSessionId());
            assertInstanceOf(String.class, dto.getStatus());
            assertInstanceOf(String.class, dto.getCreateDate());
            assertInstanceOf(BigDecimal.class, dto.getTotalAmount());
            assertInstanceOf(Integer.class, dto.getItemCount());
            assertInstanceOf(CustomerDto.class, dto.getCustomer());
            assertInstanceOf(List.class, dto.getItems());

            // Verify actual values
            assertEquals("11111111-1111-1111-1111-111111111111", dto.getId());
            assertEquals("22222222-2222-2222-2222-222222222222", dto.getSessionId());
            assertEquals("PAID", dto.getStatus());
            assertEquals("2026-07-20T12:00", dto.getCreateDate());
            assertEquals(0, new BigDecimal("500.00").compareTo(dto.getTotalAmount()));
            assertEquals(1, dto.getItemCount());
            assertEquals("test@example.com", dto.getCustomer().getEmail());
        }

        @Test
        @DisplayName("pins canonical OrderItemDetailDto shape in response path: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}")
        void pinsOrderItemDetailDtoShapeInResponse()
        {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertEquals(1, dto.getItems().size());
            OrderItemDetailDto itemDto = dto.getItems().get(0);

            // Shape assertion: canonical DTO uses String id
            assertInstanceOf(String.class, itemDto.getId());
            assertInstanceOf(BigDecimal.class, itemDto.getUnitPrice());
            assertInstanceOf(Integer.class, itemDto.getQuantity());
            assertInstanceOf(ProductVariantDetailDto.class, itemDto.getVariant());

            // Values
            assertEquals("88888888-8888-8888-8888-888888888888", itemDto.getId());
            assertEquals(0, new BigDecimal("250.00").compareTo(itemDto.getUnitPrice()));
            assertEquals(2, itemDto.getQuantity());
        }

        @Test
        @DisplayName("pins ProductVariantDetailDto nested in response path: {id: UUID, stockQuantity, attributesJson, weightKg, product: ProductDetailDto, images: List<ProductImageDto>}")
        void pinsProductVariantDetailDtoShapeInResponse()
        {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto variantDto = dto.getItems().get(0).getVariant();

            // Shape: ProductVariantDetailDto has id as UUID
            assertInstanceOf(UUID.class, variantDto.getId());
            assertInstanceOf(Integer.class, variantDto.getStockQuantity());
            assertInstanceOf(String.class, variantDto.getAttributesJson());
            assertInstanceOf(BigDecimal.class, variantDto.getWeightKg());
            assertInstanceOf(ProductDetailDto.class, variantDto.getProduct());
            assertInstanceOf(List.class, variantDto.getImages());

            // Values
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), variantDto.getId());
            assertEquals(25, variantDto.getStockQuantity());
            assertEquals("{\"color\":\"blue\"}", variantDto.getAttributesJson());
            assertEquals(0, new BigDecimal("1.50").compareTo(variantDto.getWeightKg()));

            // ProductDetailDto nested in variant (only has 'name')
            assertNotNull(variantDto.getProduct());
            assertEquals("Test Product", variantDto.getProduct().getName());

            // ProductImageDto images
            assertNotNull(variantDto.getImages());
            assertEquals(2, variantDto.getImages().size());
            ProductImageDto img1 = variantDto.getImages().get(0);
            assertInstanceOf(String.class, img1.getId());
            assertEquals("55555555-5555-5555-5555-555555555555", img1.getId());
            assertEquals("/images/test1.jpg", img1.getImageUrl());
            assertEquals(1, img1.getSortOrder());
            assertTrue(img1.isFeatured());

            ProductImageDto img2 = variantDto.getImages().get(1);
            assertEquals("66666666-6666-6666-6666-666666666666", img2.getId());
            assertEquals("/images/test2.jpg", img2.getImageUrl());
            assertEquals(2, img2.getSortOrder());
            assertFalse(img2.isFeatured());
        }

        @Test
        @DisplayName("response path variant does NOT carry sku/status/prices (reduced variant)")
        void responseVariantIsReduced()
        {
            OrderEntity order = buildOrder();

            OrderResponseDto dto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto variantDto = dto.getItems().get(0).getVariant();

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
    class ToDetailDtoTests
    {

        @Test
        @DisplayName("pins OrderDetailRespDto field set: id(UUID), customerEntity(CustomerDto — canonical, post customer-merge), totalAmount, sessionId(UUID), status(OrderStatusEn), shipping fields, items(List<OrderItemDetailDto>), createdAt, statusHistory")
        @SuppressWarnings("unchecked")
        void pinsOrderDetailRespDtoShape()
        {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.getId(), buildHistory(order));

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto);
            assertInstanceOf(UUID.class, dto.getId());
            assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), dto.getId());

            assertInstanceOf(UUID.class, dto.getSessionId());
            assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), dto.getSessionId());

            assertInstanceOf(OrderStatusEn.class, dto.getStatus());
            assertEquals(OrderStatusEn.PAID, dto.getStatus());

            assertEquals(0, new BigDecimal("500.00").compareTo(dto.getTotalAmount()));
            assertEquals(LocalDateTime.of(2026, 7, 20, 12, 0, 0), dto.getCreatedAt());

            // Shipping
            assertEquals("10 Test Road", dto.getShippingAddressLine1());
            assertNull(dto.getShippingAddressLine2());
            assertEquals("Johannesburg", dto.getShippingCity());
            assertEquals("Gauteng", dto.getShippingProvince());
            assertEquals("2000", dto.getShippingPostalCode());
            assertNull(dto.getShippingPhone());

            // Customer — canonical CustomerDto (CustomerDetailDto merged away)
            assertInstanceOf(CustomerDto.class, dto.getCustomerEntity());
            assertEquals("test@example.com", dto.getCustomerEntity().getEmail());
        }

        @Test
        @DisplayName("pins OrderItemDetailDto shape in detail path: {id: String, unitPrice: BigDecimal, quantity: Integer, variant: ProductVariantDetailDto}")
        @SuppressWarnings("unchecked")
        void pinsOrderItemDetailDtoShapeInDetail()
        {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.getId(), Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertEquals(1, dto.getItems().size());
            OrderItemDetailDto itemDto = dto.getItems().get(0);

            // Shape: canonical DTO uses String id
            assertInstanceOf(String.class, itemDto.getId());
            assertInstanceOf(BigDecimal.class, itemDto.getUnitPrice());
            assertInstanceOf(Integer.class, itemDto.getQuantity());
            assertInstanceOf(ProductVariantDetailDto.class, itemDto.getVariant());

            // Values
            assertEquals("88888888-8888-8888-8888-888888888888", itemDto.getId());
            assertEquals(0, new BigDecimal("250.00").compareTo(itemDto.getUnitPrice()));
            assertEquals(2, itemDto.getQuantity());
        }

        @Test
        @DisplayName("pins ProductVariantDetailDto nested in detail path: {id: UUID, stockQuantity, attributesJson, weightKg, product: ProductDetailDto, images: List<ProductImageDto>}")
        @SuppressWarnings("unchecked")
        void pinsProductVariantDetailDtoShapeInDetail()
        {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.getId(), Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);
            ProductVariantDetailDto variantDto = dto.getItems().get(0).getVariant();

            // Shape: ProductVariantDetailDto has id as UUID
            assertInstanceOf(UUID.class, variantDto.getId());
            assertInstanceOf(Integer.class, variantDto.getStockQuantity());
            assertInstanceOf(String.class, variantDto.getAttributesJson());
            assertInstanceOf(BigDecimal.class, variantDto.getWeightKg());
            assertInstanceOf(ProductDetailDto.class, variantDto.getProduct());
            assertInstanceOf(List.class, variantDto.getImages());

            // Values
            assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), variantDto.getId());
            assertEquals(25, variantDto.getStockQuantity());
            assertEquals("{\"color\":\"blue\"}", variantDto.getAttributesJson());
            assertEquals(0, new BigDecimal("1.50").compareTo(variantDto.getWeightKg()));

            // ProductDetailDto nested in variant (only has 'name')
            assertNotNull(variantDto.getProduct());
            assertEquals("Test Product", variantDto.getProduct().getName());

            // ProductImageDto images
            assertNotNull(variantDto.getImages());
            assertEquals(2, variantDto.getImages().size());
            ProductImageDto img1 = variantDto.getImages().get(0);
            assertInstanceOf(String.class, img1.getId());
            assertEquals("55555555-5555-5555-5555-555555555555", img1.getId());
            assertEquals("/images/test1.jpg", img1.getImageUrl());
            assertEquals(1, img1.getSortOrder());
            assertTrue(img1.isFeatured());

            ProductImageDto img2 = variantDto.getImages().get(1);
            assertEquals("66666666-6666-6666-6666-666666666666", img2.getId());
            assertEquals("/images/test2.jpg", img2.getImageUrl());
            assertEquals(2, img2.getSortOrder());
            assertFalse(img2.isFeatured());
        }

        @Test
        @DisplayName("pins unification: both response and detail paths produce the same canonical OrderItemDetailDto type with ProductVariantDetailDto")
        @SuppressWarnings("unchecked")
        void pinsBothPathsProduceSameCanonicalDto()
        {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.getId(), Collections.emptyList());

            // Response path
            OrderResponseDto responseDto = orderMapper.toResponseDto(order);
            OrderItemDetailDto responseItem = responseDto.getItems().get(0);

            // Detail path
            OrderDetailRespDto detailDto = orderMapper.toDetailDto(order);
            OrderItemDetailDto detailItem = detailDto.getItems().get(0);

            // Both use the same DTO type
            assertInstanceOf(OrderItemDetailDto.class, responseItem);
            assertInstanceOf(OrderItemDetailDto.class, detailItem);

            // Both carry ProductVariantDetailDto
            assertInstanceOf(ProductVariantDetailDto.class, responseItem.getVariant());
            assertInstanceOf(ProductVariantDetailDto.class, detailItem.getVariant());

            // Both id fields are String (canonical reconciliation)
            assertInstanceOf(String.class, responseItem.getId());
            assertInstanceOf(String.class, detailItem.getId());

            // Same values
            assertEquals(responseItem.getId(), detailItem.getId());
            assertEquals(responseItem.getVariant().getId(), detailItem.getVariant().getId());
        }

        @Test
        @DisplayName("pins image DTO unification: both paths use ProductImageDto (String id, has isFeatured)")
        @SuppressWarnings("unchecked")
        void pinsImageDtoUnifiedBetweenResponseAndDetail()
        {
            OrderEntity order = buildOrder();
            stubHistoryQuery(order.getId(), Collections.emptyList());

            // Response path
            OrderResponseDto responseDto = orderMapper.toResponseDto(order);
            ProductVariantDetailDto responseVariant = responseDto.getItems().get(0).getVariant();
            assertFalse(responseVariant.getImages().isEmpty());
            ProductImageDto responseImg = responseVariant.getImages().get(0);
            assertInstanceOf(String.class, responseImg.getId());
            assertTrue(responseImg.isFeatured());

            // Detail path
            OrderDetailRespDto detailDto = orderMapper.toDetailDto(order);
            ProductVariantDetailDto detailVariant = detailDto.getItems().get(0).getVariant();
            assertFalse(detailVariant.getImages().isEmpty());
            ProductImageDto detailImg = detailVariant.getImages().get(0);
            assertInstanceOf(String.class, detailImg.getId());
            assertTrue(detailImg.isFeatured());

            // Both produce the same image id value
            assertEquals(responseImg.getId(), detailImg.getId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OrderStatusHistoryDetailRespDto — pins current shape (order field already removed)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OrderStatusHistoryDetailRespDto shape")
    class StatusHistoryShapeTests
    {

        @Test
        @DisplayName("pins current shape: {id: UUID, status: OrderStatusEn, comment: String, changedBy: String, createdAt: LocalDateTime}")
        @SuppressWarnings("unchecked")
        void pinsStatusHistoryDtoShape()
        {
            OrderEntity order = buildOrder();
            List<OrderStatusHistoryEntity> history = buildHistory(order);
            stubHistoryQuery(order.getId(), history);

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto.getStatusHistory());
            assertEquals(2, dto.getStatusHistory().size());

            OrderStatusHistoryDetailRespDto h1 = dto.getStatusHistory().get(0);
            assertInstanceOf(UUID.class, h1.getId());
            assertEquals(UUID.fromString("aaa11111-1111-1111-1111-111111111111"), h1.getId());

            assertInstanceOf(OrderStatusEn.class, h1.getStatus());
            assertEquals(OrderStatusEn.PAID, h1.getStatus());

            assertEquals("Payment received", h1.getComment());
            assertEquals("SYSTEM", h1.getChangedBy());
            assertEquals(LocalDateTime.of(2026, 7, 20, 12, 5, 0), h1.getCreatedAt());
        }

        @Test
        @DisplayName("pins that the 'order' field does NOT exist on OrderStatusHistoryDetailRespDto (leaked entity already removed)")
        void pinsOrderFieldAlreadyRemoved()
        {
            Field[] fields = OrderStatusHistoryDetailRespDto.class.getDeclaredFields();
            for (Field field : fields) {
                assertNotEquals("order", field.getName(),
                        "OrderStatusHistoryDetailRespDto should not have a leaked 'order' field");
            }
        }

        @Test
        @DisplayName("pins that comment and changedBy fields are preserved (not null when populated)")
        @SuppressWarnings("unchecked")
        void pinsCommentAndChangedByPreserved()
        {
            OrderEntity order = buildOrder();
            List<OrderStatusHistoryEntity> history = buildHistory(order);
            stubHistoryQuery(order.getId(), history);

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            OrderStatusHistoryDetailRespDto h1 = dto.getStatusHistory().get(0);
            assertNotNull(h1.getComment());
            assertNotNull(h1.getChangedBy());

            OrderStatusHistoryDetailRespDto h2 = dto.getStatusHistory().get(1);
            assertNull(h2.getComment());
            assertEquals("admin-user-1", h2.getChangedBy());
        }

        @Test
        @DisplayName("pins the complete field set of OrderStatusHistoryDetailRespDto: exactly 5 fields")
        void pinsFieldSetSize()
        {
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
    class NullHandlingTests
    {

        @Test
        @DisplayName("toResponseDto(null) returns null")
        void toResponseDtoNullReturnsNull()
        {
            assertNull(orderMapper.toResponseDto(null));
        }

        @Test
        @DisplayName("toDetailDto(null) returns null")
        void toDetailDtoNullReturnsNull()
        {
            assertNull(orderMapper.toDetailDto(null));
        }

        @Test
        @DisplayName("order with null items list produces empty items")
        void orderWithNullItemsProducesEmptyResponse()
        {
            OrderEntity order = buildOrder();
            order.setItems(null);

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertNotNull(dto);
            assertEquals(0, dto.getItemCount());
        }

        @Test
        @DisplayName("item with null variant maps to null variant in response")
        void itemWithNullVariantMapsToNullInResponse()
        {
            OrderEntity order = buildOrder();
            order.getItems().get(0).setVariant(null);

            OrderResponseDto dto = orderMapper.toResponseDto(order);

            assertNotNull(dto.getItems().get(0));
            assertNull(dto.getItems().get(0).getVariant());
        }

        @Test
        @DisplayName("item with null variant maps to null variant in detail")
        @SuppressWarnings("unchecked")
        void itemWithNullVariantMapsToNullInDetail()
        {
            OrderEntity order = buildOrder();
            order.getItems().get(0).setVariant(null);
            stubHistoryQuery(order.getId(), Collections.emptyList());

            OrderDetailRespDto dto = orderMapper.toDetailDto(order);

            assertNotNull(dto.getItems().get(0));
            assertNull(dto.getItems().get(0).getVariant());
        }
    }
}
