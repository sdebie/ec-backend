package org.ecommerce.backend.utils;

import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    private static boolean isWithinActiveWindow(VariantPricesEntity price, LocalDateTime now)
    {
        if (price.getPriceStartDate() != null && now.isBefore(price.getPriceStartDate())) {
            return false;
        }

        return price.getPriceEndDate() == null || !now.isAfter(price.getPriceEndDate());
    }
}
