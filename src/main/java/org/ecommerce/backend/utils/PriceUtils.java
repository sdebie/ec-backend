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
        return new BigDecimal(0);

        //TODO:: SDB Calc current price window
    }
}
