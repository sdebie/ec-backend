package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductVariantDetailDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests pinning the output shape of the canonical image DTO ({@link ProductImageDto}).
 * <p>
 * After the DTO consolidation (task 2.2), both paths produce {@link ProductImageDto}:
 * - ProductMapper.mapImageEntityToDto  → ProductImageDto  (has isFeatured)
 * - OrderMapper.toItemDetailDto        → ProductImageDto  (has isFeatured — formerly ImageDetailDto without it)
 * - ProductMapper.mapVariantEntityToDto → ProductVariantDto.images (ProductImageDto)
 * <p>
 * The id type is reconciled to String (UUID.toString() in mapper).
 * isFeatured is coerced from null to false.
 * Former ImageDetailDto consumers now gain `featured` (purely additive).
 */
@DisplayName("Image DTO shape characterization (post-consolidation — canonical ProductImageDto)")
class ImageDtoCharacterizationTest
{
    private ProductMapper productMapper;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() throws Exception
    {
        productMapper = Mappers.getMapper(ProductMapper.class);

        orderMapper = new OrderMapperImpl();
        // The mapper is CDI-wired in production; constructed directly here, so its
        // collaborator is set by hand. The field lives on the generated impl.
        Field pmField = OrderMapperImpl.class.getDeclaredField("productMapper");
        pmField.setAccessible(true);
        pmField.set(orderMapper, productMapper);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ProductImageEntity imageEntity(UUID id, String url, Integer sortOrder, Boolean featured)
    {
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId(id);
        entity.setImageUrl(url);
        entity.setSortOrder(sortOrder);
        entity.setIsFeatured(featured);
        return entity;
    }

    private ProductVariantEntity variantEntity(UUID id, List<ProductImageEntity> images)
    {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(id);
        variant.setStockQuantity(10);
        variant.setAttributesJson("{\"size\":\"M\"}");
        variant.setWeightKg(BigDecimal.valueOf(1.5));
        variant.setImages(images);
        return variant;
    }

    private OrderItemEntity orderItemEntity(UUID variantId, List<ProductImageEntity> images)
    {
        ProductVariantEntity variant = variantEntity(variantId, images);
        ProductEntity product = new ProductEntity();
        product.setName("Test Product");
        variant.setProduct(product);

        OrderItemEntity item = new OrderItemEntity();
        item.setId(UUID.randomUUID());
        item.setUnitPrice(BigDecimal.valueOf(99.99));
        item.setQuantity(2);
        item.setVariant(variant);
        return item;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ProductImageDto shape (via ProductMapper)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProductImageDto shape — via ProductMapper.mapImageEntityToDto")
    class ProductImageDtoShape
    {

        @Test
        @DisplayName("produces {id: String, imageUrl: String, sortOrder: Integer, isFeatured: boolean}")
        void currentShape_allFieldsPresent()
        {
            UUID imgId = UUID.randomUUID();
            ProductImageEntity entity = imageEntity(imgId, "/images/hero.jpg", 1, true);

            ProductImageDto dto = productMapper.mapImageEntityToDto(entity);

            // id is String (UUID.toString())
            assertNotNull(dto.getId());
            assertEquals(imgId.toString(), dto.getId());
            assertInstanceOf(String.class, dto.getId());

            // imageUrl is String
            assertEquals("/images/hero.jpg", dto.getImageUrl());
            assertInstanceOf(String.class, dto.getImageUrl());

            // sortOrder is Integer
            assertEquals(1, dto.getSortOrder());
            assertInstanceOf(Integer.class, dto.getSortOrder());

            // isFeatured is primitive boolean (not null)
            assertTrue(dto.isFeatured());
        }

        @Test
        @DisplayName("isFeatured coerces null entity value to false")
        void isFeatured_nullCoercedToFalse()
        {
            ProductImageEntity entity = imageEntity(UUID.randomUUID(), "/img.jpg", 0, null);

            ProductImageDto dto = productMapper.mapImageEntityToDto(entity);

            assertFalse(dto.isFeatured(), "null isFeatured on entity must coerce to false");
        }

        @Test
        @DisplayName("isFeatured preserves false")
        void isFeatured_preservesFalse()
        {
            ProductImageEntity entity = imageEntity(UUID.randomUUID(), "/img.jpg", 0, false);

            ProductImageDto dto = productMapper.mapImageEntityToDto(entity);

            assertFalse(dto.isFeatured());
        }

        @Test
        @DisplayName("null id maps to null String")
        void nullId_mapsToNull()
        {
            ProductImageEntity entity = imageEntity(null, "/img.jpg", 0, true);

            ProductImageDto dto = productMapper.mapImageEntityToDto(entity);

            assertNull(dto.getId(), "null UUID id should map to null String");
        }

        @Test
        @DisplayName("altText maps through from the entity; absent stays null")
        void altText_mapsThrough()
        {
            ProductImageEntity withAlt = imageEntity(UUID.randomUUID(), "/img.jpg", 0, true);
            withAlt.setAltText("Hero shot of the product");
            assertEquals("Hero shot of the product", productMapper.mapImageEntityToDto(withAlt).getAltText());

            ProductImageEntity withoutAlt = imageEntity(UUID.randomUUID(), "/img.jpg", 0, true);
            assertNull(productMapper.mapImageEntityToDto(withoutAlt).getAltText(),
                    "images without alt text must expose null, not empty string");
        }

        @Test
        @DisplayName("ProductImageDto has exactly the fields: id, imageUrl, sortOrder, isFeatured, altText")
        void fieldShape()
        {
            var fieldNames = java.util.Arrays.stream(ProductImageDto.class.getDeclaredFields())
                    .map(Field::getName)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(java.util.Set.of("id", "imageUrl", "sortOrder", "featured", "altText"), fieldNames,
                    "ProductImageDto shape changed — update this characterization deliberately");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Order-item detail path now produces ProductImageDto (was ImageDetailDto)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Order-item detail path — now produces ProductImageDto (consolidated from ImageDetailDto)")
    class OrderItemDetailImageShape
    {

        @Test
        @DisplayName("produces ProductImageDto {id: String, imageUrl: String, sortOrder: Integer, isFeatured: boolean} — formerly ImageDetailDto")
        void currentShape_orderItemDetail()
        {
            UUID imgId = UUID.randomUUID();
            UUID variantId = UUID.randomUUID();
            ProductImageEntity imgEntity = imageEntity(imgId, "/images/product.jpg", 2, true);
            OrderItemEntity orderItem = orderItemEntity(variantId, List.of(imgEntity));

            OrderItemDetailDto itemDto = invokeToItemDetailDto(orderItem);

            assertNotNull(itemDto.getVariant());
            assertNotNull(itemDto.getVariant().getImages());
            assertEquals(1, itemDto.getVariant().getImages().size());

            ProductImageDto imageDto = itemDto.getVariant().getImages().get(0);

            // id is now String (UUID.toString()) — reconciled from former UUID
            assertNotNull(imageDto.getId());
            assertEquals(imgId.toString(), imageDto.getId());
            assertInstanceOf(String.class, imageDto.getId());

            // imageUrl is String
            assertEquals("/images/product.jpg", imageDto.getImageUrl());
            assertInstanceOf(String.class, imageDto.getImageUrl());

            // sortOrder is Integer
            assertEquals(2, imageDto.getSortOrder());
            assertInstanceOf(Integer.class, imageDto.getSortOrder());

            // isFeatured is now present (purely additive for former ImageDetailDto consumers)
            assertTrue(imageDto.isFeatured());
        }

        @Test
        @DisplayName("isFeatured coerces null to false in the detail path")
        void isFeatured_nullCoercedToFalseInDetailPath()
        {
            UUID imgId = UUID.randomUUID();
            // Entity has null isFeatured — must coerce to false
            ProductImageEntity imgEntity = imageEntity(imgId, "/img.jpg", 0, null);
            OrderItemEntity orderItem = orderItemEntity(UUID.randomUUID(), List.of(imgEntity));

            OrderItemDetailDto itemDto = invokeToItemDetailDto(orderItem);
            ProductImageDto imageDto = itemDto.getVariant().getImages().get(0);

            assertFalse(imageDto.isFeatured(), "null isFeatured must coerce to false in detail path");
        }

        @Test
        @DisplayName("ProductVariantDetailDto.images is now List<ProductImageDto>")
        void variantDetailDto_imagesCarryProductImageDto()
        {
            Field imagesField;
            try {
                imagesField = ProductVariantDetailDto.class.getDeclaredField("images");
            } catch (NoSuchFieldException e) {
                fail("ProductVariantDetailDto must have an 'images' field");
                return;
            }

            var genericType = imagesField.getGenericType();
            assertTrue(genericType.getTypeName().contains("ProductImageDto"),
                    "ProductVariantDetailDto.images should be List<ProductImageDto>, got: " + genericType.getTypeName());
        }

        private OrderItemDetailDto invokeToItemDetailDto(OrderItemEntity item)
        {
            return orderMapper.toItemDetailDto(item);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ProductVariantDto.images uses ProductImageDto (variant detail path)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Variant detail — ProductVariantDto.images carries ProductImageDto (with isFeatured)")
    class VariantDetailProductImageDtoShape
    {

        @Test
        @DisplayName("ProductVariantDto.images is List<ProductImageDto> with isFeatured")
        void variantDto_imagesCarryProductImageDto()
        {
            UUID imgId = UUID.randomUUID();
            UUID variantId = UUID.randomUUID();
            ProductImageEntity imgEntity = imageEntity(imgId, "/images/variant.jpg", 0, true);
            ProductVariantEntity variant = variantEntity(variantId, List.of(imgEntity));

            ProductVariantDto dto = productMapper.mapVariantEntityToDto(variant);

            assertNotNull(dto.getImages());
            assertEquals(1, dto.getImages().size());

            ProductImageDto imageDto = dto.getImages().get(0);
            assertEquals(imgId.toString(), imageDto.getId());
            assertEquals("/images/variant.jpg", imageDto.getImageUrl());
            assertEquals(0, imageDto.getSortOrder());
            assertTrue(imageDto.isFeatured());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OrderResponseDto path — uses canonical OrderItemDetailDto with ProductVariantDetailDto.images (ProductImageDto)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OrderResponseDto path — item variant images are ProductImageDto (via ProductVariantDetailDto)")
    class OrderResponseDtoImageShape
    {

        @Test
        @DisplayName("OrderResponseDto order items carry ProductImageDto via ProductVariantDetailDto.images (consolidated)")
        void orderResponseItems_useProductImageDto() throws Exception
        {
            UUID imgId = UUID.randomUUID();
            UUID variantId = UUID.randomUUID();
            ProductImageEntity imgEntity = imageEntity(imgId, "/images/order-item.jpg", 1, false);

            ProductVariantEntity variant = variantEntity(variantId, List.of(imgEntity));
            ProductEntity product = new ProductEntity();
            product.setId(UUID.randomUUID());
            product.setName("Order Product");
            variant.setProduct(product);

            OrderItemEntity item = new OrderItemEntity();
            item.setId(UUID.randomUUID());
            item.setUnitPrice(BigDecimal.valueOf(50.00));
            item.setQuantity(1);
            item.setVariant(variant);

            // Call the private toItemDetailDto (consolidated — was formerly toItemDto)
            var method = OrderMapper.class.getDeclaredMethod("toItemDetailDto", OrderItemEntity.class);
            method.setAccessible(true);
            var itemDto = (OrderItemDetailDto) method.invoke(orderMapper, item);

            assertNotNull(itemDto.getVariant());
            assertNotNull(itemDto.getVariant().getImages());
            assertEquals(1, itemDto.getVariant().getImages().size());

            ProductImageDto imageDto = itemDto.getVariant().getImages().get(0);
            // This path produces ProductImageDto (with isFeatured)
            assertEquals(imgId.toString(), imageDto.getId());
            assertEquals("/images/order-item.jpg", imageDto.getImageUrl());
            assertEquals(1, imageDto.getSortOrder());
            assertFalse(imageDto.isFeatured());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Summary: both paths now use String id (reconciled)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Id type reconciled — both paths produce ProductImageDto with String id")
    class IdTypeReconciled
    {

        @Test
        @DisplayName("ProductImageDto.id is java.lang.String (canonical)")
        void productImageDto_idIsString() throws Exception
        {
            Field idField = ProductImageDto.class.getDeclaredField("id");
            assertEquals(String.class, idField.getType());
        }

        @Test
        @DisplayName("Both order response and order detail paths produce String id images")
        void bothPaths_produceStringIdImages()
        {
            UUID imgId = UUID.randomUUID();
            ProductImageEntity imgEntity = imageEntity(imgId, "/img.jpg", 0, false);
            OrderItemEntity orderItem = orderItemEntity(UUID.randomUUID(), List.of(imgEntity));

            // Detail path
            OrderItemDetailDto detailItem = invokeToItemDetailDto(orderItem);
            ProductImageDto detailImg = detailItem.getVariant().getImages().get(0);
            assertInstanceOf(String.class, detailImg.getId());
            assertEquals(imgId.toString(), detailImg.getId());
        }

        private OrderItemDetailDto invokeToItemDetailDto(OrderItemEntity item)
        {
            return orderMapper.toItemDetailDto(item);
        }
    }
}
