package org.ecommerce.backend.mapper;

import jakarta.inject.Inject;
import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;
import static org.mapstruct.ReportingPolicy.ERROR;

/**
 * Assembles a wishlist card from a catalogue variant — not from the membership row.
 * Prices go through {@link VariantPriceMapper} so sale-countdown rules stay in one place.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR,
        imports = PriceTypeEn.class,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public abstract class WishlistItemMapper
{
    @Inject
    protected VariantPriceMapper variantPriceMapper;

    @Mapping(target = "variantId", source = "id")
    @Mapping(target = "variantLabel", source = "attributesJson")
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    @Mapping(target = "productSlug", source = "product.slug")
    @Mapping(target = "imagePath", expression = "java(imagePath(thumbnailByVariant == null ? null : thumbnailByVariant.get(variant.getId())))")
    @Mapping(target = "retailPrice", expression = "java(variantPriceMapper.toDto(priceTier(pricesByVariant, variant.getId(), PriceTypeEn.RETAIL_PRICE), now))")
    @Mapping(target = "wholesalePrice", expression = "java(variantPriceMapper.toDto(priceTier(pricesByVariant, variant.getId(), PriceTypeEn.WHOLESALE_PRICE), now))")
    @Mapping(target = "retailSalePrice", expression = "java(variantPriceMapper.toDto(priceTier(pricesByVariant, variant.getId(), PriceTypeEn.RETAIL_SALE_PRICE), now))")
    @Mapping(target = "wholesaleSalePrice", expression = "java(variantPriceMapper.toDto(priceTier(pricesByVariant, variant.getId(), PriceTypeEn.WHOLESALE_SALE_PRICE), now))")
    @Mapping(target = "productActive", expression = "java(productActive(variant))")
    @Mapping(target = "inStock", expression = "java(inStock(variant))")
    public abstract WishlistItemDto toDto(ProductVariantEntity variant, @Context Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByVariant, @Context Map<UUID, ProductImageEntity> thumbnailByVariant, @Context Instant now);

    protected String imagePath(ProductImageEntity image) {
        return image == null ? null : image.getImageUrl();
    }

    protected VariantPricesEntity priceTier(
            Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByVariant,
            UUID variantId,
            PriceTypeEn type) {
        if (pricesByVariant == null) {
            return null;
        }
        Map<PriceTypeEn, VariantPricesEntity> prices = pricesByVariant.get(variantId);
        return prices == null ? null : prices.get(type);
    }

    protected boolean productActive(ProductVariantEntity variant) {
        return variant.getProduct() != null
                && variant.getProduct().getStatus() == ProductStatusEn.ACTIVE;
    }

    protected boolean inStock(ProductVariantEntity variant) {
        return productActive(variant)
                && variant.getStatus() == ProductStatusEn.ACTIVE
                && variant.getStockQuantity() != null
                && variant.getStockQuantity() > 0;
    }
}
