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
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
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

    List<ProductVariantDto> mapVariantEntitiesToDtos(List<ProductVariantEntity> entities);

    // ── VariantPricesEntity → VariantPriceDto ────────────────────────────

    @Mapping(target = "id",        expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "priceType", expression = "java(entity.priceType == null ? null : entity.priceType.name())")
    @Mapping(target = "isActive",  expression = "java(entity.isActive())")
    @Mapping(target = "saleDaysRemaining", expression = "java(calculateSaleDaysRemaining(entity))")
    org.ecommerce.common.dto.VariantPriceDto mapPriceEntityToDto(org.ecommerce.common.entity.VariantPricesEntity entity);

    // ── CategoryEntity → CategoryDto ──────────────────────────────────────

    CategoryDto mapCategoryEntityToDto(CategoryEntity entity);

    // ── BrandEntity → BrandDto ────────────────────────────────────────────

    BrandDto mapBrandEntityToDto(BrandEntity entity);

    // ── ProductEntity → ProductDto ─────────────────────────────────────────

    @Mapping(target = "id", expression = "java(entity.id == null ? null : entity.id.toString())")
    @Mapping(target = "shortDescription", source = "shorDescription")
    @Mapping(target = "productType", expression = "java(entity.productType == null ? null : entity.productType.name())")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", expression = "java(entity.createdAt == null ? null : entity.createdAt.toString())")
    @Mapping(target = "category", expression = "java(mapPrimaryCategory(entity))")
    @Mapping(target = "categories", expression = "java(mapCategoryList(entity))")
    @Mapping(target = "brand", source = "brand")
    @Mapping(target = "variants", ignore = true)
    ProductDto mapProductEntityToDto(ProductEntity entity);

    // ── ProductDto → ProductEntity scalar copy (create vs patch) ──────────────

    /** Copy scalar fields for a NEW product, applying SIMPLE/ACTIVE defaults. */
    default void applyCreatableFields(ProductDto src, ProductEntity target) {
        target.name = src.name;
        target.slug = src.slug;
        target.description = src.description;
        target.shorDescription = src.shortDescription;
        target.productType = src.productType != null ? ProductTypeEn.valueOf(src.productType) : ProductTypeEn.SIMPLE;
        target.status = src.status != null ? ProductStatusEn.valueOf(src.status) : ProductStatusEn.ACTIVE;
    }

    /** Patch scalar fields on an EXISTING product — only non-blank values are applied. */
    default void applyEditableFields(ProductDto src, ProductEntity target) {
        if (src.name != null && !src.name.isBlank()) target.name = src.name;
        if (src.slug != null && !src.slug.isBlank()) target.slug = src.slug;
        if (src.description != null && !src.description.isBlank()) target.description = src.description;
        if (src.shortDescription != null && !src.shortDescription.isBlank()) target.shorDescription = src.shortDescription;
        if (src.productType != null && !src.productType.isBlank()) target.productType = ProductTypeEn.valueOf(src.productType);
        if (src.status != null && !src.status.isBlank()) target.status = ProductStatusEn.valueOf(src.status);
    }

    // ── Composite: product + variants → ProductInformationDto ─────────────

    default ProductInformationDto mapToProductInformationDto(ProductEntity product,
                                                             List<ProductVariantEntity> variants)
    {
        if (product == null) return null;

        ProductDto productDto = mapProductEntityToDto(product);
        // Enrich product DTO with status
        if (product.status != null) {
            productDto.status = product.status.name();
        }

        List<ProductVariantDto> variantDtos = variants != null
                ? variants.stream().map(v -> {
                    ProductVariantDto dto = mapVariantEntityToDto(v);
                    // Enrich variant DTO with status
                    if (v.status != null) {
                        dto.status = v.status.name();
                    }
                    return dto;
                }).toList()
                : Collections.emptyList();

        return new ProductInformationDto(productDto, variantDtos);
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
