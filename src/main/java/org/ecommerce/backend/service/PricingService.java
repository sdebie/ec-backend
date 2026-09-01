package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PricingService
{
    @Inject
    VariantPricesRepository variantPricesRepository;

    /**
     * Returns the active price for the given variant and customer tier.
     * Prefers sale price if active; falls back to base price.
     * Returns BigDecimal.ZERO if no price is found.
     */
    public BigDecimal getActivePrice(UUID variantId, CustomerTypeEn tier)
    {
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

        List<VariantPricesEntity> prices = variantPricesRepository.findByVariantId(variantId);

        BigDecimal salePrice = PriceUtils.currentPrice(prices, salePriceType);
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0) {
            return salePrice;
        }

        BigDecimal basePrice = PriceUtils.currentPrice(prices, basePriceType);
        return basePrice != null ? basePrice : BigDecimal.ZERO;
    }
}
