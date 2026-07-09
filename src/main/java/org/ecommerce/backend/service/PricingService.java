package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class PricingService {

    /**
     * Returns the active price for the given variant and customer tier.
     * Prefers sale price if active; falls back to base price.
     * Returns BigDecimal.ZERO if no price is found.
     */
    public BigDecimal getActivePrice(UUID variantId, CustomerTypeEn tier) {
        if (variantId == null || tier == null) {
            return BigDecimal.ZERO;
        }

        PriceTypeEn salePriceType;
        PriceTypeEn basePriceType;

        if (tier == CustomerTypeEn.WHOLESALER) {
            salePriceType = PriceTypeEn.WHOLESALE_SALE_PRICE;
            basePriceType = PriceTypeEn.WHOLESALE_PRICE;
        } else {
            // RETAILER and GUEST both use retail pricing
            salePriceType = PriceTypeEn.RETAIL_SALE_PRICE;
            basePriceType = PriceTypeEn.RETAIL_PRICE;
        }

        BigDecimal salePrice = PriceUtils.getMinimumPrice(variantId, salePriceType);
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0) {
            return salePrice;
        }

        BigDecimal basePrice = PriceUtils.getMinimumPrice(variantId, basePriceType);
        return basePrice != null ? basePrice : BigDecimal.ZERO;
    }
}
