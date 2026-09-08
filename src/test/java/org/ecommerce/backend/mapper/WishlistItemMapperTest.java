package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WishlistItemMapperTest
{
    private WishlistItemMapper mapper;
    private Instant now;

    @BeforeEach
    void setUp()
    {
        WishlistItemMapperImpl impl = new WishlistItemMapperImpl();
        impl.variantPriceMapper = new VariantPriceMapperImpl();
        mapper = impl;
        now = Instant.parse("2026-09-08T12:00:00Z");
    }

    @Test
    void copiesVariantAndProductFields()
    {
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductVariantEntity variant = variant(variantId, productId, ProductStatusEn.ACTIVE, 4);

        WishlistItemDto dto = mapper.toDto(variant, Map.of(), Map.of(), now);

        assertEquals(variantId, dto.getVariantId());
        assertEquals("{\"Size\":\"L\"}", dto.getVariantLabel());
        assertEquals("SKU-1", dto.getSku());
        assertEquals(productId, dto.getProductId());
        assertEquals("Lamp", dto.getProductName());
        assertEquals("lamp", dto.getProductSlug());
    }

    @Test
    void usesTheThumbnailUrlWhenPresent()
    {
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity variant = variant(variantId, UUID.randomUUID(), ProductStatusEn.ACTIVE, 1);
        ProductImageEntity image = new ProductImageEntity();
        image.setImageUrl("images/lamp.png");

        WishlistItemDto dto = mapper.toDto(variant, Map.of(), Map.of(variantId, image), now);

        assertEquals("images/lamp.png", dto.getImagePath());
    }

    @Test
    void leavesImagePathNullWhenThereIsNoThumbnail()
    {
        ProductVariantEntity variant = variant(UUID.randomUUID(), UUID.randomUUID(), ProductStatusEn.ACTIVE, 1);

        assertNull(mapper.toDto(variant, Map.of(), Map.of(), now).getImagePath());
    }

    @Test
    void productActiveAndInStockWhenProductAndVariantAreSellable()
    {
        ProductVariantEntity variant = variant(UUID.randomUUID(), UUID.randomUUID(), ProductStatusEn.ACTIVE, 4);

        WishlistItemDto dto = mapper.toDto(variant, Map.of(), Map.of(), now);

        assertTrue(dto.getProductActive());
        assertTrue(dto.getInStock());
    }

    @Test
    void outOfStockWhenQuantityIsZero()
    {
        ProductVariantEntity variant = variant(UUID.randomUUID(), UUID.randomUUID(), ProductStatusEn.ACTIVE, 0);

        WishlistItemDto dto = mapper.toDto(variant, Map.of(), Map.of(), now);

        assertTrue(dto.getProductActive());
        assertFalse(dto.getInStock());
    }

    @Test
    void notProductActiveWhenTheProductIsDisabled()
    {
        ProductVariantEntity variant = variant(UUID.randomUUID(), UUID.randomUUID(), ProductStatusEn.DISABLED, 4);

        WishlistItemDto dto = mapper.toDto(variant, Map.of(), Map.of(), now);

        assertFalse(dto.getProductActive());
        assertFalse(dto.getInStock());
    }

    @Test
    void mapsTheMatchingPriceTierThroughVariantPriceMapper()
    {
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity variant = variant(variantId, UUID.randomUUID(), ProductStatusEn.ACTIVE, 1);

        VariantPricesEntity retail = new VariantPricesEntity();
        retail.setId(UUID.randomUUID());
        retail.setPriceType(PriceTypeEn.RETAIL_PRICE);
        retail.setPrice(new BigDecimal("99.50"));
        retail.setVariant(variant);

        Map<PriceTypeEn, VariantPricesEntity> tiers = new EnumMap<>(PriceTypeEn.class);
        tiers.put(PriceTypeEn.RETAIL_PRICE, retail);

        WishlistItemDto dto = mapper.toDto(variant, Map.of(variantId, tiers), Map.of(), now);

        assertEquals(new BigDecimal("99.50"), dto.getRetailPrice().getPrice());
        assertNull(dto.getWholesalePrice());
    }

    private static ProductVariantEntity variant(
            UUID variantId, UUID productId, ProductStatusEn productStatus, Integer stock)
    {
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        product.setName("Lamp");
        product.setSlug("lamp");
        product.setStatus(productStatus);

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(variantId);
        variant.setSku("SKU-1");
        variant.setAttributesJson("{\"Size\":\"L\"}");
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(stock);
        variant.setProduct(product);
        return variant;
    }
}
