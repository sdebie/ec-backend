package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.List;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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

    // ── ProductVariantEntity → ProductVariantDto ─────────────────────────

    @Mapping(target = "id",      expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "prices",  source = "prices")
    @Mapping(target = "images",  source = "images")
    ProductVariantDto mapVariantEntityToDto(ProductVariantEntity entity);

    // ── VariantPricesEntity → VariantPriceDto ────────────────────────────

    @Mapping(target = "id",        expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "priceType", expression = "java(entity.priceType == null ? null : entity.priceType.name())")
    @Mapping(target = "isActive",  expression = "java(entity.isActive())")
    @Mapping(target = "saleDaysRemaining", expression = "java(calculateSaleDaysRemaining(entity))")
    org.ecommerce.common.dto.VariantPriceDto mapPriceEntityToDto(org.ecommerce.common.entity.VariantPricesEntity entity);

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
    @Mapping(target = "category", expression = "java(mapPrimaryCategory(entity))")
    @Mapping(target = "categories", expression = "java(mapCategoryList(entity))")
    @Mapping(target = "brand", source = "brand")
    @Mapping(target = "variants", ignore = true)
    ProductDto mapProductEntityToDto(ProductEntity entity);

    // ── Composite: product + variants → ProductInformationDto ─────────────

    default ProductInformationDto mapToProductInformationDto(ProductEntity product,
                                                             List<ProductVariantEntity> variants)
    {
        if (product == null) return null;

        List<ProductVariantDto> variantDtos = variants != null ? mapVariantEntitiesToDtos(variants) : Collections.emptyList();

        return new ProductInformationDto(mapProductEntityToDto(product), variantDtos);
    }

     default Long calculateSaleDaysRemaining(VariantPricesEntity entity)
     {
         if (entity == null || entity.priceType == null || entity.priceEndDate == null) return null;

         if (entity.priceType != PriceTypeEn.RETAIL_SALE_PRICE
                 && entity.priceType != PriceTypeEn.WHOLESALE_SALE_PRICE) {
             return null;
         }

         LocalDate today = LocalDate.now();
         LocalDate endDate = entity.priceEndDate.toLocalDate();
         long daysRemaining = ChronoUnit.DAYS.between(today, endDate);
         return Math.max(daysRemaining, 0L);
     }

     default CategoryDto mapPrimaryCategory(ProductEntity entity) {
         if (entity == null || entity.categories == null || entity.categories.isEmpty()) {
             return null;
         }
         return mapCategoryEntityToDto(entity.categories.iterator().next());
     }

     default List<CategoryDto> mapCategoryList(ProductEntity entity) {
         if (entity == null || entity.categories == null) {
             return Collections.emptyList();
         }
         return entity.categories.stream()
                 .map(this::mapCategoryEntityToDto)
                 .toList();
     }
}
