package org.ecommerce.backend.assembler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-model assembler for product list-item DTOs (admin + shopping).
 * <p>
 * These DTOs need related prices, images, and variants, so this collaborator
 * coordinates page-level repository preloads — it does not open queries itself,
 * and it is not a pure mapper. Repositories own the queries; services orchestrate
 * repository → assembler. Pure field copying lives here.
 * <p>
 * Replaces the former query-bearing {@code ProductListItemMapper} and the DTO
 * assembly that briefly lived inside {@code ProductRepository}.
 */
@ApplicationScoped
public class ProductListItemAssembler
{
    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    ProductImageRepository imageRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductMapper productMapper;

    public AdminProductListItemDto buildAdminListItem(ProductEntity product, LocalDateTime now)
    {
        return buildAdminListItems(List.of(product), now).getFirst();
    }

    /**
     * Builds an admin product page using three bounded preload queries (variants,
     * primary-variant images, and retail prices), rather than querying per row.
     */
    public List<AdminProductListItemDto> buildAdminListItems(List<ProductEntity> products, LocalDateTime now)
    {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ProductVariantEntity>> variantsByProduct = variantsByProduct(products, true);
        List<UUID> primaryVariantIds = variantsByProduct
                .values()
                .stream()
                .filter(variants -> !variants.isEmpty())
                .map(variants -> variants.getFirst().getId())
                .toList();

        Map<UUID, ProductImageEntity> thumbnailByVariant = imageRepository.findForVariantIds(primaryVariantIds).stream().collect(Collectors.groupingBy(image -> image.getProductVariant().getId(), Collectors.collectingAndThen(Collectors.toList(), List::getFirst)));
        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct = pricesByProduct(products, List.of(PriceTypeEn.RETAIL_PRICE), now, false);

        return products
                .stream()
                .map(product -> buildAdminListItem(product, variantsByProduct.getOrDefault(product.getId(), List.of()),
                        thumbnailByVariant, pricesByProduct.getOrDefault(product.getId(), Map.of())))
                .toList();
    }

    private AdminProductListItemDto buildAdminListItem(ProductEntity product, List<ProductVariantEntity> variants, Map<UUID, ProductImageEntity> thumbnailByVariant, Map<PriceTypeEn, VariantPricesEntity> prices)
    {
        AdminProductListItemDto dto = new AdminProductListItemDto();
        dto.setId(product.getId() != null ? product.getId().toString() : null);
        dto.setName(product.getName());
        dto.setSlug(product.getSlug());
        dto.setStatus(product.getStatus() != null ? product.getStatus().name() : null);

        CategoryEntity cat = product.getCategory();
        if (cat != null) {
            CategoryDto catDto = new CategoryDto();
            catDto.setId(cat.getId());
            catDto.setName(cat.getName());
            dto.setCategory(catDto);
        }

        if (!variants.isEmpty()) {
            ProductVariantEntity primaryVariant = variants.get(0);
            dto.setSku(primaryVariant.getSku());
            ProductImageEntity thumb = thumbnailByVariant.get(primaryVariant.getId());
            if (thumb != null) {
                dto.setThumbnailUrl(thumb.getImageUrl());
            }
        }

        dto.setStockCount(variants.stream().mapToInt(variant -> variant.getStockQuantity() == null ? 0 : variant.getStockQuantity()).sum());
        dto.setStockLevel(deriveStockLevel(dto.getStockCount()));

        VariantPricesEntity retail = prices.get(PriceTypeEn.RETAIL_PRICE);
        if (retail != null) {
            dto.setRetailPrice(retail.getPrice().toPlainString());
        }

        return dto;
    }

    public ProductShoppingListItemDto buildShoppingListItem(ProductEntity product, LocalDateTime now, boolean ignoreStatus)
    {
        return buildShoppingListItems(List.of(product), now, ignoreStatus).getFirst();
    }

    /**
     * Builds a shopping product page using one bulk query each for variants,
     * images, and active price candidates. This is deliberately page-scoped so
     * query count is constant as the page size grows.
     */
    public List<ProductShoppingListItemDto> buildShoppingListItems(
            List<ProductEntity> products, LocalDateTime now, boolean ignoreStatus)
    {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ProductVariantEntity>> variantsByProduct = variantsByProduct(products, ignoreStatus);
        Map<UUID, List<ProductImageEntity>> imagesByProduct = imageRepository.findForListingProductIds(productIds(products))
                .stream()
                .collect(Collectors.groupingBy(image -> image.getProductVariant().getProduct().getId()));
        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct = pricesByProduct(products, List.of(PriceTypeEn.RETAIL_PRICE, PriceTypeEn.WHOLESALE_PRICE, PriceTypeEn.RETAIL_SALE_PRICE, PriceTypeEn.WHOLESALE_SALE_PRICE), now, ignoreStatus);

        return products
                .stream()
                .map(product -> buildShoppingListItem(product,
                        variantsByProduct.getOrDefault(product.getId(), List.of()),
                        imagesByProduct.getOrDefault(product.getId(), List.of()),
                        pricesByProduct.getOrDefault(product.getId(), Map.of()), now))
                .toList();
    }

