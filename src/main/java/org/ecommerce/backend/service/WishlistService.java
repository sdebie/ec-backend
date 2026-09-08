package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.mapper.WishlistItemMapper;
import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.repository.*;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class WishlistService
{
    static final int MAX_ITEM_IDS = 50;

    private static final Logger LOG = Logger.getLogger(WishlistService.class);

    @Inject
    WishlistItemRepository wishlistItemRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    CustomerRepository customerRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductImageRepository productImageRepository;

    @Inject
    WishlistItemMapper wishlistItemMapper;

    public enum AddResult
    {
        CREATED,
        ALREADY_EXISTS,
        VARIANT_NOT_FOUND
    }

    public List<UUID> getVariantIds(UUID customerId) {
        List<WishlistItemEntity> items = wishlistItemRepository.findByCustomerId(customerId);
        return items
                .stream()
                .map(item -> item.getVariant().getId())
                .collect(Collectors.toList());
    }

    /**
     * Resolves variant IDs to display items with availability flags.
     *
     * <p>IDs that do not match any database row (hard-deleted) are omitted.
     * Every other variant is returned with {@code productActive} ({@code true}
     * when the parent product is ACTIVE) and {@code inStock} ({@code true} when
     * productActive AND variant ACTIVE AND stock &gt; 0).
     *
     * @param variantIds at most {@value #MAX_ITEM_IDS} IDs
     * @throws IllegalArgumentException if more than {@value #MAX_ITEM_IDS} IDs are supplied
     */
    public List<WishlistItemDto> getItems(List<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }

        if (variantIds.size() > MAX_ITEM_IDS) {
            throw new IllegalArgumentException("Maximum 50 variant IDs per request");
        }

        Instant now = Instant.now();

        List<ProductVariantEntity> resolvedVariants = productVariantRepository.findByIdsWithProduct(variantIds);

        if (resolvedVariants.isEmpty()) {
            LOG.debugv("Wishlist items: none of {0} requested variants resolved", variantIds.size());
            return List.of();
        }

        List<UUID> resolvedVariantIds = resolvedVariants
                .stream()
                .map(ProductVariantEntity::getId)
                .toList();

        List<PriceTypeEn> allPriceTypes = List.of(
                PriceTypeEn.RETAIL_PRICE,
                PriceTypeEn.WHOLESALE_PRICE,
                PriceTypeEn.RETAIL_SALE_PRICE,
                PriceTypeEn.WHOLESALE_SALE_PRICE);

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> pricesByVariant =
                buildPricesByVariant(resolvedVariantIds, allPriceTypes, now);

        Map<UUID, ProductImageEntity> thumbnailByVariant = productImageRepository.findForVariantIds(resolvedVariantIds)
                .stream()
                .collect(Collectors.toMap(
                        img -> img.getProductVariant().getId(),
                        img -> img,
                        (first, duplicate) -> first));

        return resolvedVariants
                .stream()
                .map(variant -> wishlistItemMapper.toDto(variant, pricesByVariant, thumbnailByVariant, now))
                .toList();
    }

    @Transactional
    public AddResult addToWishlist(UUID customerId, UUID variantId) {
        ProductVariantEntity variant = productVariantRepository.findById(variantId);
        if (variant == null) {
            LOG.warn("Attempt to add non-existent variant to wishlist: variantId=" + variantId);
            return AddResult.VARIANT_NOT_FOUND;
        }

        WishlistItemEntity existing = wishlistItemRepository.findByCustomerAndVariant(customerId, variantId);
        if (existing != null) {
            return AddResult.ALREADY_EXISTS;
        }

        CustomerEntity customer = customerRepository.findById(customerId);
        WishlistItemEntity newItem = new WishlistItemEntity();
        newItem.setCustomer(customer);
        newItem.setVariant(variant);
        wishlistItemRepository.persist(newItem);

        return AddResult.CREATED;
    }

    @Transactional
    public void removeFromWishlist(UUID customerId, UUID variantId) {
        wishlistItemRepository.deleteByCustomerAndVariant(customerId, variantId);
    }

    private Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> buildPricesByVariant(List<UUID> variantIds, List<PriceTypeEn> priceTypes, Instant now) {

        Comparator<VariantPricesEntity> priceOrder = Comparator
                .comparing(VariantPricesEntity::getPrice)
                .thenComparing(VariantPricesEntity::getPriceStartDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(VariantPricesEntity::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

        Map<UUID, Map<PriceTypeEn, VariantPricesEntity>> result = new java.util.HashMap<>();

        for (VariantPricesEntity price : variantPricesRepository.findActiveForVariantIds(variantIds, priceTypes, now)) {
            UUID variantId = price.getVariant().getId();
            result.computeIfAbsent(variantId, unused -> new EnumMap<>(PriceTypeEn.class))
                    .merge(price.getPriceType(), price, (current, candidate) ->
                            priceOrder.compare(current, candidate) <= 0 ? current : candidate);
        }

        return result;
    }
}
