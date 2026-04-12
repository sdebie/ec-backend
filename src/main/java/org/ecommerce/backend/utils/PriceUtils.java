package org.ecommerce.backend.utils;

import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

public class PriceUtils {

    private static final Comparator<VariantPricesEntity> PRICE_RECENCY_COMPARATOR =
            Comparator.comparing((VariantPricesEntity price) -> price.priceStartDate,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(price -> price.updatedAt,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(price -> price.createdAt,
                            Comparator.nullsFirst(LocalDateTime::compareTo))
                    .thenComparing(price -> price.id,
                            Comparator.nullsFirst(UUID::compareTo));

    /**
     * Get the latest active price for a variant and price type within the configured date window.
     * The method name is kept for compatibility with existing callers.
     */
    public static BigDecimal getMinimumPrice(UUID variantId, PriceTypeEn priceType) {
        if (variantId == null || priceType == null) {
            return BigDecimal.ZERO;
        }

        LocalDateTime now = LocalDateTime.now();

        return VariantPricesEntity.findByVariantId(variantId).stream()
                .filter(price -> price != null
                        && price.priceType == priceType
                        && price.price != null
                        && isWithinActiveWindow(price, now))
                .max(PRICE_RECENCY_COMPARATOR)
                .map(price -> price.price)
                .orElse(BigDecimal.ZERO);
    }

    private static boolean isWithinActiveWindow(VariantPricesEntity price, LocalDateTime now) {
        if (price.priceStartDate != null && now.isBefore(price.priceStartDate)) {
            return false;
        }

        return price.priceEndDate == null || !now.isAfter(price.priceEndDate);
    }
}
