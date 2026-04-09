package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
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
    @Mapping(target = "price_start_date",   ignore = true)
    @Mapping(target = "price_end_date",     ignore = true)
    @Mapping(target = "variantPrices",      source = "variantPrices")
    ProductVariantDto mapVariantEntityToDto(ProductVariantEntity entity);

    List<ProductVariantDto> mapVariantEntitiesToDtos(List<ProductVariantEntity> entities);

    // ── CategoryEntity → CategoryDto ──────────────────────────────────────

    CategoryDto mapCategoryEntityToDto(CategoryEntity entity);

    // ── BrandEntity → BrandDto ────────────────────────────────────────────

    BrandDto mapBrandEntityToDto(BrandEntity entity);

    // ── ProductEntity → ProductDto ─────────────────────────────────────────

    @Mapping(target = "id", expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "shortDescription", source = "shorDescription")
    @Mapping(target = "productType", expression = "java(entity.productType == null ? null : entity.productType.name())")
    @Mapping(target = "createdAt", expression = "java(entity.createdAt == null ? null : entity.createdAt.toString())")
    @Mapping(target = "category", source = "category")
    @Mapping(target = "brand", source = "brand")
    ProductDto mapProductEntityToDto(ProductEntity entity);

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

    default ProductInformationDto mapToProductInformationDto(ProductEntity product,
                                                             List<ProductVariantEntity> variants,
                                                             List<ProductImageEntity> images)
    {
        if (product == null) return null;

        List<ProductImageDto> imageDtos  = images  != null ? mapImageEntitiesToDtos(images)      : Collections.emptyList();
        List<ProductVariantDto> variantDtos = variants != null ? mapVariantEntitiesToDtos(variants) : Collections.emptyList();

        return new ProductInformationDto(mapProductEntityToDto(product), imageDtos, variantDtos);
    }
}