    private ProductShoppingListItemDto buildShoppingListItem(ProductEntity product, List<ProductVariantEntity> variants, List<ProductImageEntity> images, Map<PriceTypeEn, VariantPricesEntity> prices, LocalDateTime now)
    {
        ProductShoppingListItemDto dto = new ProductShoppingListItemDto();
        dto.setId(product.getId() == null ? null : product.getId().toString());
        dto.setName(product.getName());
        dto.setSlug(product.getSlug());
        dto.setShortDescription(product.getShortDescription());
        dto.setProductType(product.getProductType() == null ? null : product.getProductType().name());
        dto.setStatus(product.getStatus() == null ? null : product.getStatus().name());

        dto.setVariantCount(variants.size());
        dto.setVariantId(product.getProductType() != ProductTypeEn.SIMPLE ? null : variants.isEmpty() ? null : variants.get(0).getId().toString());
        dto.setSku(product.getProductType() != ProductTypeEn.SIMPLE ? null
                : variants.isEmpty() ? null : variants.get(0).getSku());
        dto.setInStock(variants.stream()
                .anyMatch(v -> v.getStockQuantity() != null && v.getStockQuantity() > 0));
        dto.setImages(images
                .stream()
                .map(productMapper::mapImageEntityToDto)
                .toList());
        dto.setRetailPrice(toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_PRICE), now));
        dto.setWholesalePrice(toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_PRICE), now));
        dto.setRetailSalePrice(toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_SALE_PRICE), now));
        dto.setWholesaleSalePrice(toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_SALE_PRICE), now));
        return dto;
    }

    private Map<UUID, List<ProductVariantEntity>> variantsByProduct(List<ProductEntity> products, boolean ignoreStatus)
    {
        return variantRepository.findForProductIds(productIds(products), ignoreStatus)
                .stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));
    }

    private Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct(
            List<ProductEntity> products, List<PriceTypeEn> priceTypes, LocalDateTime now, boolean ignoreStatus)
    {
        Comparator<VariantPricesEntity> priceOrder = Comparator
                .comparing((VariantPricesEntity price) -> price.getPrice())
                .thenComparing(price -> price.getPriceStartDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(price -> price.getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> result = new HashMap<>();
        for (VariantPricesEntity price : variantPricesRepository.findActiveForProductIds(productIds(products), priceTypes, now, ignoreStatus)) {
            UUID productId = price.getVariant().getProduct().getId();
            result.computeIfAbsent(productId, unused -> new EnumMap<>(PriceTypeEn.class))
                    .merge(price.getPriceType(), price, (current, candidate) -> priceOrder.compare(current, candidate) <= 0 ? current : candidate);
        }
        return result;
    }

    private List<UUID> productIds(List<ProductEntity> products)
    {
        return products.stream()
                .map(ProductEntity::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    // ─── Pure field mapping (no queries) ────────────────────────────────────

    private String deriveStockLevel(int stockCount)
    {
        if (stockCount <= 0) {
            return "OUT_OF_STOCK";
        } else if (stockCount <= 10) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
    }

    private VariantPriceDto toVariantPriceDto(VariantPricesEntity price, LocalDateTime now)
    {
        if (price == null) {
            return null;
        }
        VariantPriceDto dto = new VariantPriceDto();
        dto.setId(price.getId() == null ? null : price.getId().toString());
        dto.setPriceType(price.getPriceType() == null ? null : price.getPriceType().name());
        dto.setPrice(price.getPrice());
        dto.setPriceStartDate(price.getPriceStartDate());
        dto.setPriceEndDate(price.getPriceEndDate());
        dto.setIsActive(Boolean.TRUE);
        dto.setSaleDaysRemaining(calculateSaleDaysRemaining(price.getPriceType(), price.getPriceEndDate(), now));
        return dto;
    }

    private Long calculateSaleDaysRemaining(PriceTypeEn priceType, LocalDateTime endDate, LocalDateTime now)
    {
        if (priceType == null || endDate == null) {
            return null;
        }
        if (priceType != PriceTypeEn.RETAIL_SALE_PRICE && priceType != PriceTypeEn.WHOLESALE_SALE_PRICE) {
            return null;
        }
        long daysRemaining = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate());
        return Math.max(daysRemaining, 0L);
    }
}
