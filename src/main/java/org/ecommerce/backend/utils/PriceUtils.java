package org.ecommerce.backend.utils;

import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

public class PriceUtils
{

    private static final Comparator<VariantPricesEntity> PRICE_RECENCY_COMPARATOR =
            Comparator.comparing(VariantPricesEntity::getPriceStartDate,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(VariantPricesEntity::getUpdatedAt,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(VariantPricesEntity::getCreatedAt,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(VariantPricesEntity::getId,
                            Comparator.nullsFirst(UUID::compareTo));

    /**
     * Get the latest active price for a variant and price type within the configured date window.
     * The method name is kept for compatibility with existing callers.
     */
    public static BigDecimal getMinimumPrice(UUID variantId, PriceTypeEn priceType)
    {
        if (variantId == null || priceType == null) {
            return BigDecimal.ZERO;
        }

        LocalDateTime now = LocalDateTime.now();

        return VariantPricesEntity.findByVariantId(variantId).stream()
                .filter(price -> price != null
                        && price.getPriceType() == priceType
                        && price.getPrice() != null
                        && isWithinActiveWindow(price, now))
                .max(PRICE_RECENCY_COMPARATOR)
                .map(VariantPricesEntity::getPrice)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Whole days from {@code now} until a sale ends, floored at zero.
     * <p>
     * Only sale tiers carry a countdown; a non-sale tier or an open-ended sale returns
     * {@code null} so the client renders no countdown at all rather than a zero.
     * <p>
     * Single owner of the rule — catalogue list items, product detail, and wishlist
     * hydration all resolve the countdown here, so no surface can drift from another.
     *
     * @param priceType the tier the price belongs to
     * @param endDate   when the sale ends, {@code null} for open-ended
     * @param now       the clock the countdown is measured against
     * @return days remaining, or {@code null} when no countdown applies
     */
    public static Long saleDaysRemaining(PriceTypeEn priceType, LocalDateTime endDate, LocalDateTime now)
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

    private static boolean isWithinActiveWindow(VariantPricesEntity price, LocalDateTime now)
    {
        if (price.getPriceStartDate() != null && now.isBefore(price.getPriceStartDate())) {
            return false;
        }

        return price.getPriceEndDate() == null || !now.isAfter(price.getPriceEndDate());
    }
}
