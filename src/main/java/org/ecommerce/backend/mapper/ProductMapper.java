package org.ecommerce.backend.mapper;

import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ERROR, uses = TimestampMapper.class,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface ProductMapper
{
    @Mapping(target = "featured", expression = "java(entity.getIsFeatured() != null && entity.getIsFeatured())")
    ProductImageDto mapImageEntityToDto(ProductImageEntity entity);

    List<ProductImageDto> mapImageEntitiesToDtos(List<ProductImageEntity> entities);

    @Mapping(target = "product", ignore = true)
    @Mapping(target = "prices", source = "prices")
    @Mapping(target = "images", source = "images")
    ProductVariantDto mapVariantEntityToDto(ProductVariantEntity entity);

    List<ProductVariantDto> mapVariantEntitiesToDtos(List<ProductVariantEntity> entities);

    @Mapping(target = "isActive", expression = "java(entity.isActive())")
    @Mapping(target = "saleDaysRemaining", expression = "java(calculateSaleDaysRemaining(entity))")
    org.ecommerce.common.dto.VariantPriceDto mapPriceEntityToDto(org.ecommerce.common.entity.VariantPricesEntity entity);

    CategoryDto mapCategoryEntityToDto(CategoryEntity entity);

    BrandDto mapBrandEntityToDto(BrandEntity entity);

    @Mapping(target = "shortDescription", source = "shortDescription")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "category", expression = "java(mapPrimaryCategory(entity))")
    @Mapping(target = "categories", expression = "java(mapCategoryList(entity))")
    @Mapping(target = "brand", source = "brand")
    @Mapping(target = "variants", ignore = true)
    ProductDto mapProductEntityToDto(ProductEntity entity);

    /**
     * Copy scalar fields for a NEW product, applying SIMPLE/ACTIVE defaults.
     */
    default void applyCreatableFields(ProductDto productDto, ProductEntity productEntity)
    {
        productEntity.setName(productDto.getName());
        productEntity.setSlug(productDto.getSlug());
        productEntity.setDescription(productDto.getDescription());
        productEntity.setShortDescription(productDto.getShortDescription());
        productEntity.setProductType(productDto.getProductType() != null ? ProductTypeEn.valueOf(productDto.getProductType()) : ProductTypeEn.SIMPLE);
        productEntity.setStatus(productDto.getStatus() != null ? ProductStatusEn.valueOf(productDto.getStatus()) : ProductStatusEn.ACTIVE);
    }

    /**
     * Patch scalar fields on an EXISTING product — only non-blank values are applied.
     */
    default void applyEditableFields(ProductDto src, ProductEntity target)
    {
        if (src.getName() != null && !src.getName().isBlank()) {
            target.setName(src.getName());
        }
        if (src.getSlug() != null && !src.getSlug().isBlank()) {
            target.setSlug(src.getSlug());
        }
        if (src.getDescription() != null && !src.getDescription().isBlank()) {
            target.setDescription(src.getDescription());
        }
        if (src.getShortDescription() != null && !src.getShortDescription().isBlank()) {
            target.setShortDescription(src.getShortDescription());
        }
        if (src.getProductType() != null && !src.getProductType().isBlank()) {
            target.setProductType(ProductTypeEn.valueOf(src.getProductType()));
        }
        if (src.getStatus() != null && !src.getStatus().isBlank()) {
            target.setStatus(ProductStatusEn.valueOf(src.getStatus()));
        }
    }

    default ProductInformationDto mapToProductInformationDto(ProductEntity product, List<ProductVariantEntity> variants)
    {
        if (product == null) return null;

        ProductDto productDto = mapProductEntityToDto(product);
        // Enrich product DTO with status
        if (product.getStatus() != null) {
            productDto.setStatus(product.getStatus().name());
        }

        List<ProductVariantDto> variantDtos = variants != null ? variants.stream().map(v -> {
            ProductVariantDto dto = mapVariantEntityToDto(v);
            // Enrich variant DTO with status
            if (v.getStatus() != null) {
                dto.setStatus(v.getStatus().name());
            }
            return dto;
        }).toList() : Collections.emptyList();

        return new ProductInformationDto(productDto, variantDtos);
    }

    default Long calculateSaleDaysRemaining(VariantPricesEntity variantPricesEntity)
    {
        if (variantPricesEntity == null) {
            return null;
        }
        return PriceUtils.saleDaysRemaining(variantPricesEntity.getPriceType(),
                variantPricesEntity.getPriceEndDate(), LocalDateTime.now());
    }

    default CategoryDto mapPrimaryCategory(ProductEntity productEntity)
    {
        if (productEntity == null || productEntity.getCategories() == null || productEntity.getCategories().isEmpty()) {
            return null;
        }
        return mapCategoryEntityToDto(productEntity.getCategories().iterator().next());
    }

    default List<CategoryDto> mapCategoryList(ProductEntity productEntity)
    {
        if (productEntity == null || productEntity.getCategories() == null) {
            return Collections.emptyList();
        }

        return productEntity.getCategories()
                .stream()
                .map(this::mapCategoryEntityToDto)
                .toList();
    }

}
