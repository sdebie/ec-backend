package org.ecommerce.backend.utils;

import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PriceUtils {

    /**
     * Get the minimum price for a product across all variants for a specific price type.
     */
    public static BigDecimal getMinimumPrice(UUID productId, PriceTypeEn priceType) {
        if (productId == null) return new BigDecimal(0);

        List<ProductVariantEntity> variants = ProductVariantEntity.listByProductIdWithProduct(productId);
        if (variants.isEmpty()) new BigDecimal(0);

        return variants.stream()
                .flatMap(v -> v.variantPrices.stream())
                .filter(p -> p.priceType.equals(priceType) &&
                           p.isActive())
                .map(p -> p.price)
                .min(BigDecimal::compareTo)
                .orElse(new BigDecimal(0));
    }
}
