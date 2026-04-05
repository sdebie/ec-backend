package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductListDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Collections;
import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi",
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ProductMapper
{
    // ── ProductImageEntity → ProductImageDto ──────────────────────────────

    @Mapping(target = "id",         expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "isFeatured", expression = "java(entity.isFeatured != null && entity.isFeatured)")
    ProductImageDto mapImageEntityToDto(ProductImageEntity entity);

    List<ProductImageDto> mapImageEntitiesToDtos(List<ProductImageEntity> entities);

    // ── VariantPricesEntity → VariantPriceDto ────────────────────────────

    @Mapping(target = "id",        expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "priceType", expression = "java(entity.priceType == null ? null : entity.priceType.name())")
    @Mapping(target = "isActive",  expression = "java(entity.isActive())")
    VariantPriceDto mapPriceEntityToDto(VariantPricesEntity entity);

    // ── ProductVariantEntity → ProductVariantDto ─────────────────────────

    @Mapping(target = "id",                 expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "retailPrice",        ignore = true)
    @Mapping(target = "retailSalesPrice",   ignore = true)
    @Mapping(target = "wholesalePrice",     ignore = true)
    @Mapping(target = "wholesaleSalesPrice",ignore = true)
    @Mapping(target = "variantPrices",      source = "variantPrices")
    ProductVariantDto mapVariantEntityToDto(ProductVariantEntity entity);

    List<ProductVariantDto> mapVariantEntitiesToDtos(List<ProductVariantEntity> entities);

    /**
     * Derives the four convenience price fields from the mapped {@code variantPrices} list.
     * Runs automatically after {@link #mapVariantEntityToDto(ProductVariantEntity)}.
     */
    @AfterMapping
    default void computePrices(ProductVariantEntity entity, @MappingTarget ProductVariantDto dto)
    {
        if (entity.variantPrices == null) return;
        for (VariantPricesEntity price : entity.variantPrices) {
            if (!price.isActive()) continue;
            switch (price.priceType) {
                case RETAIL_PRICE        -> dto.retailPrice         = price.price;
                case RETAIL_SALE_PRICE   -> dto.retailSalesPrice    = price.price;
                case WHOLESALE_PRICE     -> dto.wholesalePrice      = price.price;
                case WHOLESALE_SALE_PRICE-> dto.wholesaleSalesPrice = price.price;
            }
        }
    }

    // ── Composite: variants + images → ProductListDto ────────────────────

    /**
     * Builds a {@link ProductListDto} from the raw entity collections.
     * Product name and description are resolved from the first variant's product relation.
     */
    default ProductListDto mapToProductListDto(String productId,
                                               List<ProductVariantEntity> variants,
                                               List<ProductImageEntity> images)
    {
        List<ProductImageDto> imageDtos  = images  != null ? mapImageEntitiesToDtos(images)      : Collections.emptyList();
        List<ProductVariantDto> variantDtos = variants != null ? mapVariantEntitiesToDtos(variants) : Collections.emptyList();

        String name        = null;
        String description = null;
        if (variants != null && !variants.isEmpty() && variants.getFirst().product != null) {
            name        = variants.getFirst().product.name;
            description = variants.getFirst().product.description;
        }

        return new ProductListDto(productId, name, description, imageDtos, variantDtos);
    }
}


