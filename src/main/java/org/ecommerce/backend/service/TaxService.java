package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.entity.StoreSettingsEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;

@ApplicationScoped
public class TaxService
{
    private static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("0.15");
    private static final String VAT_RATE_KEY = "vat_rate_percent";

    /**
     * Calculates VAT on the given subtotal using the rate stored in store_settings.
     * Defaults to 0.15 (15%) if the key is missing or unparseable.
     */
    public BigDecimal calculateVat(BigDecimal subtotal)
    {
        if (subtotal == null || subtotal.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal rate = loadVatRate();
        return subtotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal loadVatRate()
    {
        StoreSettingsEntity setting = StoreSettingsEntity.findById(VAT_RATE_KEY);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
            return DEFAULT_VAT_RATE;
        }
        try {
            return new BigDecimal(setting.getValue().trim());
        } catch (NumberFormatException e) {
            return DEFAULT_VAT_RATE;
        }
    }
}
