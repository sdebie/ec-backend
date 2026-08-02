package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.dto.WishlistHydratedItemDto;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves a list of variant IDs into displayable wishlist entries with availability flags.
 *
 * <p>Contract: variant IDs that do not match any database row (hard-deleted) are omitted from
 * the response. All other variants — regardless of product or variant status — are returned
 * with {@code productActive} and {@code inStock} flags so the frontend can render the
 * appropriate UI state (purchasable, out-of-stock, or no-longer-available).
 */
@ApplicationScoped
public class WishlistHydrationService
{
    private static final Logger LOG = Logger.getLogger(WishlistHydrationService.class);

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductImageRepository productImageRepository;

    /**
     * Resolves variant IDs to hydrated wishlist items with availability flags.
     *
     * <p>Nonexistent variant IDs (hard-deleted) are omitted. Every other variant is returned
     * with {@code productActive} ({@code true} when the parent product is ACTIVE) and
     * {@code inStock} ({@code true} when productActive AND variant ACTIVE AND stock &gt; 0).
     *
     * @param variantIds the variant IDs to hydrate (max 50 per the resource cap)
     * @return hydrated items with availability flags; never null
     */
    public List<WishlistHydratedItemDto> hydrate(List<UUID> variantIds)
    {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. Fetch all requested variants with their products (status-agnostic; flags derived below)
        List<ProductVariantEntity> resolvedVariants = productVariantRepository.findByIdsWithProduct(variantIds);

        if (resolvedVariants.isEmpty()) {
            LOG.debugv("Hydration: none of {0} requested variants resolved", variantIds.size());
            return List.of();
        }

        List<UUID> resolvedVariantIds = resolvedVariants
                .stream()
                .map(ProductVariantEntity::getId)
                .toList();

        // 2. Batch-fetch active prices for resolved variants (all four tier types)
        List<PriceTypeEn> allPriceTypes = List.of(
                PriceTypeEn.RETAIL_PRICE,
                PriceTypeEn.WHOLESALE_PRICE,
                PriceTypeEn.RETAIL_SALE_PRICE,
                PriceTypeEn.WHOLESALE_SALE_PRICE);

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByVariant = buildPricesByVariant(resolvedVariantIds, allPriceTypes, now);

        // 3. Batch-fetch featured/first images for resolved variants
        Map<UUID, ProductImageEntity> thumbnailByVariant = productImageRepository.findForVariantIds(resolvedVariantIds)
                .stream()
                .collect(Collectors.toMap(
                        img -> img.getProductVariant().getId(),
                        img -> img,
                        (first, duplicate) -> first));

        // 4. Assemble WishlistHydratedItemDto per variant
        return resolvedVariants
                .stream()
                .map(variant -> assembleItem(variant, pricesByVariant, thumbnailByVariant, now))
                .toList();
    }

    private Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> buildPricesByVariant(List<UUID> variantIds, List<PriceTypeEn> priceTypes, LocalDateTime now)
    {

        Comparator<VariantPricesEntity> priceOrder = Comparator
                .comparing((VariantPricesEntity price) -> price.getPrice())
                .thenComparing(price -> price.getPriceStartDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(price -> price.getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> result = new java.util.HashMap<>();

        for (VariantPricesEntity price : variantPricesRepository.findActiveForVariantIds(variantIds, priceTypes, now)) {
            UUID variantId = price.getVariant().getId();
            result.computeIfAbsent(variantId, unused -> new EnumMap<>(PriceTypeEn.class))
                    .merge(price.getPriceType(), price, (current, candidate) -> priceOrder.compare(current, candidate) <= 0 ? current : candidate);
        }

        return result;
    }

    private WishlistHydratedItemDto assembleItem(ProductVariantEntity variant, Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByVariant, Map<UUID, ProductImageEntity> thumbnailByVariant, LocalDateTime now)
    {

        WishlistHydratedItemDto dto = new WishlistHydratedItemDto();
        dto.setVariantId(variant.getId());
        dto.setVariantLabel(variant.getAttributesJson());
        dto.setSku(variant.getSku());
        dto.setProductId(variant.getProduct().getId());
        dto.setProductName(variant.getProduct().getName());
        dto.setProductSlug(variant.getProduct().getSlug());

        // Image
        ProductImageEntity image = thumbnailByVariant.get(variant.getId());
        dto.setImagePath(image != null ? image.getImageUrl() : null);

        // Prices
        Map<PriceTypeEn, VariantPricesEntity> prices = pricesByVariant.getOrDefault(variant.getId(), Map.of());
        dto.setRetailPrice(toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_PRICE), now));
        dto.setWholesalePrice(toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_PRICE), now));
        dto.setRetailSalePrice(toVariantPriceDto(prices.get(PriceTypeEn.RETAIL_SALE_PRICE), now));
        dto.setWholesaleSalePrice(toVariantPriceDto(prices.get(PriceTypeEn.WHOLESALE_SALE_PRICE), now));

        // Availability flags
        boolean productActive = variant.getProduct().getStatus() == ProductStatusEn.ACTIVE;
        dto.setProductActive(productActive);
        dto.setInStock(productActive
                && variant.getStatus() == ProductStatusEn.ACTIVE
                && variant.getStockQuantity() != null
                && variant.getStockQuantity() > 0);

        return dto;
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
