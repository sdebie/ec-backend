package org.ecommerce.backend.assembler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
public class ProductListItemAssembler {

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    ProductImageRepository imageRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductMapper productMapper;

    public AdminProductListItemDto buildAdminListItem(ProductEntity product, LocalDateTime now) {
        return buildAdminListItems(List.of(product), now).getFirst();
    }

    /**
     * Builds an admin product page using three bounded preload queries (variants,
     * primary-variant images, and retail prices), rather than querying per row.
     */
    public List<AdminProductListItemDto> buildAdminListItems(List<ProductEntity> products, LocalDateTime now) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ProductVariantEntity>> variantsByProduct = variantsByProduct(products, true);
        List<UUID> primaryVariantIds = variantsByProduct.values().stream()
                .filter(variants -> !variants.isEmpty())
                .map(variants -> variants.getFirst().id)
                .toList();
        Map<UUID, ProductImageEntity> thumbnailByVariant = imageRepository.findForVariantIds(primaryVariantIds).stream()
                .collect(Collectors.groupingBy(image -> image.productVariant.id,
                        Collectors.collectingAndThen(Collectors.toList(), List::getFirst)));
        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct = pricesByProduct(
                products, List.of(PriceTypeEn.RETAIL_PRICE), now, false);

        return products.stream()
                .map(product -> buildAdminListItem(product, variantsByProduct.getOrDefault(product.id, List.of()),
                        thumbnailByVariant, pricesByProduct.getOrDefault(product.id, Map.of())))
                .toList();
    }

    private AdminProductListItemDto buildAdminListItem(
            ProductEntity product,
            List<ProductVariantEntity> variants,
            Map<UUID, ProductImageEntity> thumbnailByVariant,
            Map<PriceTypeEn, VariantPricesEntity> prices) {
        AdminProductListItemDto dto = new AdminProductListItemDto();
        dto.id = product.id != null ? product.id.toString() : null;
        dto.name = product.name;
        dto.slug = product.slug;
        dto.status = product.status != null ? product.status.name() : null;

        CategoryEntity cat = product.getCategory();
        if (cat != null) {
            CategoryDto catDto = new CategoryDto();
            catDto.setId(cat.id);
            catDto.setName(cat.name);
            dto.category = catDto;
        }

        if (!variants.isEmpty()) {
            ProductVariantEntity primaryVariant = variants.get(0);
            dto.sku = primaryVariant.sku;
            ProductImageEntity thumb = thumbnailByVariant.get(primaryVariant.id);
            if (thumb != null) {
                dto.thumbnailUrl = thumb.imageUrl;
            }
        }

        dto.stockCount = variants.stream().mapToInt(variant -> variant.stockQuantity == null ? 0 : variant.stockQuantity).sum();
        dto.stockLevel = deriveStockLevel(dto.stockCount);

        VariantPricesEntity retail = prices.get(PriceTypeEn.RETAIL_PRICE);
        if (retail != null) {
            dto.retailPrice = retail.price.toPlainString();
        }

        return dto;
    }

    public ProductShoppingListItemDto buildShoppingListItem(ProductEntity product, LocalDateTime now, boolean ignoreStatus) {
        return buildShoppingListItems(List.of(product), now, ignoreStatus).getFirst();
    }

    /**
     * Builds a shopping product page using one bulk query each for variants,
     * images, and active price candidates. This is deliberately page-scoped so
     * query count is constant as the page size grows.
     */
    public List<ProductShoppingListItemDto> buildShoppingListItems(
            List<ProductEntity> products, LocalDateTime now, boolean ignoreStatus) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ProductVariantEntity>> variantsByProduct = variantsByProduct(products, ignoreStatus);
        Map<UUID, List<ProductImageEntity>> imagesByProduct = imageRepository.findForListingProductIds(productIds(products)).stream()
                .collect(Collectors.groupingBy(image -> image.productVariant.product.id));
        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct = pricesByProduct(products,
                List.of(PriceTypeEn.RETAIL_PRICE, PriceTypeEn.WHOLESALE_PRICE,
                        PriceTypeEn.RETAIL_SALE_PRICE, PriceTypeEn.WHOLESALE_SALE_PRICE), now, ignoreStatus);

        return products.stream()
                .map(product -> buildShoppingListItem(product,
                        variantsByProduct.getOrDefault(product.id, List.of()),
                        imagesByProduct.getOrDefault(product.id, List.of()),
                        pricesByProduct.getOrDefault(product.id, Map.of()), now))
                .toList();
    }

    private ProductShoppingListItemDto buildShoppingListItem(
            ProductEntity product,
            List<ProductVariantEntity> variants,
            List<ProductImageEntity> images,
            Map<PriceTypeEn, VariantPricesEntity> prices,
            LocalDateTime now) {
        ProductShoppingListItemDto dto = new ProductShoppingListItemDto();
        dto.id = product.id == null ? null : product.id.toString();
        dto.name = product.name;
        dto.slug = product.slug;
        dto.shortDescription = product.shorDescription;
        dto.productType = product.productType == null ? null : product.productType.name();
        dto.status = product.status == null ? null : product.status.name();

        dto.variantCount = variants.size();
        dto.variantId = product.productType != ProductTypeEn.SIMPLE
                ? null
                : variants.isEmpty() ? null : variants.getFirst().id.toString();
        dto.images = images.stream()
                .map(productMapper::mapImageEntityToDto)
                .toList();
        dto.retailPrice = toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_PRICE), now);
        dto.wholesalePrice = toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_PRICE), now);
        dto.retailSalePrice = toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_SALE_PRICE), now);
        dto.wholesaleSalePrice = toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_SALE_PRICE), now);
        return dto;
    }

    private Map<UUID, List<ProductVariantEntity>> variantsByProduct(List<ProductEntity> products, boolean ignoreStatus) {
        return variantRepository.findForProductIds(productIds(products), ignoreStatus).stream()
                .collect(Collectors.groupingBy(variant -> variant.product.id));
    }

    private Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByProduct(
            List<ProductEntity> products, List<PriceTypeEn> priceTypes, LocalDateTime now, boolean ignoreStatus) {
        Comparator<VariantPricesEntity> priceOrder = Comparator
                .comparing((VariantPricesEntity price) -> price.price)
                .thenComparing(price -> price.priceStartDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(price -> price.createdAt, Comparator.nullsFirst(Comparator.naturalOrder()));

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> result = new HashMap<>();
        for (VariantPricesEntity price : variantPricesRepository.findActiveForProductIds(
                productIds(products), priceTypes, now, ignoreStatus)) {
            UUID productId = price.variant.product.id;
            result.computeIfAbsent(productId, unused -> new EnumMap<>(PriceTypeEn.class))
                    .merge(price.priceType, price, (current, candidate) -> priceOrder.compare(current, candidate) <= 0 ? current : candidate);
        }
        return result;
    }

    private List<UUID> productIds(List<ProductEntity> products) {
        return products.stream()
                .map(product -> product.id)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    // ─── Pure field mapping (no queries) ────────────────────────────────────

    private String deriveStockLevel(int stockCount) {
        if (stockCount <= 0) {
            return "OUT_OF_STOCK";
        } else if (stockCount <= 10) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
    }

    private VariantPriceDto toVariantPriceDto(VariantPricesEntity price, LocalDateTime now) {
        if (price == null) {
            return null;
        }
        VariantPriceDto dto = new VariantPriceDto();
        dto.id = price.id == null ? null : price.id.toString();
        dto.priceType = price.priceType == null ? null : price.priceType.name();
        dto.price = price.price;
        dto.priceStartDate = price.priceStartDate;
        dto.priceEndDate = price.priceEndDate;
        dto.isActive = Boolean.TRUE;
        dto.saleDaysRemaining = calculateSaleDaysRemaining(price.priceType, price.priceEndDate, now);
        return dto;
    }

    private Long calculateSaleDaysRemaining(PriceTypeEn priceType, LocalDateTime endDate, LocalDateTime now) {
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
